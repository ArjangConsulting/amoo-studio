package dev.amoo.studio

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import dev.amoo.composeapp.generated.resources.Res
import dev.amoo.composeapp.generated.resources.icon_512
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.maniramezan.processrpc.BundledBinaryLocator
import io.github.maniramezan.processrpc.ProcessRpcClient
import io.github.maniramezan.processrpc.ProcessRpcState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.JFileChooser

@Serializable
private data class Handshake(
	val protocolVersion: Int,
	val product: String,
	val version: String,
	val capabilities: List<String>,
)

@Serializable private data class DeviceListResult(val devices: List<StudioDevice>)
@Serializable private data class OperationResult(val message: String, val artifactPath: String? = null)
@Serializable private data class ChatRequest(val provider: ProviderProfile, val messages: List<ChatMessage>, val activeTest: AmooTest)
@Serializable private data class ChatResult(val message: String)
@Serializable private data class ReplRequest(val command: String, val activeTest: AmooTest, val selectedDeviceId: String?, val selectedProviderId: String?)
@Serializable private data class ReplResult(val output: String)
@Serializable private data class TestRunRequest(val test: AmooTest, val deviceId: String, val providerId: String?)
@Serializable private data class TestRunResult(val message: String, val sessionId: String? = null, val reportId: String? = null)
@Serializable private data class ReportListResult(val reports: List<TestReport>)
@Serializable private data class McpStatusResult(val available: Boolean, val transport: String, val arguments: List<String>)

private class StudioController(
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
	private val preferences = Preferences.userNodeForPackage(StudioController::class.java)
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
	private val testFileStore = AmooTestFileStore(json)
	val state = MutableStateFlow(StudioState(hostPlatform = detectHostPlatform(), themeMode = loadThemeMode(), providers = loadProviders()))
	private val client = ProcessRpcClient(command = {
		listOf(BundledBinaryLocator("amoo", "AMOO_BINARY").locate(), "studio", "serve")
	})
	private var chatJob: Job? = null
	private var consoleJob: Job? = null
	private var testRunJob: Job? = null

	init {
		client.start()
		scope.launch {
			client.state.collect { processState ->
				when (processState) {
					is ProcessRpcState.Ready -> handshake()
					is ProcessRpcState.Unavailable -> state.value = state.value.copy(connection = ConnectionState.Unavailable(processState.reason))
					ProcessRpcState.Starting, ProcessRpcState.Stopped -> state.value = state.value.copy(connection = ConnectionState.Starting)
				}
			}
		}
	}

	private suspend fun handshake() {
		try {
			val handshake = json.decodeFromJsonElement<Handshake>(client.call("system.handshake"))
			state.value = state.value.copy(connection = connectionFromHandshake(handshake.version, handshake.protocolVersion, handshake.capabilities))
		} catch (error: Exception) {
			state.value = state.value.copy(connection = ConnectionState.Unavailable(error.message ?: "Handshake failed"))
		}
	}

	fun onEvent(event: StudioEvent) {
		val approvedAction = state.value.pendingApproval?.action
		val createDeviceRequest = state.value.createDevice
		state.value = state.value.reduce(event)
		when (event) {
			is StudioEvent.ChangeThemeMode -> persistThemeMode(event.value)
			StudioEvent.SendChat -> sendChat()
			StudioEvent.CancelChat -> { chatJob?.cancel(); chatJob = null }
			StudioEvent.ExecuteConsoleCommand -> executeConsoleCommand()
			StudioEvent.CancelConsoleCommand -> { consoleJob?.cancel(); consoleJob = null }
			StudioEvent.RunTest -> runTest()
			StudioEvent.CancelTestRun -> { testRunJob?.cancel(); testRunJob = null }
			StudioEvent.RetryConnection -> { client.close(); client.start() }
			StudioEvent.OpenTest -> openTest()
			StudioEvent.SaveTest -> saveTest(forcePicker = state.value.testPath == null)
			StudioEvent.SaveTestAs -> saveTest(forcePicker = true)
			StudioEvent.CopyMcpConfiguration -> copyMcpConfiguration()
			StudioEvent.RefreshMcpStatus -> refreshMcpStatus()
			is StudioEvent.SaveProvider, is StudioEvent.RemoveProvider -> persistProviders()
			StudioEvent.RefreshDevices -> refreshDevices()
			is StudioEvent.SelectSection -> when (event.section) {
				StudioSection.Devices -> if (state.value.devices.isEmpty()) refreshDevices()
				StudioSection.Reports -> if (state.value.reports.isEmpty()) refreshReports()
				StudioSection.Settings -> if (state.value.mcpStatus == McpStatus.Unknown) refreshMcpStatus()
				else -> Unit
			}
			StudioEvent.RefreshReports -> refreshReports()
			is StudioEvent.StartDevice -> runDeviceOperation("Starting device…", "devices.start", buildJsonObject { put("id", event.id) }, refreshAfter = true)
			StudioEvent.ConfirmCreateDevice -> createDeviceRequest?.let(::createDevice)
			StudioEvent.ChooseProjectPath -> chooseProjectPath()
			StudioEvent.BuildInstallAndRun -> buildInstallAndRun()
			StudioEvent.ReinstallAndRun -> reinstallAndRun()
			is StudioEvent.ResolveApproval -> if (event.approved && approvedAction == ApprovedAction.ResetAppData) resetAppData()
			else -> Unit
		}
	}

	private fun refreshDevices() = scope.launch {
		if (!requireCapability("devices.list")) return@launch
		runCatching { json.decodeFromJsonElement<DeviceListResult>(client.call("devices.list")) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.DevicesLoaded(it.devices)) }
			.onFailure { state.value = state.value.copy(deviceOperation = DeviceOperation.Idle, notice = "Device discovery failed: ${it.message}") }
	}

	private fun refreshReports() = scope.launch {
		if (!state.value.connection.supports("reports.list")) {
			state.value = state.value.reduce(StudioEvent.ReportsFailed("The connected Amoo version does not support reports.list"))
			return@launch
		}
		runCatching { json.decodeFromJsonElement<ReportListResult>(client.call("reports.list")) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.ReportsLoaded(it.reports)) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ReportsFailed("Report loading failed: ${it.message}")) }
	}

	private fun chooseProjectPath() {
		val chooser = JFileChooser().apply {
			dialogTitle = "Choose Xcode project, workspace, or Gradle project"
			fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
		}
		if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
			state.value = state.value.reduce(StudioEvent.ChangeProjectPath(chooser.selectedFile.absolutePath))
		}
	}

	private fun createDevice(request: CreateDeviceState) {
		runDeviceOperation("Creating device…", "devices.create", buildJsonObject {
			put("platform", request.platform.name.lowercase())
			put("name", request.name.trim())
			put("runtime", request.runtime.trim())
			put("deviceType", request.deviceType.trim())
		}, refreshAfter = true)
	}

	private fun buildInstallAndRun() {
		if (!requireCapability("apps.buildInstallRun")) return
		val snapshot = state.value
		val device = snapshot.devices.firstOrNull { it.id == snapshot.selectedDeviceId } ?: return
		runDeviceOperation("Building and installing…", "apps.buildInstallRun", buildJsonObject {
			put("deviceId", device.id); put("platform", device.platform.name.lowercase()); put("projectPath", snapshot.projectPath)
			put("appId", snapshot.appId); put("schemeOrModule", snapshot.schemeOrModule)
		})
	}

	private fun reinstallAndRun() {
		if (!requireCapability("apps.reinstallRun")) return
		val snapshot = state.value
		val artifact = snapshot.lastBuildArtifact ?: return
		runDeviceOperation("Reinstalling app…", "apps.reinstallRun", buildJsonObject {
			put("deviceId", snapshot.selectedDeviceId ?: return); put("appId", snapshot.appId); put("artifactPath", artifact)
		})
	}

	private fun resetAppData() {
		if (!requireCapability("apps.resetData")) return
		val snapshot = state.value
		runDeviceOperation("Erasing app data…", "apps.resetData", buildJsonObject {
			put("deviceId", snapshot.selectedDeviceId ?: return); put("appId", snapshot.appId); snapshot.lastBuildArtifact?.let { put("artifactPath", it) }
		})
	}

	private fun runDeviceOperation(message: String, method: String, params: kotlinx.serialization.json.JsonObject, refreshAfter: Boolean = false) {
		if (!requireCapability(method)) return
		state.value = state.value.reduce(StudioEvent.DeviceOperationStarted(message))
		scope.launch {
			runCatching { json.decodeFromJsonElement<OperationResult>(client.call(method, params)) }
				.onSuccess { result -> state.value = state.value.reduce(StudioEvent.DeviceOperationFinished(result.message, result.artifactPath)); if (refreshAfter) refreshDevices() }
				.onFailure { state.value = state.value.copy(deviceOperation = DeviceOperation.Idle, notice = "Operation failed: ${it.message}") }
		}
	}

	private fun openTest() {
		val file = chooseFile(FileDialog.LOAD, "Open Amoo test", "*.amootest") ?: return
		runCatching { testFileStore.load(file) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.TestLoaded(it, file.absolutePath)) }
			.onFailure { state.value = state.value.copy(notice = "Could not open test: ${it.message}") }
	}

	private fun saveTest(forcePicker: Boolean) {
		val existing = state.value.testPath?.let(::File)
		var file = if (forcePicker) chooseFile(FileDialog.SAVE, "Save Amoo test", "${safeFileName(state.value.test.name)}.amootest") else existing
		if (file == null) return
		if (file.extension.lowercase() != "amootest") file = File(file.parentFile, "${file.name}.amootest")
		runCatching { testFileStore.save(state.value.test, file) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.TestSaved(file.absolutePath)) }
			.onFailure { state.value = state.value.copy(notice = "Could not save test: ${it.message}") }
	}

	private fun chooseFile(mode: Int, title: String, initialFile: String): File? {
		val dialog = FileDialog(null as Frame?, title, mode)
		dialog.file = initialFile
		dialog.isVisible = true
		val selected = dialog.file?.let { File(dialog.directory, it) }
		dialog.dispose()
		return selected
	}

	private fun copyMcpConfiguration() {
		val executable = runCatching { BundledBinaryLocator("amoo", "AMOO_BINARY").locate() }.getOrElse {
			state.value = state.value.copy(notice = "Could not locate Amoo: ${it.message}")
			return
		}
		val configuration = """
			{
			  "mcpServers": {
			    "amoo": {
			      "command": ${json.encodeToString(executable)},
			      "args": ["mcp", "serve"]
			    }
			  }
			}
		""".trimIndent()
		Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(configuration), null)
		state.value = state.value.copy(notice = "MCP configuration copied")
	}

	private fun refreshMcpStatus() = scope.launch {
		if (!state.value.connection.supports("mcp.status")) {
			state.value = state.value.reduce(StudioEvent.McpStatusFailed("The connected Amoo version does not support MCP diagnostics"))
			return@launch
		}
		state.value = state.value.reduce(StudioEvent.RefreshMcpStatus)
		runCatching { json.decodeFromJsonElement<McpStatusResult>(client.call("mcp.status")) }
			.onSuccess { result ->
				state.value = if (result.available) state.value.reduce(StudioEvent.McpStatusLoaded(result.transport, result.arguments))
				else state.value.reduce(StudioEvent.McpStatusFailed("Amoo MCP is unavailable"))
			}
			.onFailure { state.value = state.value.reduce(StudioEvent.McpStatusFailed("MCP readiness check failed: ${it.message}")) }
	}

	private fun sendChat() {
		if (!requireCapability("chat.send")) return
		val snapshot = state.value
		val input = snapshot.chat.input.trim()
		val provider = snapshot.providers.firstOrNull { it.id == snapshot.selectedProviderId } ?: run {
			state.value = state.value.copy(notice = "Choose an AI provider before sending a message")
			return
		}
		if (input.isEmpty() || chatJob?.isActive == true) return
		val userMessage = ChatMessage("user-${System.nanoTime()}", ChatRole.User, input)
		state.value = state.value.reduce(StudioEvent.ChatRequestStarted(userMessage))
		val request = ChatRequest(provider, snapshot.chat.messages + userMessage, snapshot.test)
		chatJob = scope.launch {
			runCatching { json.decodeFromJsonElement<ChatResult>(client.call("chat.send", json.encodeToJsonElement(request))) }
				.onSuccess { result -> state.value = state.value.reduce(StudioEvent.ChatResponseReceived(ChatMessage("assistant-${System.nanoTime()}", ChatRole.Assistant, result.message))) }
				.onFailure { error ->
					if (state.value.chat.operation == ChatOperation.Sending) state.value = state.value.reduce(StudioEvent.ChatRequestFailed("AI request failed: ${error.message}"))
				}
			chatJob = null
		}
	}

	private fun executeConsoleCommand() {
		if (!requireCapability("repl.execute")) return
		val snapshot = state.value
		val command = snapshot.console.input.trim()
		if (command.isEmpty() || consoleJob?.isActive == true) return
		if (command.contains(Regex("(^|\\s)(reset|erase|delete|uninstall)(\\s|$)", RegexOption.IGNORE_CASE))) {
			state.value = state.value.copy(notice = "Destructive commands must use the confirmed workflow in Devices")
			return
		}
		state.value = state.value.reduce(StudioEvent.ConsoleCommandStarted)
		val request = ReplRequest(command, snapshot.test, snapshot.selectedDeviceId, snapshot.selectedProviderId)
		consoleJob = scope.launch {
			val entry = runCatching { json.decodeFromJsonElement<ReplResult>(client.call("repl.execute", json.encodeToJsonElement(request))) }
				.fold(
					onSuccess = { ConsoleEntry("command-${System.nanoTime()}", command, it.output) },
					onFailure = { ConsoleEntry("command-${System.nanoTime()}", command, "Command failed: ${it.message}", failed = true) },
				)
			if (state.value.console.operation == ConsoleOperation.Running) state.value = state.value.reduce(StudioEvent.ConsoleCommandFinished(entry))
			consoleJob = null
		}
	}

	private fun runTest() {
		if (!requireCapability("tests.run")) return
		val snapshot = state.value
		val deviceId = snapshot.selectedDeviceId ?: run {
			state.value = state.value.copy(notice = "Choose a device before running the test")
			return
		}
		if (testRunJob?.isActive == true) return
		state.value = state.value.reduce(StudioEvent.TestRunStarted("Running ${snapshot.test.name}…"))
		val request = TestRunRequest(snapshot.test, deviceId, snapshot.selectedProviderId)
		testRunJob = scope.launch {
			runCatching { json.decodeFromJsonElement<TestRunResult>(client.call("tests.run", json.encodeToJsonElement(request))) }
				.onSuccess { result -> state.value = state.value.reduce(StudioEvent.TestRunFinished(result.message, result.sessionId, result.reportId)) }
				.onFailure { error -> if (state.value.testExecution is TestExecution.Running) state.value = state.value.reduce(StudioEvent.TestRunFailed("Test run failed: ${error.message}")) }
			testRunJob = null
		}
	}

	private fun loadProviders(): List<ProviderProfile> = runCatching {
		preferences.get("providers", null)?.let { json.decodeFromString<List<ProviderProfile>>(it) }
	}.getOrNull() ?: defaultProviders()

	private fun loadThemeMode(): ThemeMode = preferences.get("themeMode", null)
		?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
		?: ThemeMode.System

	private fun persistThemeMode(themeMode: ThemeMode) {
		runCatching { preferences.put("themeMode", themeMode.name) }
			.onFailure { state.value = state.value.copy(notice = "Could not save appearance: ${it.message}") }
	}

	private fun persistProviders() {
		runCatching { preferences.put("providers", json.encodeToString(state.value.providers)) }
			.onFailure { state.value = state.value.copy(notice = "Could not save providers: ${it.message}") }
	}

	private fun requireCapability(capability: String): Boolean {
		if (state.value.connection.supports(capability)) return true
		state.value = state.value.copy(
			deviceOperation = DeviceOperation.Idle,
			notice = "The connected Amoo version does not support $capability",
		)
		return false
	}

	private fun safeFileName(value: String) = value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "untitled-test" }

	override fun close() { chatJob?.cancel(); consoleJob?.cancel(); testRunJob?.cancel(); client.close() }
}

private fun detectHostPlatform(): HostPlatform {
	val osName = System.getProperty("os.name").orEmpty().lowercase()
	return when {
		"mac" in osName || "darwin" in osName -> HostPlatform.MacOS
		"linux" in osName -> HostPlatform.Linux
		else -> HostPlatform.Unsupported
	}
}

private fun installDockIcon() {
	val icon: Image = runCatching {
		Thread.currentThread().contextClassLoader
			.getResourceAsStream("icons/icon.png")
			?.use(ImageIO::read)
	}.getOrNull() ?: return
	runCatching {
		if (Taskbar.isTaskbarSupported()) {
			val taskbar = Taskbar.getTaskbar()
			if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
				taskbar.iconImage = icon
			}
		}
	}
}

fun main() = application {
	val controller = remember { StudioController() }
	DisposableEffect(controller) {
		onDispose(controller::close)
	}
	val state by controller.state.collectAsState()
	Window(
		onCloseRequest = {
			exitApplication()
		},
		title = "Amoo Studio",
		icon = painterResource(Res.drawable.icon_512),
		state = rememberWindowState(width = 960.dp, height = 680.dp),
	) {
		LaunchedEffect(Unit) {
			installDockIcon()
		}
		AmooStudioApp(state, controller::onEvent)
	}
}
