package dev.amoo.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeContextRevisionTest {
	@Test
	fun runtimeTransitionsInvalidateSuggestionsEvenWhenTheCommandDoesNotChange() {
		val base = StudioState(console = ConsoleState(input = "tap_element id="), selectedDeviceId = "a")
		val events = listOf(
			StudioEvent.ConsoleCommandStarted,
			StudioEvent.CancelConsoleCommand,
			StudioEvent.SelectDevice("b"),
			StudioEvent.DevicesLoaded(listOf(StudioDevice("a", "Phone", TestPlatform.Android, "15", DeviceStatus.Running))),
			StudioEvent.ChangeAppId("dev.app"),
			StudioEvent.DeviceOperationStarted("Resetting app data"),
			StudioEvent.DeviceOperationFinished("Reset complete"),
			StudioEvent.DeviceOperationFailed("Reset failed after changing the device"),
			StudioEvent.TestRunStarted("Running"),
			StudioEvent.TestRunProgress("run", "Step 2", 2, 3),
			StudioEvent.TestRunFinished("Done", null, null),
			StudioEvent.TestRunFailed("Failed"),
			StudioEvent.CancelTestRun,
			StudioEvent.RetryConnection,
			StudioEvent.ConnectionChanged(ConnectionState.Starting),
		)
		for (event in events) {
			val updated = base.reduce(event)
			assertEquals(base.runtimeContextRevision + 1, updated.runtimeContextRevision, event.toString())
			assertEquals(base.console.input, updated.console.input, event.toString())
		}
	}

	@Test
	fun completionAndRecordingResultsAdvanceTheRevision() {
		val base = StudioState(console = ConsoleState(input = "tap_element id="))
		assertEquals(1L, base.reduce(StudioEvent.ConsoleCommandFinished(ConsoleEntry("e", "tap_element id=x", "Tapped"))).runtimeContextRevision)
		val device = StudioDevice("a", "Phone", TestPlatform.Android, "15", DeviceStatus.Running)
		val recording = base.copy(console = base.console.copy(recording = ConsoleRecording(ConsoleRecordingStatus.Starting, device, "dev.app")))
		assertEquals(1L, recording.reduce(StudioEvent.ConsoleRecordingStarted("s", 0)).runtimeContextRevision)
	}

	@Test
	fun agentToolResultsInvalidateButDuplicateEventsAndTextDeltasDoNot() {
		val base = StudioState(console = ConsoleState(input = "tap_element id="))
			.reduce(StudioEvent.AgentRunStarted("run", ChatMessage("u", ChatRole.User, "Explore")))
		val call = StudioEvent.AgentEventReceived(AgentEvent("run", 0, AgentEventKind.ToolCall, callId = "call", tool = "tap_element"))
		val running = base.reduce(call)
		val result = running.reduce(StudioEvent.AgentEventReceived(AgentEvent("run", 1, AgentEventKind.ToolResult, callId = "call", tool = "tap_element", text = "Tapped")))
		assertEquals(base.runtimeContextRevision + 1, running.runtimeContextRevision)
		assertEquals(running.runtimeContextRevision + 1, result.runtimeContextRevision)
		assertEquals(running.runtimeContextRevision, running.reduce(call).runtimeContextRevision)
		assertEquals(running.runtimeContextRevision, running.reduce(StudioEvent.AgentEventReceived(AgentEvent("run", 1, AgentEventKind.AssistantDelta, text = "Thinking"))).runtimeContextRevision)
	}

	@Test
	fun textAndPresentationChangesDoNotInvalidateTheRuntimeContext() {
		val base = StudioState()
		for (event in listOf(StudioEvent.ChangeConsoleInput("tap"), StudioEvent.SelectSection(StudioSection.Console), StudioEvent.ChangeThemeMode(ThemeMode.Dark), StudioEvent.ClearConsole, StudioEvent.ChangeWorkbenchSplit(0.5f))) {
			assertEquals(base.runtimeContextRevision, base.reduce(event).runtimeContextRevision, event.toString())
		}
		val selected = base.reduce(StudioEvent.SelectDevice("a"))
		assertEquals(selected.runtimeContextRevision, selected.reduce(StudioEvent.SelectDevice("a")).runtimeContextRevision)
	}

	@Test
	fun identicalInputEchoPreservesTheHistoryPositionUntilAnActualEdit() {
		val base = StudioState(console = ConsoleState(input = "devices list", historyIndex = 0, entries = listOf(ConsoleEntry("e", "devices list", "ok"))))
		val echoed = base.reduce(StudioEvent.ChangeConsoleInput("devices list"))
		assertEquals(base, echoed)
		assertEquals(null, echoed.reduce(StudioEvent.ChangeConsoleInput("devices list ")).console.historyIndex)
		assertTrue(echoed.reduce(StudioEvent.NavigateConsoleHistory(1)).console.input.isEmpty())
	}
}
