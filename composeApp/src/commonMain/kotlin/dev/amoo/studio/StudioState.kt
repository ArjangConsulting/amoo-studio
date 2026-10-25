package dev.amoo.studio

import androidx.compose.runtime.Immutable
import io.github.maniramezan.kmpcomponents.SplitPaneDefaults
import io.github.maniramezan.kmpcomponents.coerceSplit
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Immutable
data class StudioState(
	val hostPlatform: HostPlatform = HostPlatform.MacOS,
	val themeMode: ThemeMode = ThemeMode.System,
	val connection: ConnectionState = ConnectionState.Starting,
	val section: StudioSection = StudioSection.Overview,
	val test: AmooTest = AmooTest(),
	val testPath: String? = null,
	val isTestDirty: Boolean = false,
	val testExecution: TestExecution = TestExecution.Idle,
	val operationDraft: ToolOperationDraft = ToolOperationDraft(),
	/** The console and plan-editor vocabulary; [ToolCatalog.Legacy] until Amoo's `tools.catalog` loads. */
	val toolCatalog: ToolCatalog = ToolCatalog.Legacy,
	val lastSessionId: String? = null,
	val lastReportId: String? = null,
	val reports: List<TestReport> = emptyList(),
	val selectedReportId: String? = null,
	val reportsLoading: Boolean = false,
	val providers: List<ProviderProfile> = defaultProviders(),
	val selectedProviderId: String? = null,
	val providerChecks: Map<String, ProviderCheckState> = emptyMap(),
	val providerApiKeysLoaded: Boolean = false,
	val editingProviderId: String? = null,
	val providerDraft: ProviderProfile = newProviderDraft(),
	val providerApiKeyReadFailures: Map<String, String> = emptyMap(),
	val providerApiKeySaveFailures: Map<String, String> = emptyMap(),
	val providerApiKeysSaving: Set<String> = emptySet(),
	val providerApiKeyDeletionFailures: Map<String, String> = emptyMap(),
	val chat: ChatState = ChatState(),
	val console: ConsoleState = ConsoleState(),
	val notice: String? = null,
	val devices: List<StudioDevice> = emptyList(),
	val selectedDeviceId: String? = null,
	val deviceOperation: DeviceOperation = DeviceOperation.Idle,
	val projectPath: String = "",
	val appId: String = "",
	val schemeOrModule: String = "",
	val lastBuildArtifact: String? = null,
	val pendingApproval: PendingApproval? = null,
	val createDevice: CreateDeviceState? = null,
	val mcpStatus: McpStatus = McpStatus.Unknown,
	val testExportInProgress: Boolean = false,
	val amooInstall: AmooInstallState = AmooInstallState.Idle,
	val mirror: DeviceMirrorState = DeviceMirrorState(),
	/** The current or most recent agent run in AI testing. */
	val agentRun: AgentRunState? = null,
	/** The most recent generated test, shown in the Generated test section. */
	val generatedTest: GeneratedTestState? = null,
	/** Fraction of the workbench width given to the console or agent feed; the device pane gets the rest. */
	val workbenchSplit: Float = DEFAULT_WORKBENCH_SPLIT,
)

const val DEFAULT_WORKBENCH_SPLIT = 0.6f

sealed interface AmooInstallState {
	data object Idle : AmooInstallState
	data object Running : AmooInstallState
	data class Failed(val message: String) : AmooInstallState
}

sealed interface McpStatus {
	data object Unknown : McpStatus
	data object Checking : McpStatus
	data class Ready(val transport: String, val arguments: List<String>) : McpStatus
	data class Unavailable(val reason: String) : McpStatus
}

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

enum class HostPlatform(val label: String) {
	MacOS("macOS"),
	Linux("Linux"),
	Unsupported("Unsupported host"),
}

enum class StudioSection(val title: String) { Overview("Overview"), Tests("Tests"), Devices("Devices"), Chat("AI testing"), Console("Console"), GeneratedTest("Generated test"), Reports("Reports"), Settings("Settings") }

@Serializable
data class AmooTest(
	val formatVersion: Int = 1,
	val name: String = "Untitled test",
	val description: String = "",
	val platform: TestPlatform = TestPlatform.Ios,
	val steps: List<TestStep> = listOf(TestStep(id = "step-1")),
	val requirements: TestRequirements? = null,
	val compiledPlan: CompiledToolPlan? = null,
	val metadata: Map<String, String> = emptyMap(),
)

@Serializable data class TestStep(val id: String, val instruction: String = "", val expected: String = "")
@Serializable data class TestRequirements(val appId: String? = null, val projectPath: String? = null, val deviceName: String? = null)
@Serializable
data class CompiledToolPlan(
	val compiler: String,
	val compilerVersion: String,
	val operations: List<String> = emptyList(),
	val toolOperations: List<ToolOperation> = emptyList(),
	/** Notes from compiling a recorded session; written by Amoo, preserved by Studio. */
	val warnings: List<PlanWarning> = emptyList(),
)

@Serializable data class ToolOperation(val id: String, val tool: String, val arguments: Map<String, String> = emptyMap())
data class ToolOperationDraft(val tool: String = "tap_element", val arguments: Map<String, String> = emptyMap())

/** Why [ToolOperationDraft] cannot be added to the plan under [catalog], or null when it can. */
fun ToolOperationDraft.validationError(catalog: ToolCatalog): String? {
	val definition = catalog.tool(tool)?.takeIf { it.planTool != null } ?: return "Unsupported tool: $tool"
	val missing = definition.arguments.filter { it.required && arguments[it.name].isNullOrBlank() }
	if (missing.isNotEmpty()) return "Required: ${missing.joinToString { it.name }}"
	definition.arguments.forEach { argument ->
		val value = arguments[argument.name]?.takeIf(String::isNotBlank) ?: return@forEach
		val values = argument.values?.takeIf { argument.valuesAreExhaustive && it.isNotEmpty() } ?: return@forEach
		if (values.none { it.equals(value, ignoreCase = true) }) return "${argument.name} must be one of: ${values.joinToString()}"
	}
	if (definition.category == "legacy" && tool in ToolCatalog.LEGACY_SELECTOR_TOOLS && listOf("id", "label", "contains_text").none { !arguments[it].isNullOrBlank() }) {
		return "Provide an accessibility ID, label, or text selector"
	}
	return null
}
@Serializable enum class TestPlatform(val label: String) { Ios("iOS"), Android("Android") }
sealed interface TestExecution { data object Idle : TestExecution; data class Running(val message: String, val runId: String? = null, val currentOperation: Int = 0, val totalOperations: Int = 0) : TestExecution }
@Serializable data class TestReport(
	val id: String,
	val testName: String,
	val status: ReportStatus,
	val startedAt: String,
	val durationMillis: Long? = null,
	val deviceName: String = "",
	val summary: String = "",
	val artifacts: List<String> = emptyList(),
)
@Serializable enum class ReportStatus { Passed, Failed, Cancelled, Running }

@Serializable
data class ProviderProfile(
	val id: String,
	val name: String,
	val kind: ProviderKind,
	val baseUrl: String,
	val model: String,
	val apiKeyEnvironmentVariable: String = "",
	/** Held in memory only: desktopMain keeps it in the OS keychain and it is never serialized with the profile. */
	@Transient val apiKey: Secret = Secret.Empty,
)

/** A credential whose [toString] never reveals its value, so it cannot leak through logs, notices, or data-class dumps. */
@Serializable
@JvmInline
value class Secret(val value: String) {
	val isBlank: Boolean get() = value.isBlank()

	/** A display hint such as `••••a1b2`; values too short to hint without revealing much show dots only. */
	val hint: String get() = if (value.length >= 16) "••••${value.takeLast(4)}" else "••••"

	override fun toString(): String = if (value.isEmpty()) "Secret(empty)" else "Secret(redacted)"

	companion object { val Empty = Secret("") }
}

/** Advertised by Amoo builds that accept an API key inline with a provider profile. */
const val PROVIDER_API_KEY_CAPABILITY = "providers.apiKey"

/** Why [profile] can't authenticate against the connected Amoo, or null when it can. */
fun ConnectionState.providerCredentialProblem(profile: ProviderProfile): String? = when {
	profile.kind == ProviderKind.Ollama -> null
	!profile.apiKey.isBlank && supports(PROVIDER_API_KEY_CAPABILITY) -> null
	profile.apiKeyEnvironmentVariable.isNotBlank() -> null
	!profile.apiKey.isBlank -> "The connected Amoo version can't use API keys stored by Studio. Update Amoo or set an API key environment variable for ${profile.name}."
	else -> "Add an API key or an API key environment variable for ${profile.name}."
}

/** Derives a provider's id from its display name and model; saving the same pair again replaces the profile. */
fun providerId(name: String, model: String): String =
	"provider-${name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-${model.trim().hashCode()}"

@Serializable enum class ProviderKind(val label: String, val defaultBaseUrl: String, val defaultApiKeyEnvironmentVariable: String) {
	OpenAI("OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY"),
	Anthropic("Claude / Anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY"),
	Ollama("Ollama", "http://localhost:11434", ""),
	Custom("OpenAI-compatible", "https://api.openai.com/v1", "OPENAI_API_KEY"),
}
sealed interface ProviderCheckState { data object Checking : ProviderCheckState; data class Ready(val message: String) : ProviderCheckState; data class Failed(val message: String) : ProviderCheckState }

@Immutable
data class ChatState(
	val input: String = "",
	val messages: List<ChatMessage> = emptyList(),
	val operation: ChatOperation = ChatOperation.Idle,
	val proposedPlan: CompiledToolPlan? = null,
	val lastError: String? = null,
)

@Serializable data class ChatMessage(val id: String, val role: ChatRole, val content: String)
@Serializable enum class ChatRole { User, Assistant }
sealed interface ChatOperation { data object Idle : ChatOperation; data object Sending : ChatOperation }

@Immutable
data class ConsoleState(
	val input: String = "",
	val entries: List<ConsoleEntry> = emptyList(),
	val operation: ConsoleOperation = ConsoleOperation.Idle,
	val historyIndex: Int? = null,
	val recording: ConsoleRecording? = null,
)

/** A finished console command. [tool], [structuredContent], and [artifacts] come from Amoo builds with `repl.structuredResults`. */
@Serializable data class ConsoleEntry(
	val id: String,
	val command: String,
	val output: String,
	val failed: Boolean = false,
	val tool: String? = null,
	val structuredContent: String? = null,
	val artifacts: List<String> = emptyList(),
	val screenshot: ScreenshotRef? = null,
	val gesturePoint: GesturePoint? = null,
	/** Set when Amoo recorded the command into session [sessionId] as plan step `step-<actionIndex>`. */
	val actionIndex: Int? = null,
	val sessionId: String? = null,
)
sealed interface ConsoleOperation { data object Idle : ConsoleOperation; data object Running : ConsoleOperation }

@Serializable
data class StudioDevice(
	val id: String,
	val name: String,
	val platform: TestPlatform,
	val osVersion: String = "",
	val status: DeviceStatus,
	val physical: Boolean = false,
)

@Serializable enum class DeviceStatus { Running, Available }
data class CreateDeviceState(val platform: TestPlatform, val name: String = "", val runtime: String = "", val deviceType: String = "")
sealed interface DeviceOperation { data object Idle : DeviceOperation; data class Working(val message: String) : DeviceOperation }
data class PendingApproval(val title: String, val message: String, val action: ApprovedAction, val confirmLabel: String = "Confirm")
sealed interface ApprovedAction {
	data object ResetAppData : ApprovedAction
	data object InstallAmoo : ApprovedAction
	/** An agent's destructive tool call waiting for the user. */
	data class AgentTool(val runId: String, val callId: String) : ApprovedAction
}

const val AMOO_HOMEBREW_TAP = "arjangconsulting/tap"
const val AMOO_HOMEBREW_FORMULA = "amoo"
const val AMOO_HOMEBREW_TAP_COMMAND = "brew tap $AMOO_HOMEBREW_TAP"
const val AMOO_HOMEBREW_INSTALL_COMMAND = "brew install $AMOO_HOMEBREW_FORMULA"

/** An unread existing key must not be interpreted as an empty key and deleted by a form save. */
fun StudioState.canSaveProvider(profile: ProviderProfile): Boolean =
	profile.id !in providerApiKeyReadFailures && profile.id !in providerApiKeysSaving && (providerApiKeysLoaded || providers.none { it.id == profile.id } || providers.firstOrNull { it.id == profile.id }?.apiKey?.isBlank == false)

fun newProviderDraft() = ProviderProfile("", "", ProviderKind.OpenAI, ProviderKind.OpenAI.defaultBaseUrl, "", ProviderKind.OpenAI.defaultApiKeyEnvironmentVariable)

fun defaultProviders() = listOf(ProviderProfile("ollama", "Local Ollama", ProviderKind.Ollama, "http://localhost:11434", "qwen3.8:27b-mlx"))

sealed interface StudioEvent {
	data class SelectSection(val section: StudioSection) : StudioEvent
	data class ChangeThemeMode(val value: ThemeMode) : StudioEvent
	data object RetryConnection : StudioEvent
	data object RequestInstallAmoo : StudioEvent
	data object InstallAmooStarted : StudioEvent
	data class InstallAmooFinished(val message: String) : StudioEvent
	data class InstallAmooFailed(val message: String) : StudioEvent
	data object CopyInstallCommands : StudioEvent
	data object NewTest : StudioEvent
	data object OpenTest : StudioEvent
	data object SaveTest : StudioEvent
	data object SaveTestAs : StudioEvent
	data class TestLoaded(val test: AmooTest, val path: String) : StudioEvent
	data class TestSaved(val path: String) : StudioEvent
	data class ChangeTestName(val value: String) : StudioEvent
	data class ChangeTestDescription(val value: String) : StudioEvent
	data class ChangeTestPlatform(val value: TestPlatform) : StudioEvent
	data object AddTestStep : StudioEvent
	data class ChangeTestStep(val id: String, val instruction: String? = null, val expected: String? = null) : StudioEvent
	data class RemoveTestStep(val id: String) : StudioEvent
	data class AddTestPlanOperation(val command: String) : StudioEvent
	data class RemoveTestPlanOperation(val index: Int) : StudioEvent
	data class ChangeToolOperationType(val tool: String) : StudioEvent
	data class ChangeToolOperationArgument(val name: String, val value: String) : StudioEvent
	data object AddToolOperation : StudioEvent
	data class RemoveToolOperation(val id: String) : StudioEvent
	data object RunTest : StudioEvent
	data object CancelTestRun : StudioEvent
	data object ExportTest : StudioEvent
	data object TestExportStarted : StudioEvent
	data class TestExportFinished(val message: String) : StudioEvent
	data class TestExportFailed(val message: String) : StudioEvent
	data class TestRunStarted(val message: String) : StudioEvent
	data class TestRunProgress(val runId: String, val message: String, val currentOperation: Int, val totalOperations: Int) : StudioEvent
	data class TestRunFinished(val message: String, val sessionId: String?, val reportId: String?) : StudioEvent
	data class TestRunFailed(val message: String) : StudioEvent
	data object RefreshReports : StudioEvent
	data class ReportsLoaded(val reports: List<TestReport>) : StudioEvent
	data class ReportsFailed(val message: String) : StudioEvent
	data class SelectReport(val id: String) : StudioEvent
	data class OpenReportArtifact(val path: String) : StudioEvent
	data class SaveProvider(val profile: ProviderProfile) : StudioEvent
	data class RemoveProvider(val id: String) : StudioEvent
	data class EditProvider(val id: String) : StudioEvent
	data object CancelProviderEdit : StudioEvent
	data class ProviderApiKeysLoaded(val keys: Map<String, Secret>, val failures: Map<String, String> = emptyMap()) : StudioEvent
	data class ChangeProviderDraft(val profile: ProviderProfile) : StudioEvent
	data object RetryProviderApiKeys : StudioEvent
	data class RetryProviderApiKeySave(val id: String) : StudioEvent
	data class ProviderApiKeySaveFinished(val id: String, val error: String? = null) : StudioEvent
	data class RetryProviderApiKeyDelete(val id: String) : StudioEvent
	data class ProviderApiKeyDeleteFinished(val id: String, val error: String? = null) : StudioEvent
	data class SelectProvider(val id: String) : StudioEvent
	data class CheckProvider(val id: String) : StudioEvent
	data class ProviderCheckFinished(val id: String, val message: String) : StudioEvent
	data class ProviderCheckFailed(val id: String, val message: String) : StudioEvent
	data class ChangeChatInput(val value: String) : StudioEvent
	data object SendChat : StudioEvent
	data class ChatRequestStarted(val message: ChatMessage) : StudioEvent
	data class ChatResponseReceived(val message: ChatMessage, val proposedPlan: CompiledToolPlan? = null) : StudioEvent
	data object ApplyProposedPlan : StudioEvent
	data object RejectProposedPlan : StudioEvent
	data class ChatRequestFailed(val message: String) : StudioEvent
	data object CancelChat : StudioEvent
	data object ClearChat : StudioEvent
	data object RetryLastChat : StudioEvent
	data class ChangeConsoleInput(val value: String) : StudioEvent
	data class ToolCatalogLoaded(val catalog: ToolCatalog) : StudioEvent
	data class NavigateConsoleHistory(val delta: Int) : StudioEvent
	data object ExecuteConsoleCommand : StudioEvent
	data object ConsoleCommandStarted : StudioEvent
	data class ConsoleCommandFinished(val entry: ConsoleEntry) : StudioEvent
	data object CancelConsoleCommand : StudioEvent
	data object ClearConsole : StudioEvent
	data object StartConsoleRecording : StudioEvent
	data class ConsoleRecordingStarted(val sessionId: String, val actionCount: Int) : StudioEvent
	data object StopConsoleRecording : StudioEvent
	data class ConsoleRecordingStopped(val actionCount: Int?, val planPath: String?, val message: String?) : StudioEvent
	data class ConsoleRecordingFailed(val message: String) : StudioEvent
	/** Amoo exited while recording; it ends recordings on shutdown, so what was recorded is kept. */
	data object ConsoleRecordingInterrupted : StudioEvent
	data object DismissConsoleRecording : StudioEvent
	data object GenerateConsoleTest : StudioEvent
	data class ShowNotice(val message: String?) : StudioEvent
	data object CopyMcpConfiguration : StudioEvent
	data object RefreshMcpStatus : StudioEvent
	data class McpStatusLoaded(val transport: String, val arguments: List<String>) : StudioEvent
	data class McpStatusFailed(val reason: String) : StudioEvent
	data object RefreshDevices : StudioEvent
	data class DevicesLoaded(val devices: List<StudioDevice>) : StudioEvent
	data class SelectDevice(val id: String) : StudioEvent
	data class StartDevice(val id: String) : StudioEvent
	data object RequestCreateDevice : StudioEvent
	data class ChangeCreateDevicePlatform(val value: TestPlatform) : StudioEvent
	data class ChangeCreateDeviceName(val value: String) : StudioEvent
	data class ChangeCreateDeviceRuntime(val value: String) : StudioEvent
	data class ChangeCreateDeviceType(val value: String) : StudioEvent
	data object ConfirmCreateDevice : StudioEvent
	data object CancelCreateDevice : StudioEvent
	data object ChooseProjectPath : StudioEvent
	data class ChangeProjectPath(val value: String) : StudioEvent
	data class ChangeAppId(val value: String) : StudioEvent
	data class ChangeSchemeOrModule(val value: String) : StudioEvent
	data object BuildInstallAndRun : StudioEvent
	data object ReinstallAndRun : StudioEvent
	data object RequestResetAppData : StudioEvent
	data class ResolveApproval(val approved: Boolean) : StudioEvent
	data class DeviceOperationStarted(val message: String) : StudioEvent
	data class DeviceOperationFinished(val message: String, val artifact: String? = null) : StudioEvent
	data class MirrorStarting(val deviceId: String) : StudioEvent
	data class MirrorFrameReceived(val deviceId: String, val frame: DeviceFrame, val framesPerSecond: Double) : StudioEvent
	data class MirrorReconnecting(val deviceId: String, val reason: String) : StudioEvent
	data class MirrorUnavailable(val reason: String) : StudioEvent
	data object MirrorStopped : StudioEvent
	data object ToggleMirrorPaused : StudioEvent
	data class PinScreenshot(val pinned: PinnedScreenshot) : StudioEvent
	data object ReturnToLiveMirror : StudioEvent
	data class ChangeWorkbenchSplit(val fraction: Float) : StudioEvent
	data class AgentRunStarted(val runId: String, val request: ChatMessage) : StudioEvent
	data class AgentEventReceived(val event: AgentEvent) : StudioEvent
	data class AgentEventsReplayed(val runId: String, val events: List<AgentEvent>, val status: AgentRunStatus, val nextSeq: Int) : StudioEvent
	data class AgentRunFailed(val message: String) : StudioEvent
	data object CancelAgentRun : StudioEvent
	data object SteerAgentRun : StudioEvent
	data class AgentSteered(val message: ChatMessage) : StudioEvent
	data object DismissAgentRun : StudioEvent
	data class GenerateTest(val source: GenerationSource, val stepScreenshots: Map<String, PinnedScreenshot> = emptyMap()) : StudioEvent
	data class TestGenerated(val generated: GeneratedSource?, val problem: GenerationProblem?, val test: AmooTest) : StudioEvent
	data class TestGenerationFailed(val message: String) : StudioEvent
	data class SelectGeneratedOperation(val operationId: String?) : StudioEvent
	data class ChangeGeneratedTestLayout(val operationsSplit: Float? = null, val sourceSplit: Float? = null) : StudioEvent
	data class ChangeGeneratedTestOptions(val uiToolkit: UiToolkit? = null, val testName: String? = null) : StudioEvent
	data class RegenerateTest(val allowIncomplete: Boolean) : StudioEvent
	data object SaveGeneratedTest : StudioEvent
}

fun StudioState.reduce(event: StudioEvent): StudioState = when (event) {
	is StudioEvent.SelectSection -> copy(section = event.section)
	is StudioEvent.ChangeThemeMode -> copy(themeMode = event.value)
	StudioEvent.RetryConnection -> copy(connection = ConnectionState.Starting, notice = null)
	StudioEvent.RequestInstallAmoo -> copy(pendingApproval = PendingApproval(
		"Install Amoo via Homebrew?",
		"Studio will run:\n$AMOO_HOMEBREW_TAP_COMMAND\n$AMOO_HOMEBREW_INSTALL_COMMAND",
		ApprovedAction.InstallAmoo,
		confirmLabel = "Install",
	))
	StudioEvent.InstallAmooStarted -> copy(amooInstall = AmooInstallState.Running, notice = null)
	is StudioEvent.InstallAmooFinished -> copy(amooInstall = AmooInstallState.Idle, notice = event.message)
	is StudioEvent.InstallAmooFailed -> copy(amooInstall = AmooInstallState.Failed(event.message), notice = event.message)
	StudioEvent.CopyInstallCommands -> this
	StudioEvent.NewTest -> copy(test = AmooTest(platform = hostPlatform.defaultTestPlatform), testPath = null, isTestDirty = false, section = StudioSection.Tests)
	StudioEvent.OpenTest, StudioEvent.SaveTest, StudioEvent.SaveTestAs, StudioEvent.CopyMcpConfiguration, is StudioEvent.OpenReportArtifact -> this
	is StudioEvent.TestLoaded -> copy(test = event.test, testPath = event.path, isTestDirty = false, section = StudioSection.Tests, notice = "Opened ${event.path}")
	is StudioEvent.TestSaved -> copy(testPath = event.path, isTestDirty = false, notice = "Saved ${event.path}")
	is StudioEvent.ChangeTestName -> copy(test = test.copy(name = event.value), isTestDirty = true)
	is StudioEvent.ChangeTestDescription -> copy(test = test.copy(description = event.value), isTestDirty = true)
	is StudioEvent.ChangeTestPlatform -> copy(test = test.copy(platform = event.value), isTestDirty = true)
	StudioEvent.AddTestStep -> copy(test = test.copy(steps = test.steps + TestStep("step-${nextStepId(test.steps)}")), isTestDirty = true)
	is StudioEvent.ChangeTestStep -> copy(test = test.copy(steps = test.steps.map { step -> if (step.id == event.id) step.copy(instruction = event.instruction ?: step.instruction, expected = event.expected ?: step.expected) else step }), isTestDirty = true)
	is StudioEvent.RemoveTestStep -> copy(test = test.copy(steps = test.steps.filterNot { it.id == event.id }), isTestDirty = true)
	is StudioEvent.AddTestPlanOperation -> {
		val command = event.command.trim()
		val parsed = parseToolCommand(command, toolCatalog)
		if (command.isEmpty()) this else copy(
			test = test.copy(compiledPlan = (test.compiledPlan ?: CompiledToolPlan("studio-console", "1")).let { plan ->
				if (parsed != null) plan.copy(toolOperations = plan.toolOperations + ToolOperation("operation-${nextOperationId(plan.toolOperations)}", parsed.tool, parsed.arguments))
				else plan.copy(operations = plan.operations + command)
			}),
			isTestDirty = true,
			notice = "Added command to ${test.name}",
		)
	}
	is StudioEvent.RemoveTestPlanOperation -> copy(
		test = test.copy(compiledPlan = test.compiledPlan?.let { plan -> plan.copy(operations = plan.operations.filterIndexed { index, _ -> index != event.index }) }),
		isTestDirty = true,
	)
	is StudioEvent.ChangeToolOperationType -> copy(operationDraft = ToolOperationDraft(event.tool))
	is StudioEvent.ChangeToolOperationArgument -> copy(operationDraft = operationDraft.copy(arguments = operationDraft.arguments + (event.name to event.value)))
	StudioEvent.AddToolOperation -> if (operationDraft.validationError(toolCatalog) != null) this else copy(
		test = test.copy(compiledPlan = (test.compiledPlan ?: CompiledToolPlan("studio", "1")).let { plan ->
			plan.copy(toolOperations = plan.toolOperations + ToolOperation("operation-${nextOperationId(plan.toolOperations)}", operationDraft.tool, operationDraft.arguments.filterValues(String::isNotBlank)))
		}),
		operationDraft = ToolOperationDraft(operationDraft.tool),
		isTestDirty = true,
	)
	is StudioEvent.RemoveToolOperation -> copy(
		test = test.copy(compiledPlan = test.compiledPlan?.let { it.copy(toolOperations = it.toolOperations.filterNot { operation -> operation.id == event.id }) }),
		isTestDirty = true,
	)
	StudioEvent.RunTest -> this
	StudioEvent.CancelTestRun -> copy(testExecution = TestExecution.Idle, notice = "Test run cancelled")
	StudioEvent.ExportTest -> this
	StudioEvent.TestExportStarted -> copy(testExportInProgress = true, notice = null)
	is StudioEvent.TestExportFinished -> copy(testExportInProgress = false, notice = event.message)
	is StudioEvent.TestExportFailed -> copy(testExportInProgress = false, notice = event.message)
	is StudioEvent.TestRunStarted -> copy(testExecution = TestExecution.Running(event.message), notice = null)
	is StudioEvent.TestRunProgress -> copy(testExecution = TestExecution.Running(event.message, event.runId, event.currentOperation, event.totalOperations))
	is StudioEvent.TestRunFinished -> copy(testExecution = TestExecution.Idle, notice = event.message, lastSessionId = event.sessionId ?: lastSessionId, lastReportId = event.reportId ?: lastReportId)
	is StudioEvent.TestRunFailed -> copy(testExecution = TestExecution.Idle, notice = event.message)
	StudioEvent.RefreshReports -> copy(reportsLoading = true, notice = null)
	is StudioEvent.ReportsLoaded -> copy(reports = event.reports, reportsLoading = false, selectedReportId = selectedReportId?.takeIf { id -> event.reports.any { it.id == id } } ?: event.reports.firstOrNull()?.id)
	is StudioEvent.ReportsFailed -> copy(reportsLoading = false, notice = event.message)
	is StudioEvent.SelectReport -> copy(selectedReportId = event.id)
	is StudioEvent.SaveProvider -> if (!canSaveProvider(event.profile)) copy(notice = if (event.profile.id in providerApiKeysSaving) "Wait for the API key to finish saving before editing this provider." else "Read the stored API key before editing this provider.") else copy(
		providers = if (providers.any { it.id == event.profile.id }) providers.map { if (it.id == event.profile.id) event.profile else it } else providers + event.profile,
		selectedProviderId = event.profile.id,
		providerChecks = providerChecks - event.profile.id,
		editingProviderId = null,
		providerDraft = newProviderDraft(),
		providerApiKeysSaving = providerApiKeysSaving + event.profile.id,
		providerApiKeyDeletionFailures = providerApiKeyDeletionFailures - event.profile.id,
		notice = "Provider profile saved",
	)
	is StudioEvent.RemoveProvider -> copy(
		providers = providers.filterNot { it.id == event.id },
		selectedProviderId = selectedProviderId.takeUnless { it == event.id },
		providerChecks = providerChecks - event.id,
		editingProviderId = editingProviderId.takeUnless { it == event.id },
		providerDraft = if (editingProviderId == event.id) newProviderDraft() else providerDraft,
		providerApiKeyReadFailures = providerApiKeyReadFailures - event.id,
		providerApiKeySaveFailures = providerApiKeySaveFailures - event.id,
		providerApiKeysSaving = providerApiKeysSaving - event.id,
	)
	is StudioEvent.EditProvider -> if (!providerApiKeysLoaded || event.id in providerApiKeyReadFailures) this else providers.firstOrNull { it.id == event.id }?.let { copy(editingProviderId = it.id, providerDraft = it) } ?: this
	StudioEvent.CancelProviderEdit -> copy(editingProviderId = null, providerDraft = newProviderDraft())
	is StudioEvent.ChangeProviderDraft -> copy(providerDraft = event.profile)
	StudioEvent.RetryProviderApiKeys -> copy(providerApiKeysLoaded = false)
	is StudioEvent.RetryProviderApiKeySave -> if (providers.firstOrNull { it.id == event.id }?.let(::canSaveProvider) == true) copy(providerApiKeysSaving = providerApiKeysSaving + event.id) else this
	is StudioEvent.ProviderApiKeySaveFinished -> copy(
		providerApiKeysSaving = providerApiKeysSaving - event.id,
		providerApiKeySaveFailures = if (event.error == null || providers.none { it.id == event.id }) providerApiKeySaveFailures - event.id else providerApiKeySaveFailures + (event.id to event.error),
	)
	is StudioEvent.RetryProviderApiKeyDelete -> this
	is StudioEvent.ProviderApiKeyDeleteFinished -> copy(providerApiKeyDeletionFailures = if (event.error == null || providers.any { it.id == event.id }) providerApiKeyDeletionFailures - event.id else providerApiKeyDeletionFailures + (event.id to event.error))
	is StudioEvent.ProviderApiKeysLoaded -> copy(
		providers = providers.map { profile -> event.keys[profile.id]?.takeIf { profile.apiKey.isBlank }?.let { profile.copy(apiKey = it) } ?: profile },
		providerApiKeysLoaded = true,
		providerApiKeyReadFailures = event.failures.filterKeys { id -> providers.any { it.id == id } },
	)
	is StudioEvent.SelectProvider -> copy(selectedProviderId = event.id)
	is StudioEvent.CheckProvider -> copy(providerChecks = providerChecks + (event.id to ProviderCheckState.Checking))
	is StudioEvent.ProviderCheckFinished -> copy(providerChecks = providerChecks + (event.id to ProviderCheckState.Ready(event.message)))
	is StudioEvent.ProviderCheckFailed -> copy(providerChecks = providerChecks + (event.id to ProviderCheckState.Failed(event.message)))
	is StudioEvent.ChangeChatInput -> copy(chat = chat.copy(input = event.value))
	StudioEvent.SendChat -> this
	is StudioEvent.ChatRequestStarted -> copy(chat = chat.copy(input = "", messages = chat.messages + event.message, operation = ChatOperation.Sending, lastError = null), notice = null)
	is StudioEvent.ChatResponseReceived -> copy(chat = chat.copy(messages = chat.messages + event.message, operation = ChatOperation.Idle, proposedPlan = event.proposedPlan))
	StudioEvent.ApplyProposedPlan -> chat.proposedPlan?.let { proposal -> copy(test = test.copy(compiledPlan = proposal), isTestDirty = true, chat = chat.copy(proposedPlan = null), notice = "Applied AI plan to ${test.name}") } ?: this
	StudioEvent.RejectProposedPlan -> copy(chat = chat.copy(proposedPlan = null), notice = "AI plan discarded")
	is StudioEvent.ChatRequestFailed -> copy(chat = chat.copy(operation = ChatOperation.Idle, lastError = event.message), notice = event.message)
	StudioEvent.CancelChat -> copy(chat = chat.copy(operation = ChatOperation.Idle), notice = "AI request cancelled")
	StudioEvent.ClearChat -> copy(chat = ChatState())
	StudioEvent.RetryLastChat -> copy(chat = chat.copy(input = chat.messages.lastOrNull { it.role == ChatRole.User }?.content.orEmpty(), lastError = null))
	is StudioEvent.ChangeConsoleInput -> copy(console = console.copy(input = event.value, historyIndex = null))
	is StudioEvent.ToolCatalogLoaded -> copy(
		toolCatalog = event.catalog,
		operationDraft = operationDraft.takeIf { event.catalog.tool(it.tool)?.planTool != null } ?: ToolOperationDraft(event.catalog.planTools.firstOrNull()?.name ?: operationDraft.tool),
	)
	is StudioEvent.NavigateConsoleHistory -> {
		if (console.entries.isEmpty()) this else {
			val current = console.historyIndex ?: console.entries.size
			val next = (current + event.delta).coerceIn(0, console.entries.size)
			copy(console = console.copy(historyIndex = next.takeIf { it < console.entries.size }, input = console.entries.getOrNull(next)?.command.orEmpty()))
		}
	}
	StudioEvent.ExecuteConsoleCommand -> this
	StudioEvent.ConsoleCommandStarted -> copy(console = console.copy(operation = ConsoleOperation.Running), notice = null)
	is StudioEvent.ConsoleCommandFinished -> copy(console = console.copy(
		input = "",
		entries = console.entries + event.entry,
		operation = ConsoleOperation.Idle,
		historyIndex = null,
		recording = console.recording?.recording(event.entry),
	))
	StudioEvent.CancelConsoleCommand -> copy(console = console.copy(operation = ConsoleOperation.Idle), notice = "Command cancelled")
	StudioEvent.ClearConsole -> copy(console = ConsoleState(recording = console.recording))
	StudioEvent.StartConsoleRecording, is StudioEvent.ConsoleRecordingStarted, StudioEvent.StopConsoleRecording, is StudioEvent.ConsoleRecordingStopped,
	is StudioEvent.ConsoleRecordingFailed, StudioEvent.ConsoleRecordingInterrupted, StudioEvent.DismissConsoleRecording, StudioEvent.GenerateConsoleTest,
	-> reduceConsoleRecording(event)
	is StudioEvent.ShowNotice -> copy(notice = event.message)
	StudioEvent.RefreshMcpStatus -> copy(mcpStatus = McpStatus.Checking)
	is StudioEvent.McpStatusLoaded -> copy(mcpStatus = McpStatus.Ready(event.transport, event.arguments))
	is StudioEvent.McpStatusFailed -> copy(mcpStatus = McpStatus.Unavailable(event.reason))
	StudioEvent.RefreshDevices -> copy(deviceOperation = DeviceOperation.Working("Discovering devices…"))
	is StudioEvent.DevicesLoaded -> copy(devices = event.devices, deviceOperation = DeviceOperation.Idle, selectedDeviceId = selectedDeviceId?.takeIf { id -> event.devices.any { it.id == id } })
	is StudioEvent.SelectDevice -> copy(selectedDeviceId = event.id)
	is StudioEvent.StartDevice -> copy(selectedDeviceId = event.id, deviceOperation = DeviceOperation.Working("Starting device…"))
	StudioEvent.RequestCreateDevice -> copy(createDevice = CreateDeviceState(hostPlatform.defaultTestPlatform))
	is StudioEvent.ChangeCreateDevicePlatform -> copy(createDevice = createDevice?.copy(platform = event.value))
	is StudioEvent.ChangeCreateDeviceName -> copy(createDevice = createDevice?.copy(name = event.value))
	is StudioEvent.ChangeCreateDeviceRuntime -> copy(createDevice = createDevice?.copy(runtime = event.value))
	is StudioEvent.ChangeCreateDeviceType -> copy(createDevice = createDevice?.copy(deviceType = event.value))
	StudioEvent.ConfirmCreateDevice -> copy(createDevice = null, deviceOperation = DeviceOperation.Working("Creating device…"))
	StudioEvent.CancelCreateDevice -> copy(createDevice = null)
	StudioEvent.ChooseProjectPath, StudioEvent.BuildInstallAndRun, StudioEvent.ReinstallAndRun -> this
	is StudioEvent.ChangeProjectPath -> copy(projectPath = event.value)
	is StudioEvent.ChangeAppId -> copy(appId = event.value)
	is StudioEvent.ChangeSchemeOrModule -> copy(schemeOrModule = event.value)
	StudioEvent.RequestResetAppData -> copy(pendingApproval = PendingApproval("Erase app data?", "This removes all local data for $appId on the selected device. The action cannot be undone.", ApprovedAction.ResetAppData, confirmLabel = "Erase data"))
	is StudioEvent.ResolveApproval -> copy(pendingApproval = null)
	is StudioEvent.DeviceOperationStarted -> copy(deviceOperation = DeviceOperation.Working(event.message), notice = null)
	is StudioEvent.DeviceOperationFinished -> copy(deviceOperation = DeviceOperation.Idle, notice = event.message, lastBuildArtifact = event.artifact ?: lastBuildArtifact)
	is StudioEvent.MirrorStarting -> copy(mirror = mirror.copy(status = MirrorStatus.Starting, deviceId = event.deviceId, frame = mirror.frame.takeIf { mirror.deviceId == event.deviceId }))
	// A frame from a stream that was already replaced (another device) is dropped.
	is StudioEvent.MirrorFrameReceived -> if (event.deviceId != mirror.deviceId) this else copy(mirror = mirror.copy(status = MirrorStatus.Live, frame = event.frame, framesPerSecond = event.framesPerSecond))
	is StudioEvent.MirrorReconnecting -> if (event.deviceId != mirror.deviceId) this else copy(mirror = mirror.copy(status = MirrorStatus.Reconnecting(event.reason)))
	is StudioEvent.MirrorUnavailable -> copy(mirror = mirror.copy(status = MirrorStatus.Unavailable(event.reason)))
	StudioEvent.MirrorStopped -> copy(mirror = mirror.copy(status = if (mirror.paused) MirrorStatus.Paused else MirrorStatus.Off, framesPerSecond = 0.0))
	StudioEvent.ToggleMirrorPaused -> copy(mirror = mirror.copy(paused = !mirror.paused, status = if (mirror.paused) mirror.status else MirrorStatus.Paused))
	is StudioEvent.PinScreenshot -> copy(mirror = mirror.copy(pinned = event.pinned))
	StudioEvent.ReturnToLiveMirror -> copy(mirror = mirror.copy(pinned = null))
	is StudioEvent.ChangeWorkbenchSplit -> copy(workbenchSplit = coerceSplit(event.fraction, MIN_WORKBENCH_SPLIT, MAX_WORKBENCH_SPLIT))
	is StudioEvent.AgentRunStarted -> copy(
		agentRun = AgentRunState(event.runId, event.request.content),
		chat = chat.copy(input = "", messages = chat.messages + event.request, lastError = null),
		notice = null,
	)
	is StudioEvent.AgentEventReceived -> applyingAgentRun(agentRun?.applying(event.event))
	is StudioEvent.AgentEventsReplayed -> applyingAgentRun(agentRun?.takeIf { it.runId == event.runId }?.replaying(event.events, event.status, event.nextSeq))
	is StudioEvent.AgentRunFailed -> copy(chat = chat.copy(lastError = event.message), notice = event.message)
	StudioEvent.CancelAgentRun, StudioEvent.SteerAgentRun -> this
	is StudioEvent.AgentSteered -> copy(chat = chat.copy(input = "", messages = chat.messages + event.message))
	StudioEvent.DismissAgentRun -> if (agentRun?.status?.isTerminal != false) copy(agentRun = null) else this
	is StudioEvent.GenerateTest -> copy(
		section = StudioSection.GeneratedTest,
		generatedTest = GeneratedTestState(
			event.source,
			stepScreenshots = event.stepScreenshots,
			testName = (event.source as? GenerationSource.Session)?.title ?: test.name,
		),
	)
	is StudioEvent.TestGenerated -> copy(generatedTest = generatedTest?.copy(
		loading = false,
		generated = event.generated,
		problem = event.problem,
		test = event.test,
		selectedOperationId = generatedTest.selectedOperationId?.takeIf { id -> event.test.compiledPlan?.toolOperations?.any { it.id == id } == true }
			?: event.test.compiledPlan?.toolOperations?.firstOrNull()?.id,
	))
	is StudioEvent.TestGenerationFailed -> copy(generatedTest = generatedTest?.copy(loading = false), notice = event.message)
	is StudioEvent.SelectGeneratedOperation -> copy(generatedTest = generatedTest?.copy(selectedOperationId = event.operationId))
	is StudioEvent.ChangeGeneratedTestLayout -> copy(generatedTest = generatedTest?.copy(
		operationsSplit = coerceSplit(event.operationsSplit ?: generatedTest.operationsSplit, SplitPaneDefaults.MIN_SPLIT, SplitPaneDefaults.MAX_SPLIT),
		sourceSplit = coerceSplit(event.sourceSplit ?: generatedTest.sourceSplit, SplitPaneDefaults.MIN_SPLIT, SplitPaneDefaults.MAX_SPLIT),
	))
	is StudioEvent.ChangeGeneratedTestOptions -> copy(generatedTest = generatedTest?.copy(
		uiToolkit = event.uiToolkit ?: generatedTest.uiToolkit,
		testName = event.testName ?: generatedTest.testName,
	))
	is StudioEvent.RegenerateTest -> copy(generatedTest = generatedTest?.copy(loading = true, allowIncomplete = event.allowIncomplete))
	StudioEvent.SaveGeneratedTest -> this
}

/**
 * Updates the agent run and what follows from it: an approval prompt while a destructive call waits, and the
 * run's summary added to the conversation when it ends.
 */
private fun StudioState.applyingAgentRun(updated: AgentRunState?): StudioState {
	val previous = agentRun
	if (updated == null || updated == previous) return this
	var next = copy(agentRun = updated)
	val waiting = updated.pendingApprovalCallId
	if (waiting != null && waiting != previous?.pendingApprovalCallId) {
		val call = updated.events.lastOrNull { it.kind == AgentEventKind.ApprovalRequired && it.callId == waiting }
		val arguments = call?.arguments.orEmpty().entries.joinToString("\n") { "${it.key} = ${it.value}" }
		next = next.copy(pendingApproval = PendingApproval(
			"Allow ${call?.tool ?: "this action"}?",
			listOfNotNull(call?.text, arguments.takeIf(String::isNotEmpty)).joinToString("\n\n"),
			ApprovedAction.AgentTool(updated.runId, waiting),
			confirmLabel = "Allow",
		))
	} else if (waiting == null && (pendingApproval?.action as? ApprovedAction.AgentTool)?.runId == updated.runId) {
		next = next.copy(pendingApproval = null)
	}
	if (updated.status.isTerminal && previous?.status?.isTerminal != true) {
		updated.summary?.let { summary -> next = next.copy(chat = next.chat.copy(messages = next.chat.messages + ChatMessage("agent-${updated.runId}", ChatRole.Assistant, summary))) }
	}
	return next
}

const val MIN_WORKBENCH_SPLIT = 0.3f
const val MAX_WORKBENCH_SPLIT = 0.8f

private fun nextStepId(steps: List<TestStep>): Int = (steps.mapNotNull { it.id.removePrefix("step-").toIntOrNull() }.maxOrNull() ?: 0) + 1
private fun nextOperationId(operations: List<ToolOperation>): Int = (operations.mapNotNull { it.id.removePrefix("operation-").toIntOrNull() }.maxOrNull() ?: 0) + 1

sealed interface ConnectionState {
	data object Starting : ConnectionState
	data class Ready(val version: String, val protocolVersion: Int, val capabilities: List<String>) : ConnectionState
	data class Unavailable(val reason: String, val canInstallAmoo: Boolean = false) : ConnectionState
}

const val STUDIO_PROTOCOL_VERSION: Int = 1

fun connectionFromHandshake(version: String, protocolVersion: Int, capabilities: List<String>): ConnectionState =
	if (protocolVersion == STUDIO_PROTOCOL_VERSION) {
		ConnectionState.Ready(version, protocolVersion, capabilities)
	} else {
		ConnectionState.Unavailable(
			"Amoo uses Studio protocol $protocolVersion, but this Studio supports protocol $STUDIO_PROTOCOL_VERSION. Update Amoo Studio and Amoo to compatible versions.",
		)
	}

val HostPlatform.defaultTestPlatform: TestPlatform
	get() = if (this == HostPlatform.Linux) TestPlatform.Android else TestPlatform.Ios

fun HostPlatform.supports(platform: TestPlatform): Boolean = when (this) {
	HostPlatform.MacOS -> true
	HostPlatform.Linux -> platform == TestPlatform.Android
	HostPlatform.Unsupported -> false
}

fun ConnectionState.supports(capability: String): Boolean =
	this is ConnectionState.Ready && capability in capabilities

data class ParsedToolCommand(val tool: String, val arguments: Map<String, String>)

/**
 * Parses a console line into a plan operation, or returns null when it is invalid or not a tool a plan
 * can contain. The tool is renamed to its plan vocabulary, so `assert_absent` records as `assert_not_visible`.
 */
fun parseToolCommand(command: String, catalog: ToolCatalog): ParsedToolCommand? {
	val parse = ReplGrammar(catalog).parse(command)
	val tool = parse.command as? ReplCommand.Tool ?: return null
	if (!parse.isValid) return null
	val planTool = catalog.tool(tool.name)?.planTool ?: return null
	return ParsedToolCommand(planTool, tool.arguments)
}

/** The console line parsed against the loaded catalog. */
fun StudioState.consoleParse(): ReplParse = ReplGrammar(toolCatalog).parse(console.input)
