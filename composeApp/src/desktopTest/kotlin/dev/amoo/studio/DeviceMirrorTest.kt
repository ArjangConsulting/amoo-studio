package dev.amoo.studio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

class DeviceMirrorTest {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val scratch = createTempDirectory("mirror-test").toFile()
	private val device = StudioDevice("sim-1", "iPhone 17", TestPlatform.Ios, "26", DeviceStatus.Running)

	@AfterTest
	fun tearDown() {
		scope.cancel()
		scratch.deleteRecursively()
	}

	// MARK: State

	@Test
	fun `frames from a replaced stream are dropped`() {
		val frame = DeviceFrame(1, ImageBitmap(1, 1), 1, 1)
		val state = StudioState().reduce(StudioEvent.MirrorStarting("sim-2"))

		val stale = state.reduce(StudioEvent.MirrorFrameReceived("sim-1", frame, 5.0))
		val current = state.reduce(StudioEvent.MirrorFrameReceived("sim-2", frame, 5.0))

		assertNull(stale.mirror.frame)
		assertEquals(MirrorStatus.Live, current.mirror.status)
		assertEquals(5.0, current.mirror.framesPerSecond)
	}

	@Test
	fun `pausing stops the desired stream and resuming restores it`() {
		val ready = StudioState(
			connection = ConnectionState.Ready("1", STUDIO_PROTOCOL_VERSION, emptyList()),
			section = StudioSection.Console,
			devices = listOf(device),
			selectedDeviceId = device.id,
		)

		val paused = ready.reduce(StudioEvent.ToggleMirrorPaused)

		assertEquals(device, ready.desiredMirrorDevice())
		assertNull(paused.desiredMirrorDevice())
		assertEquals(MirrorStatus.Paused, paused.mirror.status)
		assertEquals(device, paused.reduce(StudioEvent.ToggleMirrorPaused).desiredMirrorDevice())
		assertNull(ready.reduce(StudioEvent.SelectSection(StudioSection.Reports)).desiredMirrorDevice())
		assertNull(ready.copy(devices = listOf(device.copy(status = DeviceStatus.Available))).desiredMirrorDevice())
	}

	@Test
	fun `pinning shows a step until returning to live, and the split is clamped`() {
		val pinned = PinnedScreenshot("tap_element id=login", ScreenshotRef("/tmp/a.png"), GesturePoint(10.0, 20.0))

		val state = StudioState().reduce(StudioEvent.PinScreenshot(pinned))

		assertEquals(pinned, state.mirror.pinned)
		assertNull(state.reduce(StudioEvent.ReturnToLiveMirror).mirror.pinned)
		assertEquals(MAX_WORKBENCH_SPLIT, state.reduce(StudioEvent.ChangeWorkbenchSplit(2f)).workbenchSplit)
		assertEquals(MIN_WORKBENCH_SPLIT, state.reduce(StudioEvent.ChangeWorkbenchSplit(-1f)).workbenchSplit)
	}

	@Test
	fun `gesture points map from points to the shown bitmap's pixels`() {
		val screenshot = ScreenshotRef("/tmp/a.png", widthPixels = 1179, heightPixels = 2556, scale = 3.0)

		val offset = pointInImage(GesturePoint(196.5, 426.0), screenshot, ImageBitmap(393, 852))

		assertEquals(Offset(196.5f, 426f), offset)
		assertNull(pointInImage(GesturePoint(1.0, 1.0), ScreenshotRef("/tmp/a.png"), ImageBitmap(10, 10)))
	}

	// MARK: Stream events

	@Test
	fun `stream events parse and unknown lines are ignored`() {
		val json = Json { ignoreUnknownKeys = true }

		assertEquals(DeviceStreamEvent.Started("/tmp/x"), json.parseDeviceStreamEvent("""{"event":"started","platform":"ios","device":"a","directory":"/tmp/x"}"""))
		assertEquals(DeviceStreamEvent.Frame(12, "/tmp/x/frame-0.png", 369, 800), json.parseDeviceStreamEvent("""{"event":"frame","frame":12,"path":"/tmp/x/frame-0.png","width":369,"height":800,"elapsed_ms":2104}"""))
		assertEquals(DeviceStreamEvent.Failure("boom"), json.parseDeviceStreamEvent("""{"event":"error","message":"boom"}"""))
		assertNull(json.parseDeviceStreamEvent("""{"event":"future"}"""))
		assertNull(json.parseDeviceStreamEvent("not json"))
		assertNull(json.parseDeviceStreamEvent("""{"event":"frame"}"""))
	}

	// MARK: Lifecycle

	@Test
	fun `frames are decoded and reported for the streaming device`() = runBlocking<Unit> {
		val png = writePng()
		val events = Channel<StudioEvent>(Channel.UNLIMITED)
		val launcher = ScriptedLauncher { FakeProcess(lines("started", frame(png), frame(png)), exitCode = 0) }
		val mirror = DeviceMirror(scope, launcher, onEvent = { events.send(it) }, backoffMillis = { 60_000 })

		mirror.update(device)

		assertIs<StudioEvent.MirrorStarting>(events.receiveWithin())
		val frame = events.receiveUntil<StudioEvent.MirrorFrameReceived>()
		assertEquals("sim-1", frame.deviceId)
		assertEquals(listOf("device", "stream", "--platform", "ios", "--device", "sim-1", "--fps", "6"), launcher.arguments.first().take(8))
		mirror.close()
	}

	@Test
	fun `an Amoo without device stream is reported unavailable and not retried`() = runBlocking<Unit> {
		val events = Channel<StudioEvent>(Channel.UNLIMITED)
		val launcher = ScriptedLauncher { FakeProcess(ByteArrayInputStream(ByteArray(0)), exitCode = 64) }
		val mirror = DeviceMirror(scope, launcher, onEvent = { events.send(it) }, backoffMillis = { 0 })

		mirror.update(device)

		val unavailable = events.receiveUntil<StudioEvent.MirrorUnavailable>()
		assertTrue(unavailable.reason.contains("newer Amoo"))
		assertEquals(1, launcher.launches.get())
		mirror.close()
	}

	@Test
	fun `a failed stream reconnects`() = runBlocking<Unit> {
		val events = Channel<StudioEvent>(Channel.UNLIMITED)
		val launcher = ScriptedLauncher { FakeProcess(lines("started", """{"event":"error","message":"simctl failed"}"""), exitCode = 1) }
		val mirror = DeviceMirror(scope, launcher, onEvent = { events.send(it) }, backoffMillis = { 0 })

		mirror.update(device)

		assertEquals("simctl failed", events.receiveUntil<StudioEvent.MirrorReconnecting>().reason)
		events.receiveUntil<StudioEvent.MirrorReconnecting>()
		assertTrue(launcher.launches.get() >= 2)
		mirror.close()
	}

	@Test
	fun `stopping or switching devices destroys the running process`() = runBlocking<Unit> {
		val events = Channel<StudioEvent>(Channel.UNLIMITED)
		val processes = mutableListOf<FakeProcess>()
		val launcher = ScriptedLauncher { OpenEndedProcess().also { synchronized(processes) { processes += it } } }
		val mirror = DeviceMirror(scope, launcher, onEvent = { events.send(it) }, backoffMillis = { 60_000 })

		mirror.update(device)
		events.receiveUntil<StudioEvent.MirrorStarting>()
		waitUntil { synchronized(processes) { processes.size == 1 } }
		mirror.update(device.copy(id = "sim-2"))
		assertTrue(synchronized(processes) { processes[0] }.destroyed.await(5, TimeUnit.SECONDS))
		assertEquals("sim-2", events.receiveUntil<StudioEvent.MirrorStarting> { it.deviceId == "sim-2" }.deviceId)

		waitUntil { synchronized(processes) { processes.size == 2 } }
		mirror.update(null)
		assertTrue(synchronized(processes) { processes[1] }.destroyed.await(5, TimeUnit.SECONDS))
		events.receiveUntil<StudioEvent.MirrorStopped>()
	}

	// MARK: Screenshot loading

	@Test
	fun `screenshots are downscaled to the requested size`() = runBlocking<Unit> {
		val loader = DesktopScreenshotLoader()
		val image = loader.load(writePng(width = 1179, height = 2556).absolutePath, 320)

		assertEquals(320, image?.height)
		assertEquals(148, image?.width)
		assertNull(loader.load("/nonexistent.png", 320))
	}

	@Test
	fun `the cache stays within its byte budget`() = runBlocking<Unit> {
		val loader = DesktopScreenshotLoader(budgetBytes = 100L * 100 * 4)
		val first = writePng(width = 100, height = 100, name = "a.png")
		val second = writePng(width = 100, height = 100, name = "b.png")

		val cached = loader.load(first.absolutePath, 0)
		loader.load(second.absolutePath, 0)

		// The first image was evicted, so loading it again decodes a new bitmap.
		assertTrue(cached !== loader.load(first.absolutePath, 0))
	}

	// MARK: Helpers

	private fun writePng(width: Int = 4, height: Int = 8, name: String = "frame.png"): File {
		val surface = Surface.makeRasterN32Premul(width, height)
		val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
		return File(scratch, name).apply { writeBytes(data.bytes) }
	}

	private fun frame(file: File) = """{"event":"frame","frame":1,"path":"${file.absolutePath}","width":4,"height":8}"""

	private fun lines(vararg events: String): InputStream =
		ByteArrayInputStream(events.joinToString("\n") { if (it == "started") """{"event":"started","directory":"/tmp"}""" else it }.plus("\n").toByteArray())

	private suspend fun Channel<StudioEvent>.receiveWithin(): StudioEvent = withTimeout(5_000) { receive() }

	private suspend inline fun <reified T : StudioEvent> Channel<StudioEvent>.receiveUntil(crossinline matches: (T) -> Boolean = { true }): T =
		withTimeout(5_000) {
			while (true) {
				val event = receive()
				if (event is T && matches(event)) return@withTimeout event
			}
			@Suppress("UNREACHABLE_CODE") error("unreachable")
		}

	private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
		while (!condition()) kotlinx.coroutines.delay(10)
	}

	private class ScriptedLauncher(private val next: () -> StreamProcess) : StreamLauncher {
		val launches = AtomicInteger()
		val arguments = mutableListOf<List<String>>()

		override fun launch(arguments: List<String>): StreamProcess {
			launches.incrementAndGet()
			synchronized(this.arguments) { this.arguments += arguments }
			return next()
		}
	}

	private open class FakeProcess(override val output: InputStream, private val exitCode: Int) : StreamProcess {
		val destroyed = CountDownLatch(1)
		override fun waitFor(): Int = exitCode
		override fun destroy() = destroyed.countDown()
	}

	/** Emits nothing and runs until destroyed, like a stream waiting on a slow device. */
	private class OpenEndedProcess(private val writer: PipedOutputStream = PipedOutputStream()) : FakeProcess(PipedInputStream(writer), exitCode = 143) {
		override fun destroy() {
			runCatching { writer.close() }
			super.destroy()
		}
	}
}
