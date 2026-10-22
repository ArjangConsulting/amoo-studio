package dev.amoo.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders each workbench screen with realistic state, so a composition failure or missing control fails here. */
@OptIn(ExperimentalTestApi::class)
class StudioScreensUiTest {
	private val device = StudioDevice("sim-1", "iPhone 17", TestPlatform.Ios, "26", DeviceStatus.Running)
	private val ready = ConnectionState.Ready("1", STUDIO_PROTOCOL_VERSION, listOf("repl.execute", "chat.send", AGENT_RUNS_CAPABILITY, TESTS_GENERATE_CAPABILITY))

	@Composable private fun Host(content: @Composable () -> Unit) {
		MaterialTheme { CompositionLocalProvider(LocalScreenshotLoader provides ScreenshotLoader.None) { content() } }
	}

	@Test
	fun consoleShowsTheEditorHistoryAndStructuredDetails() = runComposeUiTest {
		val state = StudioState(
			connection = ready,
			section = StudioSection.Console,
			devices = listOf(device),
			selectedDeviceId = device.id,
			console = ConsoleState(entries = listOf(ConsoleEntry("e1", "find_elements", "Found 1 element", structuredContent = """{"total":1}"""))),
		)
		setContent { Host { ConsoleContent(state) {} } }

		onNodeWithContentDescription("Amoo command").assertExists()
		onNodeWithText("Found 1 element").assertExists()
		onNodeWithText("Show details").performClick()
		onNodeWithText("Hide details").assertExists()
		onNodeWithContentDescription("Resize panes").assertExists()
	}

	@Test
	fun theConsoleRecordsCommandsAndGeneratesATestFromThem() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		var state by mutableStateOf(StudioState(
			connection = ConnectionState.Ready("1", STUDIO_PROTOCOL_VERSION, listOf("repl.execute", REPL_RECORDING_CAPABILITY, TESTS_GENERATE_CAPABILITY)),
			devices = listOf(device),
			selectedDeviceId = device.id,
			appId = "dev.app",
		))
		setContent { Host { ConsoleContent(state) { events += it } } }

		onNodeWithText("Record").assertIsEnabled().performClick()
		assertTrue(StudioEvent.StartConsoleRecording in events)

		state = state.reduce(StudioEvent.StartConsoleRecording).reduce(StudioEvent.ConsoleRecordingStarted("s-1", 0))
			.reduce(StudioEvent.ConsoleCommandFinished(ConsoleEntry("e1", "tap_element id=login", "Tapped", actionIndex = 0, sessionId = "s-1")))
		onNodeWithText("Recording on iPhone 17 · 1 step").assertExists()
		onNodeWithText("Recorded as step 1").assertExists()
		onNodeWithText("Stop recording").performClick()
		assertTrue(StudioEvent.StopConsoleRecording in events)

		state = state.reduce(StudioEvent.StopConsoleRecording).reduce(StudioEvent.ConsoleRecordingStopped(1, "/plan.json", null))
		onNodeWithText("Recorded 1 step on iPhone 17").assertExists()
		onNodeWithText("Generate test").assertIsEnabled().performClick()
		assertTrue(StudioEvent.GenerateConsoleTest in events)
	}

	@Test
	fun theConsoleHidesRecordingFromAmooBuildsWithoutIt() = runComposeUiTest {
		setContent { Host { ConsoleContent(StudioState(connection = ready, devices = listOf(device), selectedDeviceId = device.id, appId = "dev.app")) {} } }

		onNodeWithText("Record").assertDoesNotExist()
	}

	@Test
	fun aiTestingOffersApprovalAndGenerationForAnAgentRun() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		val run = AgentRunState("run-1", "Sign in")
			.applying(AgentEvent("run-1", 0, AgentEventKind.SessionStarted, sessionId = "s-1"))
			.applying(AgentEvent("run-1", 1, AgentEventKind.ToolCall, callId = "c1", tool = "device_uninstall_app", arguments = mapOf("app_id" to "dev.app")))
			.applying(AgentEvent("run-1", 2, AgentEventKind.ApprovalRequired, callId = "c1", tool = "device_uninstall_app"))
		val waiting = StudioState(connection = ready, devices = listOf(device), selectedDeviceId = device.id, appId = "dev.app")
			.reduce(StudioEvent.AgentRunStarted("run-1", ChatMessage("u", ChatRole.User, "Sign in")))
			.let { start -> run.events.fold(start) { state, event -> state.reduce(StudioEvent.AgentEventReceived(event)) } }
		setContent { Host { AiTestingContent(waiting) { events += it } } }

		onNodeWithText("device_uninstall_app").assertExists()
		onNodeWithText("Allow").assertIsEnabled().performClick()
		assertTrue(StudioEvent.ResolveApproval(true) in events)
		onNodeWithText("Stop").assertExists()
		onNodeWithText("Waiting for your approval").assertExists()
	}

	@Test
	fun aFinishedRunCanGenerateATest() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		val finished = listOf(
			AgentEvent("run-1", 0, AgentEventKind.SessionStarted, sessionId = "s-1"),
			AgentEvent("run-1", 1, AgentEventKind.AssistantMessage, text = "Signed in."),
			AgentEvent("run-1", 2, AgentEventKind.Finished, state = AgentRunStatus.Completed, sessionId = "s-1"),
		).fold(StudioState(connection = ready).reduce(StudioEvent.AgentRunStarted("run-1", ChatMessage("u", ChatRole.User, "Sign in")))) { state, event ->
			state.reduce(StudioEvent.AgentEventReceived(event))
		}
		setContent { Host { AiTestingContent(finished) { events += it } } }

		onNodeWithText("Generate test").performClick()
		assertTrue(events.any { it is StudioEvent.GenerateTest && (it.source as? GenerationSource.Session)?.sessionId == "s-1" })
		onNodeWithText("Dismiss run").assertExists()
	}

	@Test
	fun aiTestingExplainsWhatTheAgentNeeds() = runComposeUiTest {
		setContent { Host { AiTestingContent(StudioState(connection = ready, devices = listOf(device), selectedDeviceId = device.id)) {} } }

		onNodeWithText("Set the app's bundle or application ID in Devices so the agent can launch it on iPhone 17.").assertExists()
	}

	@Test
	fun generatedTestShowsStepsSourceAndProblems() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		val generated = GeneratedTestState(
			GenerationSource.Session("s-1", "Sign in"),
			loading = false,
			generated = GeneratedSource("SignInTest.swift", "import XCTest\n\nfinal class SignInTest {}\n", "swift", listOf(SourceMapEntry(0, "step-0", 3, 3))),
			test = AmooTest(compiledPlan = CompiledToolPlan("session", "1", toolOperations = listOf(ToolOperation("step-0", "tap_element", mapOf("id" to "login")), ToolOperation("step-1", "assert_visible")))),
			selectedOperationId = "step-0",
		)
		setContent { Host { GeneratedTestContent(StudioState(connection = ready, generatedTest = generated)) { events += it } } }

		onNodeWithText("SignInTest.swift").assertExists()
		onNodeWithContentDescription("Generated test source").assertExists()
		onNodeWithText("Not in the generated code").assertExists()
		onNodeWithText("assert_visible").performClick()
		assertTrue(StudioEvent.SelectGeneratedOperation("step-1") in events)
	}

	@Test
	fun anIncompletePlanOffersToGenerateAnyway() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		val generated = GeneratedTestState(GenerationSource.AuthoredTest, loading = false, problem = GenerationProblem(GenerationProblemKind.Incomplete, "1 step could not be compiled"))
		setContent { Host { GeneratedTestContent(StudioState(connection = ready, generatedTest = generated)) { events += it } } }

		onNodeWithText("Generate anyway").performClick()
		assertTrue(StudioEvent.RegenerateTest(allowIncomplete = true) in events)
	}

	@Test
	fun theDevicePaneExplainsEachState() = runComposeUiTest {
		setContent { Host { DevicePane(DeviceMirrorState(status = MirrorStatus.Unavailable("Live view needs a newer Amoo.")), device, {}) } }

		assertTrue(onAllNodesWithText("Live view needs a newer Amoo.").fetchSemanticsNodes().isNotEmpty())
		onNodeWithText("Pause").assertExists()
	}
}
