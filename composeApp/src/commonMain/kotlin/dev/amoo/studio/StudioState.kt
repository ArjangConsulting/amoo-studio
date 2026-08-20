package dev.amoo.studio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

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
	val lastSessionId: String? = null,
	val lastReportId: String? = null,
	val reports: List<TestReport> = emptyList(),
	val selectedReportId: String? = null,
	val reportsLoading: Boolean = false,
	val providers: List<ProviderProfile> = defaultProviders(),
	val selectedProviderId: String? = null,
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
)

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

enum class StudioSection(val title: String) { Overview("Overview"), Tests("Tests"), Devices("Devices"), Chat("AI testing"), Console("Console"), Reports("Reports"), Settings("Settings") }

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
)

@Serializable data class ToolOperation(val id: String, val tool: String, val arguments: Map<String, String> = emptyMap())
data class ToolOperationDraft(val tool: String = TOOL_CATALOG.first().name, val arguments: Map<String, String> = emptyMap())
data class ToolDefinition(val name: String, val description: String, val arguments: List<ToolArgument> = emptyList())
data class ToolArgument(val name: String, val label: String, val required: Boolean = false, val placeholder: String = "")

val TOOL_CATALOG = listOf(
	ToolDefinition("tap_element", "Tap a control by accessibility identity", listOf(ToolArgument("id", "Accessibility ID"), ToolArgument("label", "Label"), ToolArgument("contains_text", "Contains text"))),
	ToolDefinition("set_text", "Focus, clear, and fill a text field", listOf(ToolArgument("id", "Accessibility ID"), ToolArgument("label", "Label"), ToolArgument("value", "Value", required = true))),
	ToolDefinition("type_text", "Type into the focused field", listOf(ToolArgument("text", "Text", required = true))),
	ToolDefinition("swipe_in_direction", "Swipe from the screen center", listOf(ToolArgument("direction", "Direction", required = true, placeholder = "up, down, left, right"), ToolArgument("distance", "Distance", placeholder = "300"))),
	ToolDefinition("wait_for_element", "Wait until an element appears", listOf(ToolArgument("id", "Accessibility ID"), ToolArgument("label", "Label"), ToolArgument("timeout_ms", "Timeout (ms)", placeholder = "5000"))),
	ToolDefinition("assert_visible", "Require an element to be visible", listOf(ToolArgument("id", "Accessibility ID"), ToolArgument("label", "Label"), ToolArgument("contains_text", "Contains text"))),
	ToolDefinition("assert_not_visible", "Require an element to be absent", listOf(ToolArgument("id", "Accessibility ID"), ToolArgument("label", "Label"), ToolArgument("contains_text", "Contains text"))),
	ToolDefinition("assert_text", "Require matching text", listOf(ToolArgument("expected", "Expected text", required = true), ToolArgument("id", "Accessibility ID"), ToolArgument("label", "Label"))),
	ToolDefinition("take_screenshot", "Capture a report artifact"),
	ToolDefinition("press_back", "Navigate back"),
)

fun ToolOperationDraft.validationError(): String? {
	val definition = TOOL_CATALOG.firstOrNull { it.name == tool } ?: return "Unsupported tool: $tool"
	val missing = definition.arguments.filter { it.required && arguments[it.name].isNullOrBlank() }
	if (missing.isNotEmpty()) return "Required: ${missing.joinToString { it.label }}"
	if (tool in setOf("tap_element", "wait_for_element", "assert_visible", "assert_not_visible") && listOf("id", "label", "contains_text").none { !arguments[it].isNullOrBlank() }) {
		return "Provide an accessibility ID, label, or text selector"
	}
	return null
}
@Serializable enum class TestPlatform(val label: String) { Ios("iOS"), Android("Android") }
sealed interface TestExecution { data object Idle : TestExecution; data class Running(val message: String) : TestExecution }
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
)

@Serializable enum class ProviderKind(val label: String) { OpenAI("OpenAI"), Anthropic("Claude / Anthropic"), Ollama("Ollama"), Custom("OpenAI-compatible") }

@Immutable
data class ChatState(
	val input: String = "",
	val messages: List<ChatMessage> = emptyList(),
	val operation: ChatOperation = ChatOperation.Idle,
)

@Serializable data class ChatMessage(val id: String, val role: ChatRole, val content: String)
@Serializable enum class ChatRole { User, Assistant }
sealed interface ChatOperation { data object Idle : ChatOperation; data object Sending : ChatOperation }

@Immutable
data class ConsoleState(
	val input: String = "",
	val entries: List<ConsoleEntry> = emptyList(),
	val operation: ConsoleOperation = ConsoleOperation.Idle,
)

@Serializable data class ConsoleEntry(val id: String, val command: String, val output: String, val failed: Boolean = false)
data class ConsoleSuggestion(val command: String, val description: String)
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
data class PendingApproval(val title: String, val message: String, val action: ApprovedAction)
sealed interface ApprovedAction { data object ResetAppData : ApprovedAction }

fun defaultProviders() = listOf(ProviderProfile("ollama", "Local Ollama", ProviderKind.Ollama, "http://localhost:11434", "qwen3.8:27b-mlx"))

sealed interface StudioEvent {
	data class SelectSection(val section: StudioSection) : StudioEvent
	data class ChangeThemeMode(val value: ThemeMode) : StudioEvent
	data object RetryConnection : StudioEvent
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
	data class TestRunStarted(val message: String) : StudioEvent
	data class TestRunFinished(val message: String, val sessionId: String?, val reportId: String?) : StudioEvent
	data class TestRunFailed(val message: String) : StudioEvent
	data object RefreshReports : StudioEvent
	data class ReportsLoaded(val reports: List<TestReport>) : StudioEvent
	data class ReportsFailed(val message: String) : StudioEvent
	data class SelectReport(val id: String) : StudioEvent
	data class SaveProvider(val profile: ProviderProfile) : StudioEvent
	data class RemoveProvider(val id: String) : StudioEvent
	data class SelectProvider(val id: String) : StudioEvent
	data class ChangeChatInput(val value: String) : StudioEvent
	data object SendChat : StudioEvent
	data class ChatRequestStarted(val message: ChatMessage) : StudioEvent
	data class ChatResponseReceived(val message: ChatMessage) : StudioEvent
	data class ChatRequestFailed(val message: String) : StudioEvent
	data object CancelChat : StudioEvent
	data object ClearChat : StudioEvent
	data class ChangeConsoleInput(val value: String) : StudioEvent
	data class ChooseConsoleSuggestion(val command: String) : StudioEvent
	data object ExecuteConsoleCommand : StudioEvent
	data object ConsoleCommandStarted : StudioEvent
	data class ConsoleCommandFinished(val entry: ConsoleEntry) : StudioEvent
	data object CancelConsoleCommand : StudioEvent
	data object ClearConsole : StudioEvent
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
}

fun StudioState.reduce(event: StudioEvent): StudioState = when (event) {
	is StudioEvent.SelectSection -> copy(section = event.section)
	is StudioEvent.ChangeThemeMode -> copy(themeMode = event.value)
	StudioEvent.RetryConnection -> copy(connection = ConnectionState.Starting, notice = null)
	StudioEvent.NewTest -> copy(test = AmooTest(platform = hostPlatform.defaultTestPlatform), testPath = null, isTestDirty = false, section = StudioSection.Tests)
	StudioEvent.OpenTest, StudioEvent.SaveTest, StudioEvent.SaveTestAs, StudioEvent.CopyMcpConfiguration -> this
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
		if (command.isEmpty()) this else copy(
			test = test.copy(compiledPlan = (test.compiledPlan ?: CompiledToolPlan("studio-console", "1", emptyList())).let { it.copy(operations = it.operations + command) }),
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
	StudioEvent.AddToolOperation -> if (operationDraft.validationError() != null) this else copy(
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
	is StudioEvent.TestRunStarted -> copy(testExecution = TestExecution.Running(event.message), notice = null)
	is StudioEvent.TestRunFinished -> copy(testExecution = TestExecution.Idle, notice = event.message, lastSessionId = event.sessionId ?: lastSessionId, lastReportId = event.reportId ?: lastReportId)
	is StudioEvent.TestRunFailed -> copy(testExecution = TestExecution.Idle, notice = event.message)
	StudioEvent.RefreshReports -> copy(reportsLoading = true, notice = null)
	is StudioEvent.ReportsLoaded -> copy(reports = event.reports, reportsLoading = false, selectedReportId = selectedReportId?.takeIf { id -> event.reports.any { it.id == id } } ?: event.reports.firstOrNull()?.id)
	is StudioEvent.ReportsFailed -> copy(reportsLoading = false, notice = event.message)
	is StudioEvent.SelectReport -> copy(selectedReportId = event.id)
	is StudioEvent.SaveProvider -> copy(providers = providers.filterNot { it.id == event.profile.id } + event.profile, selectedProviderId = event.profile.id, notice = "Provider saved")
	is StudioEvent.RemoveProvider -> copy(providers = providers.filterNot { it.id == event.id }, selectedProviderId = selectedProviderId.takeUnless { it == event.id })
	is StudioEvent.SelectProvider -> copy(selectedProviderId = event.id)
	is StudioEvent.ChangeChatInput -> copy(chat = chat.copy(input = event.value))
	StudioEvent.SendChat -> this
	is StudioEvent.ChatRequestStarted -> copy(chat = chat.copy(input = "", messages = chat.messages + event.message, operation = ChatOperation.Sending), notice = null)
	is StudioEvent.ChatResponseReceived -> copy(chat = chat.copy(messages = chat.messages + event.message, operation = ChatOperation.Idle))
	is StudioEvent.ChatRequestFailed -> copy(chat = chat.copy(operation = ChatOperation.Idle), notice = event.message)
	StudioEvent.CancelChat -> copy(chat = chat.copy(operation = ChatOperation.Idle), notice = "AI request cancelled")
	StudioEvent.ClearChat -> copy(chat = ChatState())
	is StudioEvent.ChangeConsoleInput -> copy(console = console.copy(input = event.value))
	is StudioEvent.ChooseConsoleSuggestion -> copy(console = console.copy(input = event.command))
	StudioEvent.ExecuteConsoleCommand -> this
	StudioEvent.ConsoleCommandStarted -> copy(console = console.copy(operation = ConsoleOperation.Running), notice = null)
	is StudioEvent.ConsoleCommandFinished -> copy(console = console.copy(input = "", entries = console.entries + event.entry, operation = ConsoleOperation.Idle))
	StudioEvent.CancelConsoleCommand -> copy(console = console.copy(operation = ConsoleOperation.Idle), notice = "Command cancelled")
	StudioEvent.ClearConsole -> copy(console = ConsoleState())
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
	StudioEvent.RequestResetAppData -> copy(pendingApproval = PendingApproval("Erase app data?", "This removes all local data for $appId on the selected device. The action cannot be undone.", ApprovedAction.ResetAppData))
	is StudioEvent.ResolveApproval -> copy(pendingApproval = null)
	is StudioEvent.DeviceOperationStarted -> copy(deviceOperation = DeviceOperation.Working(event.message), notice = null)
	is StudioEvent.DeviceOperationFinished -> copy(deviceOperation = DeviceOperation.Idle, notice = event.message, lastBuildArtifact = event.artifact ?: lastBuildArtifact)
}

private fun nextStepId(steps: List<TestStep>): Int = (steps.mapNotNull { it.id.removePrefix("step-").toIntOrNull() }.maxOrNull() ?: 0) + 1
private fun nextOperationId(operations: List<ToolOperation>): Int = (operations.mapNotNull { it.id.removePrefix("operation-").toIntOrNull() }.maxOrNull() ?: 0) + 1

sealed interface ConnectionState {
	data object Starting : ConnectionState
	data class Ready(val version: String, val protocolVersion: Int, val capabilities: List<String>) : ConnectionState
	data class Unavailable(val reason: String) : ConnectionState
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

fun StudioState.consoleSuggestions(): List<ConsoleSuggestion> {
	val catalog = buildList {
		add(ConsoleSuggestion("help", "Show commands supported by the connected Amoo engine"))
		add(ConsoleSuggestion("devices list", "Discover simulators, emulators, and physical devices"))
		devices.forEach { device -> add(ConsoleSuggestion("devices inspect ${device.id}", "Inspect ${device.name}")) }
		selectedDeviceId?.let { id -> add(ConsoleSuggestion("devices inspect $id", "Inspect the selected device")) }
		add(ConsoleSuggestion("tests validate", "Validate the active test: ${test.name}"))
		add(ConsoleSuggestion("tests run", "Run the active test on the selected device"))
		add(ConsoleSuggestion("sessions list", "List recent Amoo sessions"))
		add(ConsoleSuggestion("reports list", "List available reports"))
		selectedProviderId?.let { add(ConsoleSuggestion("providers inspect $it", "Inspect the selected AI provider profile")) }
	}
	val terms = console.input.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
	return catalog.distinctBy { it.command }.filter { suggestion -> terms.all { it in suggestion.command.lowercase() || it in suggestion.description.lowercase() } }.take(8)
}
