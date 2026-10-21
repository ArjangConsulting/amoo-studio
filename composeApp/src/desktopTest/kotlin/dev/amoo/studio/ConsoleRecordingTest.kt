package dev.amoo.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleRecordingTest {
	private val device = StudioDevice("sim-1", "iPhone", TestPlatform.Ios, "26", DeviceStatus.Running)
	private val ready = StudioState(
		connection = ConnectionState.Ready("1", 1, listOf(REPL_RECORDING_CAPABILITY, TESTS_GENERATE_CAPABILITY)),
		devices = listOf(device),
		selectedDeviceId = device.id,
		appId = "dev.app",
	)
	private val screenshot = ScreenshotRef("/s.png")

	private fun recording() = ready
		.reduce(StudioEvent.StartConsoleRecording)
		.reduce(StudioEvent.ConsoleRecordingStarted("s-1", 0))

	private fun entry(id: String, actionIndex: Int?, sessionId: String? = "s-1") =
		ConsoleEntry(id, "tap_element id=$id", "Tapped", screenshot = screenshot, gesturePoint = GesturePoint(1.0, 2.0), actionIndex = actionIndex, sessionId = sessionId)

	@Test
	fun `recording needs the capability, a running device, and an app`() {
		assertEquals(ConsoleRecordingStatus.Starting, ready.reduce(StudioEvent.StartConsoleRecording).console.recording?.status)
		assertEquals("dev.app", ready.reduce(StudioEvent.StartConsoleRecording).console.recording?.appId)

		val unsupported = ready.copy(connection = ConnectionState.Ready("1", 1, emptyList())).reduce(StudioEvent.StartConsoleRecording)
		assertNull(unsupported.console.recording)
		assertTrue(unsupported.notice!!.contains("Update Amoo"))
		assertNull(ready.copy(selectedDeviceId = null).reduce(StudioEvent.StartConsoleRecording).console.recording)
		assertNull(ready.copy(appId = "").reduce(StudioEvent.StartConsoleRecording).console.recording)
	}

	@Test
	fun `recorded commands count as steps and keep their screenshots`() {
		val state = recording()
			.reduce(StudioEvent.ConsoleCommandFinished(entry("a", 0)))
			.reduce(StudioEvent.ConsoleCommandFinished(entry("devices", null)))
			.reduce(StudioEvent.ConsoleCommandFinished(entry("other", 5, sessionId = "s-2")))
			.reduce(StudioEvent.ConsoleCommandFinished(entry("b", 1)))

		val recording = state.console.recording!!
		assertEquals(ConsoleRecordingStatus.Recording, recording.status)
		assertEquals(2, recording.actionCount)
		assertEquals(setOf("step-0", "step-1"), recording.stepScreenshots.keys)
		assertEquals(GesturePoint(1.0, 2.0), recording.stepScreenshots["step-1"]?.gesturePoint)

		val cleared = state.reduce(StudioEvent.ClearConsole)
		assertTrue(cleared.console.entries.isEmpty())
		assertEquals(recording, cleared.console.recording)
	}

	@Test
	fun `stopping ends the recording, and generating opens the session with its screenshots`() {
		val stopping = recording().reduce(StudioEvent.ConsoleCommandFinished(entry("a", 0))).reduce(StudioEvent.StopConsoleRecording)
		assertEquals(ConsoleRecordingStatus.Stopping, stopping.console.recording?.status)

		val stopped = stopping.reduce(StudioEvent.ConsoleRecordingStopped(1, "/plan.json", null))
		assertEquals(ConsoleRecordingStatus.Stopped, stopped.console.recording?.status)
		assertEquals("/plan.json", stopped.console.recording?.planPath)

		val generating = stopped.reduce(StudioEvent.GenerateConsoleTest)
		assertEquals(StudioSection.GeneratedTest, generating.section)
		val source = assertIs<GenerationSource.Session>(generating.generatedTest?.source)
		assertEquals("s-1", source.sessionId)
		assertEquals(screenshot, generating.generatedTest?.stepScreenshots?.get("step-0")?.screenshot)

		assertNull(stopped.reduce(StudioEvent.DismissConsoleRecording).console.recording)
	}

	@Test
	fun `generating and dismissing wait for the recording to stop`() {
		val state = recording()

		assertNull(state.reduce(StudioEvent.GenerateConsoleTest).generatedTest)
		assertEquals(state, state.reduce(StudioEvent.DismissConsoleRecording))
		assertEquals(state, state.reduce(StudioEvent.StartConsoleRecording))
	}

	@Test
	fun `a failed start clears the recording, and a failed stop keeps recording`() {
		val failedStart = ready.reduce(StudioEvent.StartConsoleRecording).reduce(StudioEvent.ConsoleRecordingFailed("Could not start recording: busy"))
		assertNull(failedStart.console.recording)
		assertEquals("Could not start recording: busy", failedStart.notice)

		val failedStop = recording().reduce(StudioEvent.StopConsoleRecording).reduce(StudioEvent.ConsoleRecordingFailed("Could not stop"))
		assertEquals(ConsoleRecordingStatus.Recording, failedStop.console.recording?.status)
	}

	@Test
	fun `Amoo exiting ends an active recording but keeps its session for generation`() {
		assertNull(ready.reduce(StudioEvent.StartConsoleRecording).reduce(StudioEvent.ConsoleRecordingInterrupted).console.recording)

		val interrupted = recording().reduce(StudioEvent.ConsoleRecordingInterrupted).console.recording!!
		assertEquals(ConsoleRecordingStatus.Stopped, interrupted.status)
		assertEquals("s-1", interrupted.sessionId)
		assertTrue(interrupted.message!!.contains("saved"))

		assertEquals(ready, ready.reduce(StudioEvent.ConsoleRecordingInterrupted))
	}

	@Test
	fun `a late start result does not revive a recording that already ended`() {
		val state = ready.reduce(StudioEvent.StartConsoleRecording).reduce(StudioEvent.ConsoleRecordingInterrupted)

		assertNull(state.reduce(StudioEvent.ConsoleRecordingStarted("s-1", 0)).console.recording)
	}
}
