package dev.amoo.studio

import io.github.maniramezan.processrpc.ProcessRpcState
import io.github.maniramezan.testcommons.TemporaryDirectory
import io.github.maniramezan.testcommons.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A [StudioController] wired to scripted collaborators, with helpers to connect and to wait for asynchronous state. */
internal class StudioControllerFixture(
	val rpc: FakeRpc = FakeRpc(),
	val host: FakeHost = FakeHost(),
	val keychain: FakeKeychain = FakeKeychain(),
	val picker: FakeFilePicker = FakeFilePicker(),
	val directory: TemporaryDirectory = TemporaryDirectory(prefix = "amoo-controller"),
	val preferencesPath: String = "dev/amoo/studio-test/${UUID.randomUUID()}",
) : AutoCloseable {
	// The controller assumes one UI thread owns the state, as Dispatchers.Main does in the app.
	private val uiDispatcher = Dispatchers.Default.limitedParallelism(1)
	private val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
	val preferences: Preferences = Preferences.userRoot().node(preferencesPath)
	val chatHistoryFile = File(directory.file, "chat-history.json")
	val controller = StudioController(
		rpc = rpc,
		host = host,
		preferences = preferences,
		keychain = keychain,
		chatHistoryFile = chatHistoryFile,
		filePicker = picker,
		scope = scope,
		mainDispatcher = Dispatchers.Unconfined,
		keychainDispatcher = Dispatchers.Unconfined,
		mirrorLauncher = StreamLauncher { error("device streaming is not available in tests") },
	)
	val state: StudioState get() = controller.state.value

	fun send(vararg events: StudioEvent) = runBlocking(uiDispatcher) { events.forEach(controller::onEvent) }

	suspend fun awaitState(timeout: Duration = 5.seconds, description: String = "state", predicate: (StudioState) -> Boolean): StudioState =
		waitUntil(timeout, observe = { controller.state.value }, matching = predicate)

	/** Answers the handshake and brings the process up, then waits for Studio to enable workflows. */
	suspend fun connect(vararg capabilities: String, version: String = "0.9.0", protocol: Int = STUDIO_PROTOCOL_VERSION) {
		val list = capabilities.joinToString(",") { "\"$it\"" }
		rpc.reply("system.handshake", ok("""{"protocolVersion":$protocol,"product":"amoo","version":"$version","capabilities":[$list]}"""))
		rpc.state.value = ProcessRpcState.Ready(1)
		awaitState { it.connection !is ConnectionState.Starting }
	}

	fun selectRunningDevice(id: String = "sim-1") {
		send(
			StudioEvent.DevicesLoaded(listOf(StudioDevice(id, "iPhone 17", TestPlatform.Ios, "26", DeviceStatus.Running))),
			StudioEvent.SelectDevice(id),
		)
	}

	override fun close() {
		controller.close()
		scope.cancel()
		preferences.removeNode()
		directory.remove()
	}
}
