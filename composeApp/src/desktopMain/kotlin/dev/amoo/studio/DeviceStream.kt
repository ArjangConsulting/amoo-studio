package dev.amoo.studio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Image

/** One line of `amoo device stream`'s NDJSON output; unknown events and fields are ignored. */
internal sealed interface DeviceStreamEvent {
	data class Started(val directory: String?) : DeviceStreamEvent
	data class Frame(val index: Long, val path: String, val width: Int, val height: Int) : DeviceStreamEvent
	data class Failure(val message: String) : DeviceStreamEvent
	data class Stopped(val frames: Long) : DeviceStreamEvent
}

internal fun Json.parseDeviceStreamEvent(line: String): DeviceStreamEvent? {
	val fields = runCatching { parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
	fun string(name: String) = (fields[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
	return when (string("event")) {
		"started" -> DeviceStreamEvent.Started(string("directory"))
		"frame" -> {
			val path = string("path") ?: return null
			DeviceStreamEvent.Frame(fields.long("frame") ?: 0, path, fields.int("width") ?: 0, fields.int("height") ?: 0)
		}
		"error" -> DeviceStreamEvent.Failure(string("message") ?: "Unknown stream error")
		"stopped" -> DeviceStreamEvent.Stopped(fields.long("frames") ?: 0)
		else -> null
	}
}

private fun JsonObject.long(name: String) = this[name]?.jsonPrimitive?.longOrNull
private fun JsonObject.int(name: String) = this[name]?.jsonPrimitive?.intOrNull

/** A running stream process; abstracted so the mirror lifecycle is testable without amoo. */
internal interface StreamProcess {
	val output: InputStream
	fun waitFor(): Int
	fun destroy()
}

internal fun interface StreamLauncher {
	fun launch(arguments: List<String>): StreamProcess
}

/** Launches `amoo device stream` with stderr discarded: stdout carries only NDJSON events. */
internal class AmooStreamLauncher(private val amoo: () -> String) : StreamLauncher {
	override fun launch(arguments: List<String>): StreamProcess {
		val process = ProcessBuilder(listOf(amoo()) + arguments)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
			.start()
		return object : StreamProcess {
			override val output: InputStream get() = process.inputStream
			override fun waitFor(): Int = process.waitFor()
			override fun destroy() {
				process.destroy()
				if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
			}
		}
	}
}

/** Decodes PNG or JPEG bytes into a bitmap; called off the UI thread. */
internal fun decodeImageBitmap(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()

/**
 * Runs the live mirror for at most one device at a time.
 *
 * `amoo device stream` captures on the host (simctl, devicectl, adb) without a device lease, so it can
 * watch a device while the console or an agent drives it. Frames are decoded off the UI thread and
 * conflated, so a slow decode skips frames instead of queueing them. A stream that fails is restarted
 * with backoff; one that exits with a usage error before starting means the connected Amoo predates
 * `device stream`, and is reported as unavailable instead of retried.
 */
internal class DeviceMirror(
	private val scope: CoroutineScope,
	private val launcher: StreamLauncher,
	private val onEvent: suspend (StudioEvent) -> Unit,
	private val decode: (ByteArray) -> ImageBitmap = ::decodeImageBitmap,
	private val framesPerSecond: Int = 6,
	private val json: Json = Json { ignoreUnknownKeys = true },
	private val backoffMillis: (attempt: Int) -> Long = { attempt -> (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L) },
) : AutoCloseable {
	private var current: Pair<String, Job>? = null

	/** Starts, switches, or stops the stream so it matches [device]. Call on every relevant state change. */
	fun update(device: StudioDevice?) {
		if (current?.first == device?.id) return
		current?.second?.cancel()
		current = null
		if (device == null) {
			scope.launch { onEvent(StudioEvent.MirrorStopped) }
			return
		}
		current = device.id to scope.launch(Dispatchers.IO) { stream(device) }
	}

	private suspend fun stream(device: StudioDevice) {
		var attempt = 0
		while (currentCoroutineContextActive()) {
			onEvent(StudioEvent.MirrorStarting(device.id))
			val outcome = try {
				runOnce(device)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Exception) {
				Outcome.Ended(failure.message ?: "Could not start the live view", receivedFrames = false)
			}
			when (outcome) {
				is Outcome.Unavailable -> { onEvent(StudioEvent.MirrorUnavailable(outcome.reason)); return }
				is Outcome.Ended -> {
					if (outcome.receivedFrames) attempt = 0
					onEvent(StudioEvent.MirrorReconnecting(device.id, outcome.reason))
					delay(backoffMillis(attempt++))
				}
			}
		}
	}

	private suspend fun currentCoroutineContextActive() = kotlin.coroutines.coroutineContext.isActive

	private sealed interface Outcome {
		data class Unavailable(val reason: String) : Outcome
		data class Ended(val reason: String, val receivedFrames: Boolean) : Outcome
	}

	private suspend fun runOnce(device: StudioDevice): Outcome {
		val directory = Files.createTempDirectory("amoo-mirror-").toFile()
		val process = launcher.launch(listOf(
			"device", "stream",
			"--platform", device.platform.name.lowercase(),
			"--device", device.id,
			"--fps", framesPerSecond.toString(),
			"--out", directory.absolutePath,
		))
		var started = false
		var frames = 0
		var lastError: String? = null
		try {
			coroutineScope {
				// Reading stdout blocks and ignores cancellation, so destroying the process is what ends it.
				// UNDISPATCHED enters the try at once: a job cancelled before this point still destroys the
				// process instead of skipping the body and leaving the reader blocked forever.
				launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { process.destroy() } }
				val pending = Channel<DeviceStreamEvent.Frame>(Channel.CONFLATED)
				val decoder = launch { decodeFrames(device.id, pending) { frames++ } }
				ensureActive()
				try {
					process.output.bufferedReader().useLines { lines ->
						lines.forEach { line ->
							when (val event = json.parseDeviceStreamEvent(line)) {
								is DeviceStreamEvent.Started -> started = true
								is DeviceStreamEvent.Frame -> pending.trySend(event)
								is DeviceStreamEvent.Failure -> lastError = event.message
								is DeviceStreamEvent.Stopped, null -> Unit
							}
						}
					}
				} catch (closed: java.io.IOException) {
					// Destroying the process on cancellation closes stdout under the reader.
					lastError = lastError ?: closed.message
				}
				pending.close()
				decoder.join()
				coroutineContext[Job]?.children?.forEach { it.cancel() }
			}
			val exit = process.waitFor()
			return when {
				!started && exit == USAGE_EXIT_CODE -> Outcome.Unavailable("Live view needs a newer Amoo with `amoo device stream`.")
				else -> Outcome.Ended(lastError ?: "Stream exited with code $exit", receivedFrames = frames > 0)
			}
		} finally {
			process.destroy()
			directory.deleteRecursively()
		}
	}

	private suspend fun decodeFrames(deviceId: String, pending: Channel<DeviceStreamEvent.Frame>, onDecoded: () -> Unit) {
		var previousNanos = 0L
		var averageFps = 0.0
		for (frame in pending) {
			val image = withContext(Dispatchers.Default) {
				// A frame file is rewritten four frames later, so a failed read just skips this frame.
				runCatching { decode(File(frame.path).readBytes()) }.getOrNull()
			} ?: continue
			val now = System.nanoTime()
			if (previousNanos != 0L) {
				val instant = 1_000_000_000.0 / (now - previousNanos).coerceAtLeast(1)
				averageFps = if (averageFps == 0.0) instant else averageFps * 0.8 + instant * 0.2
			}
			previousNanos = now
			onDecoded()
			onEvent(StudioEvent.MirrorFrameReceived(deviceId, DeviceFrame(frame.index, image, frame.width, frame.height), averageFps))
		}
	}

	override fun close() {
		current?.second?.cancel()
		current = null
	}

	private companion object {
		/** amoo's exit code for a usage error, such as a subcommand an older build does not know. */
		const val USAGE_EXIT_CODE = 64
	}
}
