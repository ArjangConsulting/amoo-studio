package dev.amoo.studio

import androidx.compose.ui.window.application
import com.maniramezan.credentialkeychain.CredentialKeychain
import io.github.maniramezan.processrpc.ProcessRpcState
import io.github.maniramezan.processrpc.RpcNotification
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
private data class Handshake(
	val protocolVersion: Int,
	val product: String,
	val version: String,
	val capabilities: List<String>,
)

@Serializable private data class DeviceListResult(val devices: List<StudioDevice>)
@Serializable private data class OperationResult(val message: String, val artifactPath: String? = null)
/** Wire form of [ProviderProfile]; [apiKey] is only sent to Amoo builds that advertise [PROVIDER_API_KEY_CAPABILITY]. */
@Serializable private data class ProviderRequest(
	val id: String,
	val name: String,
	val kind: ProviderKind,
	val baseUrl: String,
	val model: String,
	val apiKeyEnvironmentVariable: String,
	val apiKey: Secret? = null,
)
private fun ProviderProfile.toRequest(connection: ConnectionState) = ProviderRequest(
	id, name, kind, baseUrl, model, apiKeyEnvironmentVariable,
	apiKey.takeIf { !it.isBlank && connection.supports(PROVIDER_API_KEY_CAPABILITY) },
)
@Serializable private data class ChatRequest(val provider: ProviderRequest, val messages: List<ChatMessage>, val activeTest: AmooTest)
@Serializable private data class AgentStartRequest(
	val provider: ProviderRequest,
	val messages: List<ChatMessage>,
	val activeTest: AmooTest,
	val deviceId: String,
	val platform: String,
	val appId: String,
	val buildPath: String? = null,
)
@Serializable private data class AgentStartResult(val runId: String)
@Serializable private data class GenerateRequest(
	val sessionId: String? = null,
	val test: AmooTest? = null,
	val uiToolkit: UiToolkit? = null,
	val testName: String? = null,
	val allowIncomplete: Boolean = false,
	val projectPath: String? = null,
)
@Serializable private data class GenerateResult(val generated: GeneratedSource? = null, val problem: GenerationProblem? = null, val test: AmooTest)
@Serializable private data class AgentEventsResult(val state: AgentRunStatus, val events: List<AgentEvent>, val nextSeq: Int)
@Serializable private data class ChatResult(val message: String, val proposedPlan: CompiledToolPlan? = null)
@Serializable private data class ProviderCheckResult(val message: String)
@Serializable private data class ReplRequest(val command: String, val activeTest: AmooTest, val selectedDeviceId: String?, val selectedProviderId: String?, val platform: String? = null, val captureScreenshot: Boolean = true)
@Serializable private data class ReplResult(
	val output: String,
	val isError: Boolean? = null,
	val tool: String? = null,
	val structuredContent: String? = null,
	val artifacts: List<String>? = null,
	val screenshot: ScreenshotRef? = null,
	val gesturePoint: GesturePoint? = null,
	val actionIndex: Int? = null,
	val sessionId: String? = null,
)
@Serializable private data class ReplRecordingStartRequest(val deviceId: String, val platform: String, val appId: String, val buildPath: String? = null)
@Serializable private data class ReplRecordingStopRequest(val deviceId: String)
@Serializable private data class ReplRecording(val sessionId: String, val actionCount: Int, val planPath: String? = null, val message: String? = null)
@Serializable private data class TestRunRequest(val test: AmooTest, val deviceId: String, val providerId: String?)
@Serializable private data class TestRunResult(val message: String, val sessionId: String? = null, val reportId: String? = null)
@Serializable private data class TestStartResult(val runId: String)
@Serializable private data class ReportListResult(val reports: List<TestReport>)
@Serializable private data class TestExportRequest(val test: AmooTest)
@Serializable private data class TestExportResult(val fileName: String, val source: String)
@Serializable private data class McpStatusResult(val available: Boolean, val transport: String, val arguments: List<String>)

/**
 * Owns [StudioState] and performs everything the reducer cannot: RPC calls, files, preferences and the
 * keychain. Its collaborators are parameters so tests can script Amoo and run without a desktop.
 */
internal class StudioController(
	private val rpc: StudioRpc,
	private val host: StudioHost,
	private val preferences: Preferences,
	keychain: CredentialKeychain,
	chatHistoryFile: File,
	private val filePicker: FilePicker,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
	private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
	// Keychain access can block or prompt; a single worker also keeps writes and deletes in order.
	private val keychainDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
	mirrorLauncher: StreamLauncher = AmooStreamLauncher(host::locateAmoo),
) : AutoCloseable {
	private val log = StudioLog.forFeature("Studio")
	private val studioPreferences = StudioPreferences(preferences)
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
	private val testFileStore = AmooTestFileStore(json)
	private val providerStore = ProviderStore(preferences, keychain, json)
	private val chatHistory = ChatHistoryStore(chatHistoryFile, json, preferences)
	val initialWindowSize: WindowSize? = studioPreferences.windowSize()
	val initialWindowOrigin: WindowOrigin? = studioPreferences.windowOrigin()
	val state = MutableStateFlow(initialState())
	val screenshotLoader: ScreenshotLoader = DesktopScreenshotLoader()

	/**
	 * Amoo's console completion and validation. Returns null when Amoo cannot answer, so the editor's
	 * service falls back to the local grammar; cancellation propagates because the editor cancels stale requests.
	 */
	val replAssist: ReplAssist = object : ReplAssist {
		override suspend fun complete(line: String, cursor: Int): ReplCompletion? = askAmoo(REPL_COMPLETE_CAPABILITY, "repl.complete") {
			put("line", line)
			put("cursor", cursor)
			selectedDevice()?.let { device -> put("selectedDeviceId", device.id); put("platform", device.platform.name.lowercase()) }
		}

		override suspend fun validate(line: String): List<ReplDiagnostic>? =
			askAmoo<ValidationResult>(REPL_VALIDATE_CAPABILITY, "repl.validate") { put("line", line) }?.diagnostics
	}

	private fun initialState(): StudioState {
		val providers = providerStore.loadProfiles()
		val session = runCatching(studioPreferences::loadSession).getOrDefault(PersistedSession())
		return StudioState(
			hostPlatform = host.platform,
			themeMode = runCatching(studioPreferences::themeMode).getOrDefault(ThemeMode.System),
			providers = providers,
			chat = ChatState(messages = runCatching(chatHistory::load).getOrDefault(emptyList())),
			projectPath = session.projectPath,
			appId = session.appId,
			schemeOrModule = session.schemeOrModule,
			selectedProviderId = session.selectedProviderId?.takeIf { id -> providers.any { it.id == id } },
			selectedDeviceId = session.selectedDeviceId,
			workbenchSplit = session.workbenchSplit,
		)
	}

	@Serializable private data class ValidationResult(val diagnostics: List<ReplDiagnostic>)

	/** Every RPC goes through here so each call and failure is logged with its method and identifiers, never its payload. */
	private suspend fun call(method: String, params: JsonElement = JsonObject(emptyMap()), timeoutMs: Long? = null, context: String = ""): JsonElement {
		val quiet = method in QUIET_METHODS
		val started = System.nanoTime()
		if (!quiet) log.info("rpc.call", "method=$method $context")
		return try {
			rpc.call(method, params, timeoutMs).also {
				val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
				if (quiet) log.debug("rpc.ok", "method=$method ms=$elapsed $context") else log.info("rpc.ok", "method=$method ms=$elapsed $context")
			}
		} catch (cancelled: CancellationException) {
			log.debug("rpc.cancelled", "method=$method $context")
			throw cancelled
		} catch (failure: Exception) {
			log.error("rpc.failed", failure, "method=$method ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} $context")
			throw failure
		}
	}

	private fun selectedDevice(): StudioDevice? = state.value.let { snapshot -> snapshot.devices.firstOrNull { it.id == snapshot.selectedDeviceId } }

	private suspend inline fun <reified T> askAmoo(capability: String, method: String, crossinline params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): T? {
		if (!state.value.connection.supports(capability)) return null
		return try {
			json.decodeFromJsonElement<T>(call(method, buildJsonObject { params() }))
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			null
		}
	}
	private val mirror = DeviceMirror(scope, mirrorLauncher, onEvent = { event ->
		withContext(mainDispatcher) { state.value = state.value.reduce(event) }
	})
	private var chatJob: Job? = null
	private var consoleJob: Job? = null
	private var testRunJob: Job? = null
	// Latest pushed status per run. A run can report progress before tests.start has returned its ID,
	// so statuses are kept by run ID rather than matched against the active run on arrival.
	private val pushedRunStatuses = MutableStateFlow<Map<String, TestRunStatus>>(emptyMap())

	init {
		loadApiKeys()
		// Subscribe before starting the process: notifications are not replayed to late collectors.
		scope.launch(start = CoroutineStart.UNDISPATCHED) { rpc.notifications.collect(::onNotification) }
		rpc.start()
		scope.launch { state.map { it.desiredMirrorDevice() }.distinctUntilChanged().collect(mirror::update) }
		persistSession()
		scope.launch {
			rpc.state.collect { processState ->
				log.info("process.state", processState::class.simpleName.orEmpty())
				when (processState) {
					is ProcessRpcState.Ready -> handshake()
					is ProcessRpcState.Unavailable -> state.value = state.value.reduce(StudioEvent.ConsoleRecordingInterrupted).reduce(StudioEvent.ConnectionChanged(
						ConnectionState.Unavailable(processState.reason, canInstallAmoo = host.isAmooMissing()),
					))
					ProcessRpcState.Starting, ProcessRpcState.Stopped -> state.value = state.value.reduce(StudioEvent.ConsoleRecordingInterrupted).reduce(StudioEvent.ConnectionChanged(ConnectionState.Starting))
				}
			}
		}
	}

	private fun onNotification(notification: RpcNotification) {
		val decoded = runCatching { json.decodeAmooNotification(notification) }.getOrElse { error ->
			log.error("notification.malformed", error, "method=${notification.method}")
			return
		}
		when (decoded) {
			is AmooNotification.TestProgress -> pushedRunStatuses.update { it + (decoded.status.runId to decoded.status) }
			is AmooNotification.AgentEventNotice -> onAgentEvent(decoded.event)
			null -> Unit
		}
	}

	private suspend fun handshake() {
		try {
			val handshake = json.decodeFromJsonElement<Handshake>(call("system.handshake"))
			log.info("handshake.ok", "amoo=${handshake.version} protocol=${handshake.protocolVersion} capabilities=${handshake.capabilities.size}")
			state.value = state.value.reduce(StudioEvent.ConnectionChanged(connectionFromHandshake(handshake.version, handshake.protocolVersion, handshake.capabilities)))
			loadToolCatalog()
		} catch (error: Exception) {
			log.error("handshake.failed", error)
			state.value = state.value.reduce(StudioEvent.ConnectionChanged(ConnectionState.Unavailable(error.message ?: "Handshake failed")))
		}
	}

	/** Replaces the legacy console vocabulary with Amoo's catalog; older builds keep [ToolCatalog.Legacy]. */
	private suspend fun loadToolCatalog() {
		if (!state.value.connection.supports(TOOLS_CATALOG_CAPABILITY)) return
		runCatching { json.decodeFromJsonElement<ToolCatalog>(call("tools.catalog")) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.ToolCatalogLoaded(it)) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not load Amoo's tool catalog; the console uses its basic commands: ${it.message}", NoticeTone.Error)) }
	}

	fun onEvent(event: StudioEvent) {
		val approvedAction = state.value.pendingApproval?.action
		val createDeviceRequest = state.value.createDevice
		val activeRunId = (state.value.testExecution as? TestExecution.Running)?.runId
		val testDirty = state.value.isTestDirty
		val providerToSave = when (event) {
			is StudioEvent.SaveProvider -> event.profile
			is StudioEvent.RetryProviderApiKeySave -> state.value.providers.firstOrNull { it.id == event.id }
			else -> null
		}
		val providerSaveAllowed = providerToSave?.let { state.value.canSaveProvider(it) } ?: false
		state.value = state.value.reduce(event)
		when (event) {
			is StudioEvent.ChangeThemeMode -> persistThemeMode(event.value)
			StudioEvent.SendChat -> sendChat()
			StudioEvent.RetryLastChat -> sendChat()
			StudioEvent.CancelChat -> { chatJob?.cancel(); chatJob = null }
			StudioEvent.ClearChat -> persistChatMessages()
			StudioEvent.ExecuteConsoleCommand -> executeConsoleCommand()
			StudioEvent.CancelConsoleCommand -> { consoleJob?.cancel(); consoleJob = null }
			StudioEvent.StartConsoleRecording -> startConsoleRecording()
			StudioEvent.StopConsoleRecording -> stopConsoleRecording()
			StudioEvent.RunTest -> runTest()
			StudioEvent.CancelTestRun -> cancelTestRun(activeRunId)
			StudioEvent.ExportTest -> exportTest()
			StudioEvent.RetryConnection -> { rpc.close(); rpc.start() }
			StudioEvent.OpenTest -> if (!testDirty) openTest()
			StudioEvent.RevealLogs -> revealLogs()
			StudioEvent.SaveTest -> saveTest(forcePicker = state.value.testPath == null)
			StudioEvent.SaveTestAs -> saveTest(forcePicker = true)
			StudioEvent.CopyMcpConfiguration -> copyMcpConfiguration()
			StudioEvent.CopyInstallCommands -> copyInstallCommands()
			StudioEvent.RefreshMcpStatus -> refreshMcpStatus()
			is StudioEvent.SaveProvider -> if (providerSaveAllowed) saveProvider(event.profile)
			StudioEvent.RetryProviderApiKeys -> loadApiKeys()
			is StudioEvent.RetryProviderApiKeySave -> if (providerSaveAllowed) saveProvider(providerToSave)
			is StudioEvent.RetryProviderApiKeyDelete -> if (state.value.providers.none { it.id == event.id }) deleteProviderApiKey(event.id)
			is StudioEvent.RemoveProvider -> removeProvider(event.id)
			is StudioEvent.CheckProvider -> checkProvider(event.id)
			StudioEvent.RefreshDevices -> refreshDevices()
			is StudioEvent.SelectSection -> when (event.section) {
				StudioSection.Devices -> if (state.value.devices.isEmpty()) refreshDevices()
				StudioSection.Reports -> if (state.value.reports.isEmpty()) refreshReports()
				StudioSection.Settings -> if (state.value.mcpStatus == McpStatus.Unknown) refreshMcpStatus()
				else -> Unit
			}
			StudioEvent.RefreshReports -> refreshReports()
			is StudioEvent.OpenReportArtifact -> openArtifact(event.path)
			is StudioEvent.StartDevice -> runDeviceOperation("Starting device…", "devices.start", buildJsonObject { put("id", event.id) }, refreshAfter = true)
			StudioEvent.ConfirmCreateDevice -> createDeviceRequest?.let(::createDevice)
			StudioEvent.ChooseProjectPath -> chooseProjectPath()
			StudioEvent.BuildInstallAndRun -> buildInstallAndRun()
			StudioEvent.ReinstallAndRun -> reinstallAndRun()
			is StudioEvent.ResolveApproval -> when (approvedAction) {
				ApprovedAction.ResetAppData -> if (event.approved) resetAppData()
				ApprovedAction.InstallAmoo -> if (event.approved) installAmoo()
				is ApprovedAction.DiscardTest -> if (event.approved && approvedAction.then == DiscardContinuation.OpenTest) openTest()
				// A decline is an answer too: the agent is told and continues another way.
				is ApprovedAction.AgentTool -> answerAgentApproval(approvedAction, event.approved)
				null -> Unit
			}
			StudioEvent.CancelAgentRun -> cancelAgentRun()
			is StudioEvent.GenerateTest, is StudioEvent.RegenerateTest, StudioEvent.GenerateConsoleTest -> generateTest()
			StudioEvent.SaveGeneratedTest -> saveGeneratedTest()
			StudioEvent.SteerAgentRun -> steerAgentRun()
			else -> Unit
		}
	}

	private fun refreshDevices() = scope.launch {
		if (!requireCapability("devices.list")) return@launch
		runCatching { json.decodeFromJsonElement<DeviceListResult>(call("devices.list")) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.DevicesLoaded(it.devices)) }
			.onFailure { state.value = state.value.copy(deviceOperation = DeviceOperation.Idle, notice = Notice.error("Device discovery failed: ${it.message}")) }
	}

	private fun refreshReports() = scope.launch {
		if (!state.value.connection.supports("reports.list")) {
			state.value = state.value.reduce(StudioEvent.ReportsFailed("The connected Amoo version does not support reports.list"))
			return@launch
		}
		runCatching { json.decodeFromJsonElement<ReportListResult>(call("reports.list")) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.ReportsLoaded(it.reports)) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ReportsFailed("Report loading failed: ${it.message}")) }
	}

	private fun openArtifact(path: String) {
		runCatching {
			val file = File(path)
			require(file.isFile) { "Artifact no longer exists: $path" }
			require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { "Opening files is unsupported on this desktop" }
			Desktop.getDesktop().open(file)
		}.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice(it.message ?: "Could not open artifact", NoticeTone.Error)) }
	}

	private fun chooseProjectPath() {
		filePicker.chooseProject()?.let { state.value = state.value.reduce(StudioEvent.ChangeProjectPath(it.absolutePath)) }
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
			runCatching { json.decodeFromJsonElement<OperationResult>(call(method, params, context = (params["deviceId"] ?: params["id"])?.let { "deviceId=${it.jsonPrimitive.content}" }.orEmpty())) }
				.onSuccess { result -> state.value = state.value.reduce(StudioEvent.DeviceOperationFinished(result.message, result.artifactPath)); if (refreshAfter) refreshDevices() }
				.onFailure { state.value = state.value.reduce(StudioEvent.DeviceOperationFailed("Operation failed: ${it.message}")) }
		}
	}

	private fun openTest() {
		val file = filePicker.chooseFileToOpen("Open Amoo test", "*.amootest") ?: return
		runCatching { testFileStore.load(file) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.TestLoaded(it, file.absolutePath)) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not open test: ${it.message}", NoticeTone.Error)) }
	}

	private fun saveTest(forcePicker: Boolean) {
		val existing = state.value.testPath?.let(::File)
		var file = if (forcePicker) filePicker.chooseFileToSave("Save Amoo test", "${safeFileName(state.value.test.name)}.amootest") else existing
		if (file == null) return
		if (file.extension.lowercase() != "amootest") file = File(file.parentFile, "${file.name}.amootest")
		runCatching { testFileStore.save(state.value.test, file) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.TestSaved(file.absolutePath)) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not save test: ${it.message}", NoticeTone.Error)) }
	}

	private fun copyMcpConfiguration() {
		val executable = runCatching { host.locateAmoo() }.getOrElse {
			state.value = state.value.reduce(StudioEvent.ShowNotice("Could not locate Amoo: ${it.message}", NoticeTone.Error))
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
		state.value = state.value.reduce(StudioEvent.ShowNotice("MCP configuration copied", NoticeTone.Success))
	}

	private fun copyInstallCommands() {
		val commands = "$AMOO_HOMEBREW_TAP_COMMAND\n$AMOO_HOMEBREW_INSTALL_COMMAND"
		Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(commands), null)
		state.value = state.value.reduce(StudioEvent.ShowNotice("Install commands copied", NoticeTone.Success))
	}

	private fun installAmoo() {
		if (state.value.amooInstall == AmooInstallState.Running) return
		state.value = state.value.reduce(StudioEvent.InstallAmooStarted)
		scope.launch(Dispatchers.IO) {
			val result = runCatching {
				val brew = host.locateHomebrew()
				runShellCommand(listOf(brew, "tap", AMOO_HOMEBREW_TAP))
				runShellCommand(listOf(brew, "install", AMOO_HOMEBREW_FORMULA))
			}
			withContext(mainDispatcher) {
				result.onSuccess {
					state.value = state.value.reduce(StudioEvent.InstallAmooFinished("Amoo installed via Homebrew. Reconnecting…"))
					rpc.close()
					rpc.start()
				}.onFailure { error ->
					state.value = state.value.reduce(StudioEvent.InstallAmooFailed("Homebrew install failed: ${error.message}"))
				}
			}
		}
	}

	private fun runShellCommand(command: List<String>, timeoutMinutes: Long = 20) {
		val process = ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
		// Drain output on its own thread so a chatty or stalled process can't block the timeout below.
		val output = StringBuilder()
		val reader = thread(isDaemon = true) { process.inputStream.bufferedReader().use { output.append(it.readText()) } }
		if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
			process.destroyForcibly()
			throw IllegalStateException("${command.joinToString(" ")} timed out after $timeoutMinutes minutes")
		}
		reader.join(5_000)
		val exitCode = process.exitValue()
		if (exitCode != 0) throw IllegalStateException("${command.joinToString(" ")} failed (exit $exitCode): ${output.trim().takeLast(500)}")
	}

	private fun refreshMcpStatus() = scope.launch {
		if (!state.value.connection.supports("mcp.status")) {
			state.value = state.value.reduce(StudioEvent.McpStatusFailed("The connected Amoo version does not support MCP diagnostics"))
			return@launch
		}
		state.value = state.value.reduce(StudioEvent.RefreshMcpStatus)
		runCatching { json.decodeFromJsonElement<McpStatusResult>(call("mcp.status")) }
			.onSuccess { result ->
				state.value = if (result.available) state.value.reduce(StudioEvent.McpStatusLoaded(result.transport, result.arguments))
				else state.value.reduce(StudioEvent.McpStatusFailed("Amoo MCP is unavailable"))
			}
			.onFailure { state.value = state.value.reduce(StudioEvent.McpStatusFailed("MCP readiness check failed: ${it.message}")) }
	}

	private fun sendChat() {
		val readiness = state.value.agentReadiness()
		if (readiness is SessionReadiness.NoApp) {
			state.value = state.value.reduce(StudioEvent.ShowNotice("Set the app's bundle or application ID in Devices so the agent can launch ${readiness.device.name}'s app", NoticeTone.Error))
			return
		}
		if (readiness is SessionReadiness.Ready) {
			startAgentRun(readiness)
			return
		}
		if (!requireCapability("chat.send")) return
		val snapshot = state.value
		val input = snapshot.chat.input.trim()
		val provider = snapshot.providers.firstOrNull { it.id == snapshot.selectedProviderId } ?: run {
			state.value = state.value.reduce(StudioEvent.ShowNotice("Choose an AI provider before sending a message", NoticeTone.Error))
			return
		}
		if (input.isEmpty() || chatJob?.isActive == true) return
		snapshot.connection.providerProblem(provider)?.let { problem ->
			state.value = state.value.reduce(StudioEvent.ShowNotice(problem, NoticeTone.Error))
			return
		}
		val userMessage = ChatMessage("user-${System.nanoTime()}", ChatRole.User, input)
		state.value = state.value.reduce(StudioEvent.ChatRequestStarted(userMessage))
		persistChatMessages()
		val request = ChatRequest(provider.toRequest(snapshot.connection), snapshot.chat.messages + userMessage, snapshot.test)
		chatJob = scope.launch {
			runCatching { json.decodeFromJsonElement<ChatResult>(call("chat.send", json.encodeToJsonElement(request))) }
				.onSuccess { result -> state.value = state.value.reduce(StudioEvent.ChatResponseReceived(ChatMessage("assistant-${System.nanoTime()}", ChatRole.Assistant, result.message), result.proposedPlan)); persistChatMessages() }
				.onFailure { error ->
					if (state.value.chat.operation == ChatOperation.Sending) state.value = state.value.reduce(StudioEvent.ChatRequestFailed("AI request failed: ${error.message}"))
				}
			chatJob = null
		}
	}

	// MARK: Agent runs

	/** Events that arrived before `agent.start` returned their run id. */
	private val earlyAgentEvents = mutableMapOf<String, MutableList<AgentEvent>>()
	private var agentWatchJob: Job? = null
	private var lastAgentEventNanos = 0L

	private fun startAgentRun(readiness: SessionReadiness.Ready) {
		val snapshot = state.value
		val input = snapshot.chat.input.trim()
		if (input.isEmpty() || snapshot.agentRunActive) return
		val provider = snapshot.providers.firstOrNull { it.id == snapshot.selectedProviderId } ?: run {
			state.value = state.value.reduce(StudioEvent.ShowNotice("Choose an AI provider before starting an agent run", NoticeTone.Error))
			return
		}
		snapshot.connection.providerProblem(provider)?.let { problem ->
			state.value = state.value.reduce(StudioEvent.ShowNotice(problem, NoticeTone.Error))
			return
		}
		val userMessage = ChatMessage("user-${System.nanoTime()}", ChatRole.User, input)
		val request = AgentStartRequest(
			provider = provider.toRequest(snapshot.connection),
			messages = snapshot.chat.messages + userMessage,
			activeTest = snapshot.test,
			deviceId = readiness.device.id,
			platform = readiness.device.platform.name.lowercase(),
			appId = readiness.appId,
			buildPath = snapshot.lastBuildArtifact,
		)
		chatJob = scope.launch {
			runCatching { json.decodeFromJsonElement<AgentStartResult>(call("agent.start", json.encodeToJsonElement(request))) }
				.onSuccess { started ->
					state.value = state.value.reduce(StudioEvent.AgentRunStarted(started.runId, userMessage))
					persistChatMessages()
					earlyAgentEvents.remove(started.runId)?.sortedBy { it.seq }?.forEach(::onAgentEvent)
					earlyAgentEvents.clear()
					watchAgentRun(started.runId)
				}
				.onFailure { state.value = state.value.reduce(StudioEvent.AgentRunFailed("Agent run failed to start: ${it.message}")) }
			chatJob = null
		}
	}

	private fun onAgentEvent(event: AgentEvent) {
		val run = state.value.agentRun
		if (run?.runId != event.runId) {
			if (event.kind != AgentEventKind.AssistantDelta) earlyAgentEvents.getOrPut(event.runId) { mutableListOf() }.takeIf { it.size < 500 }?.add(event)
			return
		}
		lastAgentEventNanos = System.nanoTime()
		val wasTerminal = run.status.isTerminal
		state.value = state.value.reduce(StudioEvent.AgentEventReceived(event))
		val updated = state.value.agentRun ?: return
		if (updated.gap) scope.launch { replayAgentEvents(updated.runId) }
		if (!wasTerminal && updated.status.isTerminal) persistChatMessages()
	}

	/** Replays missed events after a gap, and every few seconds of silence in case a notification was dropped. */
	private fun watchAgentRun(runId: String) {
		agentWatchJob?.cancel()
		lastAgentEventNanos = System.nanoTime()
		agentWatchJob = scope.launch {
			while (state.value.agentRun?.let { it.runId == runId && !it.status.isTerminal } == true) {
				delay(AGENT_HEARTBEAT_MILLIS)
				if (System.nanoTime() - lastAgentEventNanos >= AGENT_HEARTBEAT_MILLIS * 1_000_000) replayAgentEvents(runId)
			}
		}
	}

	private suspend fun replayAgentEvents(runId: String) {
		val after = (state.value.agentRun?.takeIf { it.runId == runId }?.nextSeq ?: return) - 1
		runCatching { json.decodeFromJsonElement<AgentEventsResult>(call("agent.events", buildJsonObject { put("runId", runId); put("after", after) }, context = "runId=$runId")) }
			.onSuccess { result ->
				lastAgentEventNanos = System.nanoTime()
				val wasTerminal = state.value.agentRun?.status?.isTerminal == true
				state.value = state.value.reduce(StudioEvent.AgentEventsReplayed(runId, result.events, result.state, result.nextSeq))
				if (!wasTerminal && state.value.agentRun?.status?.isTerminal == true) persistChatMessages()
			}
	}

	private fun answerAgentApproval(action: ApprovedAction.AgentTool, approved: Boolean) = scope.launch {
		runCatching { call("agent.approve", buildJsonObject { put("runId", action.runId); put("callId", action.callId); put("approved", approved) }, context = "runId=${action.runId}") }
			.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not answer the approval: ${it.message}", NoticeTone.Error)) }
	}

	private fun cancelAgentRun() {
		val runId = state.value.agentRun?.takeIf { !it.status.isTerminal }?.runId ?: return
		scope.launch {
			runCatching { call("agent.cancel", buildJsonObject { put("runId", runId) }, context = "runId=$runId") }
				.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not stop the agent: ${it.message}", NoticeTone.Error)) }
		}
	}

	private fun steerAgentRun() {
		val snapshot = state.value
		val runId = snapshot.agentRun?.takeIf { !it.status.isTerminal }?.runId ?: return
		val text = snapshot.chat.input.trim().takeIf(String::isNotEmpty) ?: return
		val message = ChatMessage("user-${System.nanoTime()}", ChatRole.User, text)
		scope.launch {
			runCatching { call("agent.continue", buildJsonObject { put("runId", runId); put("message", text) }, context = "runId=$runId") }
				.onSuccess { state.value = state.value.reduce(StudioEvent.AgentSteered(message)); persistChatMessages() }
				.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not send guidance: ${it.message}", NoticeTone.Error)) }
		}
	}

	// MARK: Test generation

	private var generateJob: Job? = null

	private fun generateTest() {
		val snapshot = state.value
		val generated = snapshot.generatedTest ?: return
		if (!snapshot.connection.supports(TESTS_GENERATE_CAPABILITY)) {
			state.value = state.value.reduce(StudioEvent.TestGenerationFailed("The connected Amoo version cannot generate tests. Update Amoo."))
			return
		}
		val request = GenerateRequest(
			sessionId = (generated.source as? GenerationSource.Session)?.sessionId,
			test = snapshot.test.takeIf { generated.source == GenerationSource.AuthoredTest },
			uiToolkit = generated.uiToolkit,
			testName = generated.testName.trim().ifEmpty { null },
			allowIncomplete = generated.allowIncomplete,
			projectPath = snapshot.projectPath.ifBlank { null },
		)
		generateJob?.cancel()
		generateJob = scope.launch {
			runCatching { json.decodeFromJsonElement<GenerateResult>(call("tests.generate", json.encodeToJsonElement(request))) }
				.onSuccess { state.value = state.value.reduce(StudioEvent.TestGenerated(it.generated, it.problem, it.test)) }
				.onFailure { state.value = state.value.reduce(StudioEvent.TestGenerationFailed("Test generation failed: ${it.message}")) }
		}
	}

	private fun saveGeneratedTest() {
		val generated = state.value.generatedTest?.generated ?: return
		val file = filePicker.chooseFileToSave("Save generated test", generated.fileName) ?: return
		runCatching { writeAtomically(file, generated.source) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.ShowNotice("Saved ${file.name}", NoticeTone.Success)) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not write file: ${it.message}", NoticeTone.Error)) }
	}

	private fun copyToClipboard(text: String, notice: String) {
		Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
		state.value = state.value.reduce(StudioEvent.ShowNotice(notice, NoticeTone.Success))
	}

	private fun checkProvider(id: String) {
		if (!requireCapability("providers.check")) return
		val snapshot = state.value
		val provider = snapshot.providers.firstOrNull { it.id == id } ?: return
		snapshot.connection.providerProblem(provider)?.let { problem ->
			state.value = state.value.reduce(StudioEvent.ProviderCheckFailed(id, problem))
			return
		}
		scope.launch {
			runCatching { json.decodeFromJsonElement<ProviderCheckResult>(call("providers.check", json.encodeToJsonElement(provider.toRequest(snapshot.connection)), context = "providerId=$id")) }
				.onSuccess { state.value = state.value.reduce(StudioEvent.ProviderCheckFinished(id, it.message)) }
				.onFailure { state.value = state.value.reduce(StudioEvent.ProviderCheckFailed(id, "Connection failed: ${it.message}")) }
		}
	}

	private fun executeConsoleCommand() {
		if (!requireCapability("repl.execute")) return
		val snapshot = state.value
		val command = snapshot.console.input.trim()
		if (command.isEmpty() || consoleJob?.isActive == true) return
		// Fast feedback only: Amoo's repl.execute applies the same grammar and refuses destructive tools.
		ReplGrammar(snapshot.toolCatalog).parse(command).diagnostics.firstOrNull { it.severity == ReplSeverity.Error }?.let {
			state.value = state.value.reduce(StudioEvent.ShowNotice(it.message, NoticeTone.Error))
			return
		}
		state.value = state.value.reduce(StudioEvent.ConsoleCommandStarted)
		val platform = snapshot.devices.firstOrNull { it.id == snapshot.selectedDeviceId }?.platform?.name?.lowercase()
		val request = ReplRequest(command, snapshot.test, snapshot.selectedDeviceId, snapshot.selectedProviderId, platform)
		consoleJob = scope.launch {
			val entry = runCatching { json.decodeFromJsonElement<ReplResult>(call("repl.execute", json.encodeToJsonElement(request))) }
				.fold(
					onSuccess = {
						ConsoleEntry(
							"command-${System.nanoTime()}", command, it.output, failed = it.isError == true, tool = it.tool,
							structuredContent = it.structuredContent, artifacts = it.artifacts.orEmpty(), screenshot = it.screenshot, gesturePoint = it.gesturePoint,
							actionIndex = it.actionIndex, sessionId = it.sessionId,
						)
					},
					onFailure = { ConsoleEntry("command-${System.nanoTime()}", command, "Command failed: ${it.message}", failed = true) },
				)
			if (state.value.console.operation == ConsoleOperation.Running) state.value = state.value.reduce(StudioEvent.ConsoleCommandFinished(entry))
			consoleJob = null
		}
	}

	// MARK: Console recording

	/** Starts the session the reducer just marked as starting. It can boot the device and build Amoo's companion, so it may take minutes. */
	private fun startConsoleRecording() {
		val recording = state.value.console.recording?.takeIf { it.status == ConsoleRecordingStatus.Starting } ?: return
		val request = ReplRecordingStartRequest(recording.device.id, recording.device.platform.name.lowercase(), recording.appId, state.value.lastBuildArtifact)
		scope.launch {
			runCatching { json.decodeFromJsonElement<ReplRecording>(call("repl.startRecording", json.encodeToJsonElement(request), timeoutMs = RECORDING_START_TIMEOUT_MILLIS, context = "deviceId=${recording.device.id}")) }
				.onSuccess { state.value = state.value.reduce(StudioEvent.ConsoleRecordingStarted(it.sessionId, it.actionCount)) }
				.onFailure { state.value = state.value.reduce(StudioEvent.ConsoleRecordingFailed("Could not start recording: ${it.message}")) }
		}
	}

	private fun stopConsoleRecording() {
		val recording = state.value.console.recording?.takeIf { it.status == ConsoleRecordingStatus.Stopping } ?: return
		scope.launch {
			runCatching { json.decodeFromJsonElement<ReplRecording>(call("repl.stopRecording", json.encodeToJsonElement(ReplRecordingStopRequest(recording.device.id)), timeoutMs = RECORDING_STOP_TIMEOUT_MILLIS, context = "deviceId=${recording.device.id}")) }
				.onSuccess { state.value = state.value.reduce(StudioEvent.ConsoleRecordingStopped(it.actionCount, it.planPath, it.message)) }
				.onFailure { state.value = state.value.reduce(StudioEvent.ConsoleRecordingFailed("Could not stop recording: ${it.message}")) }
		}
	}

	private fun runTest() {
		if (!requireCapability("tests.run")) return
		val snapshot = state.value
		val deviceId = snapshot.selectedDeviceId ?: run {
			state.value = state.value.reduce(StudioEvent.ShowNotice("Choose a device before running the test", NoticeTone.Error))
			return
		}
		if (testRunJob?.isActive == true) return
		state.value = state.value.reduce(StudioEvent.TestRunStarted("Running ${snapshot.test.name}…"))
		val request = TestRunRequest(snapshot.test, deviceId, snapshot.selectedProviderId)
		testRunJob = scope.launch {
			if (snapshot.connection.supports("tests.start")) {
				runAsyncTest(request)
				return@launch
			}
			runCatching { json.decodeFromJsonElement<TestRunResult>(call("tests.run", json.encodeToJsonElement(request))) }
				.onSuccess { result -> state.value = state.value.reduce(StudioEvent.TestRunFinished(result.message, result.sessionId, result.reportId)) }
				.onFailure { error -> if (state.value.testExecution is TestExecution.Running) state.value = state.value.reduce(StudioEvent.TestRunFailed("Test run failed: ${error.message}")) }
			testRunJob = null
		}
	}

	private suspend fun runAsyncTest(request: TestRunRequest) {
		runCatching { json.decodeFromJsonElement<TestStartResult>(call("tests.start", json.encodeToJsonElement(request))) }
			.onFailure { state.value = state.value.reduce(StudioEvent.TestRunFailed("Test run failed: ${it.message}")); testRunJob = null }
			.onSuccess { started ->
				val pushed = state.value.connection.supports(TESTS_PROGRESS_CAPABILITY)
				var previous: TestRunStatus? = null
				// Polling backs off from 250 ms to 1 s while one operation is long-running.
				var pollDelayMillis = 250L
				while (testRunJob?.isActive == true) {
					val status = runCatching { nextRunStatus(started.runId, previous, pushed, pollDelayMillis) }.getOrElse {
						state.value = state.value.reduce(StudioEvent.TestRunFailed("Test progress failed: ${it.message}"))
						break
					}
					if (status.runId != started.runId) {
						state.value = state.value.reduce(StudioEvent.TestRunFailed("Test progress failed: Amoo reported run ${status.runId} while tracking ${started.runId}"))
						break
					}
					state.value = state.value.reduce(StudioEvent.TestRunProgress(status.runId, status.message, status.currentOperation, status.totalOperations))
					pollDelayMillis = if (status.currentOperation != previous?.currentOperation) 250L else (pollDelayMillis * 2).coerceAtMost(1_000)
					previous = status
					when (status.state) {
						TestRunState.Running -> Unit
						TestRunState.Passed -> { state.value = state.value.reduce(StudioEvent.TestRunFinished(status.message, status.sessionId, status.reportId)); break }
						TestRunState.Failed -> { state.value = state.value.reduce(StudioEvent.TestRunFailed(status.message)); break }
						TestRunState.Cancelled -> { state.value = state.value.copy(testExecution = TestExecution.Idle, notice = Notice.info(status.message)); break }
					}
				}
				pushedRunStatuses.update { it - started.runId }
				testRunJob = null
			}
	}

	/**
	 * Waits for the run's next status. With [pushed] progress it waits for a notification that differs
	 * from [previous], polling `tests.status` only as a heartbeat in case one was dropped; otherwise it
	 * polls after [pollDelayMillis].
	 */
	private suspend fun nextRunStatus(runId: String, previous: TestRunStatus?, pushed: Boolean, pollDelayMillis: Long): TestRunStatus {
		if (pushed) {
			withTimeoutOrNull(PUSHED_PROGRESS_HEARTBEAT_MILLIS) {
				pushedRunStatuses.mapNotNull { it[runId] }.first { it != previous }
			}?.let { return it }
		} else if (previous != null) {
			delay(pollDelayMillis)
		}
		return json.decodeFromJsonElement<TestRunStatus>(call("tests.status", buildJsonObject { put("runId", runId) }, context = "runId=$runId"))
	}

	private fun cancelTestRun(runId: String?) {
		if (runId != null && state.value.connection.supports("tests.cancel")) {
			scope.launch { runCatching { call("tests.cancel", buildJsonObject { put("runId", runId) }, context = "runId=$runId") } }
		}
		testRunJob?.cancel()
		testRunJob = null
	}

	private fun exportTest() {
		if (!requireCapability("tests.export")) return
		val snapshot = state.value
		if (snapshot.testExportInProgress) return
		if (snapshot.test.compiledPlan?.toolOperations.isNullOrEmpty()) {
			state.value = state.value.reduce(StudioEvent.ShowNotice("Add at least one action before exporting a test", NoticeTone.Error))
			return
		}
		state.value = state.value.reduce(StudioEvent.TestExportStarted)
		scope.launch {
			runCatching { json.decodeFromJsonElement<TestExportResult>(call("tests.export", json.encodeToJsonElement(TestExportRequest(snapshot.test)))) }
				.onSuccess { result -> saveExportedTest(result) }
				.onFailure { state.value = state.value.reduce(StudioEvent.TestExportFailed("Test export failed: ${it.message}")) }
		}
	}

	private fun saveExportedTest(result: TestExportResult) {
		val file = filePicker.chooseFileToSave("Save generated test", result.fileName)
		if (file == null) {
			state.value = state.value.reduce(StudioEvent.TestExportFinished("Export cancelled"))
			return
		}
		runCatching { writeAtomically(file, result.source) }
			.onSuccess { state.value = state.value.reduce(StudioEvent.TestExportFinished("Exported ${file.name}")) }
			.onFailure { state.value = state.value.reduce(StudioEvent.TestExportFailed("Could not write file: ${it.message}")) }
	}

	private fun loadApiKeys() = scope.launch {
		val profiles = state.value.providers
		val result = withContext(keychainDispatcher) { providerStore.loadApiKeys(profiles.map { it.id }) }
		state.value = state.value.reduce(StudioEvent.ProviderApiKeysLoaded(result.keys, result.failures.mapValues { it.value.credentialStorageMessage() }))
		if (result.failures.isNotEmpty()) {
			val names = profiles.filter { it.id in result.failures }.joinToString { it.name }
			state.value = state.value.reduce(StudioEvent.ShowNotice("Could not read API keys for $names. ${result.failures.values.first().credentialStorageMessage()}", NoticeTone.Error))
		}
	}

	private fun persistChatMessages() {
		runCatching { chatHistory.save(state.value.chat.messages) }
			.onFailure {
				log.error("chat.persist", it)
				state.value = state.value.reduce(StudioEvent.ShowNotice("Could not save conversation: ${it.message}", NoticeTone.Error))
			}
	}

	private fun persistThemeMode(themeMode: ThemeMode) {
		runCatching { studioPreferences.saveThemeMode(themeMode) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not save appearance: ${it.message}", NoticeTone.Error)) }
	}

	private fun revealLogs() {
		val directory = StudioLog.directory
		if (directory == null || !directory.isDirectory) {
			state.value = state.value.reduce(StudioEvent.ShowNotice("Studio has no log folder on this machine", NoticeTone.Error))
			return
		}
		runCatching {
			require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { "Opening folders is unsupported on this desktop" }
			Desktop.getDesktop().open(directory)
		}.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not open the log folder: ${it.message}", NoticeTone.Error)) }
	}

	private fun saveProvider(profile: ProviderProfile) {
		if (profile.id in state.value.providerApiKeyReadFailures) return
		persistProfiles()
		scope.launch {
			runCatching { withContext(keychainDispatcher) { providerStore.saveApiKey(profile.id, profile.apiKey) } }
				.onSuccess { state.value = state.value.reduce(StudioEvent.ProviderApiKeySaveFinished(profile.id)) }
				.onFailure {
					if (it is CancellationException) throw it
					state.value = state.value.reduce(StudioEvent.ProviderApiKeySaveFinished(profile.id, it.credentialStorageMessage()))
				}
		}
	}

	private fun removeProvider(id: String) {
		persistProfiles()
		deleteProviderApiKey(id)
	}

	private fun deleteProviderApiKey(id: String) {
		scope.launch {
			runCatching { withContext(keychainDispatcher) { providerStore.deleteApiKey(id) } }
				.onSuccess { state.value = state.value.reduce(StudioEvent.ProviderApiKeyDeleteFinished(id)) }
				.onFailure {
					if (it is CancellationException) throw it
					state.value = state.value.reduce(StudioEvent.ProviderApiKeyDeleteFinished(id, it.credentialStorageMessage()))
				}
		}
	}

	private fun persistProfiles() {
		runCatching { providerStore.saveProfiles(state.value.providers) }
			.onFailure { state.value = state.value.reduce(StudioEvent.ShowNotice("Could not save providers: ${it.message}", NoticeTone.Error)) }
	}

	private fun requireCapability(capability: String): Boolean {
		if (state.value.connection.supports(capability)) return true
		state.value = state.value.copy(
			deviceOperation = DeviceOperation.Idle,
			notice = Notice.error("The connected Amoo version does not support $capability"),
		)
		return false
	}

	private fun safeFileName(value: String) = value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "untitled-test" }

	/** Remembers the working context across launches. Dragging the divider emits many fractions, so writes wait for the state to settle. */
	@OptIn(FlowPreview::class)
	private fun persistSession() {
		fun StudioState.session() = PersistedSession(projectPath, appId, schemeOrModule, selectedProviderId, selectedDeviceId, workbenchSplit)
		// Compared with what was last stored, not skipped by position: the first change can arrive before collection starts.
		var persisted = state.value.session()
		scope.launch {
			state.map { it.session() }.distinctUntilChanged().debounce(500).filter { it != persisted }
				.collect { session ->
					runCatching { studioPreferences.saveSession(session) }
						.onSuccess { persisted = session }
						.onFailure { log.error("session.persist", it) }
				}
		}
	}

	fun persistWindowBounds(origin: WindowOrigin?, size: WindowSize) {
		runCatching {
			studioPreferences.saveWindowSize(size)
			origin?.let(studioPreferences::saveWindowOrigin)
		}.onFailure { log.error("window.persist", it) }
	}

	override fun close() { chatJob?.cancel(); consoleJob?.cancel(); testRunJob?.cancel(); agentWatchJob?.cancel(); generateJob?.cancel(); mirror.close(); rpc.close() }
}

private const val PUSHED_PROGRESS_HEARTBEAT_MILLIS = 5_000L
// Polled or per-keystroke calls: logged only at debug level so they don't bury the log.
private val QUIET_METHODS = setOf("tests.status", "agent.events", "repl.complete", "repl.validate")
private const val AGENT_HEARTBEAT_MILLIS = 5_000L
// Starting a recording can boot the device and build the companion; stopping writes the plan.
private const val RECORDING_START_TIMEOUT_MILLIS = 15 * 60_000L
private const val RECORDING_STOP_TIMEOUT_MILLIS = 2 * 60_000L
