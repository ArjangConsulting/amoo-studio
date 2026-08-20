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
	val providers: List<ProviderProfile> = defaultProviders(),
	val selectedProviderId: String? = null,
	val notice: String? = null,
	val devices: List<StudioDevice> = emptyList(),
	val selectedDeviceId: String? = null,
	val deviceOperation: DeviceOperation = DeviceOperation.Idle,
	val projectPath: String = "",
	val appId: String = "",
	val schemeOrModule: String = "",
	val lastBuildArtifact: String? = null,
	val pendingApproval: PendingApproval? = null,
)

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

enum class HostPlatform(val label: String) {
	MacOS("macOS"),
	Linux("Linux"),
	Unsupported("Unsupported host"),
}

enum class StudioSection(val title: String) { Overview("Overview"), Tests("Tests"), Devices("Devices"), Chat("AI testing"), Reports("Reports"), Settings("Settings") }

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
@Serializable data class CompiledToolPlan(val compiler: String, val compilerVersion: String, val operations: List<String>)
@Serializable enum class TestPlatform(val label: String) { Ios("iOS"), Android("Android") }

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
	data class SaveProvider(val profile: ProviderProfile) : StudioEvent
	data class RemoveProvider(val id: String) : StudioEvent
	data class SelectProvider(val id: String) : StudioEvent
	data class ShowNotice(val message: String?) : StudioEvent
	data object CopyMcpConfiguration : StudioEvent
	data object RefreshDevices : StudioEvent
	data class DevicesLoaded(val devices: List<StudioDevice>) : StudioEvent
	data class SelectDevice(val id: String) : StudioEvent
	data class StartDevice(val id: String) : StudioEvent
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
	is StudioEvent.SaveProvider -> copy(providers = providers.filterNot { it.id == event.profile.id } + event.profile, selectedProviderId = event.profile.id, notice = "Provider saved")
	is StudioEvent.RemoveProvider -> copy(providers = providers.filterNot { it.id == event.id }, selectedProviderId = selectedProviderId.takeUnless { it == event.id })
	is StudioEvent.SelectProvider -> copy(selectedProviderId = event.id)
	is StudioEvent.ShowNotice -> copy(notice = event.message)
	StudioEvent.RefreshDevices -> copy(deviceOperation = DeviceOperation.Working("Discovering devices…"))
	is StudioEvent.DevicesLoaded -> copy(devices = event.devices, deviceOperation = DeviceOperation.Idle, selectedDeviceId = selectedDeviceId?.takeIf { id -> event.devices.any { it.id == id } })
	is StudioEvent.SelectDevice -> copy(selectedDeviceId = event.id)
	is StudioEvent.StartDevice -> copy(selectedDeviceId = event.id, deviceOperation = DeviceOperation.Working("Starting device…"))
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

sealed interface ConnectionState {
	data object Starting : ConnectionState
	data class Ready(val version: String, val protocolVersion: Int, val capabilities: List<String>) : ConnectionState
	data class Unavailable(val reason: String) : ConnectionState
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
