package dev.amoo.studio

import io.github.maniramezan.kmpcomponents.codeeditor.DiagnosticSeverity
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentAndGenerationTest {
	private val json = Json { ignoreUnknownKeys = true }
	private fun event(seq: Int, kind: AgentEventKind, runId: String = "run-1", configure: AgentEvent.() -> AgentEvent = { this }) = AgentEvent(runId, seq, kind).configure()
	private val device = StudioDevice("sim-1", "iPhone", TestPlatform.Ios, "26", DeviceStatus.Running)

	// MARK: Run state

	@Test
	fun `events apply in order, duplicates are ignored, and a gap asks for replay`() {
		val run = AgentRunState("run-1", "Sign in")
			.applying(event(0, AgentEventKind.SessionStarted) { copy(sessionId = "s-1") })
			.applying(event(0, AgentEventKind.SessionStarted))
			.applying(event(2, AgentEventKind.Usage))

		assertEquals(1, run.events.size)
		assertEquals("s-1", run.sessionId)
		assertTrue(run.gap)
		assertEquals(1, run.nextSeq)
	}

	@Test
	fun `replay fills gaps, tolerates trimmed history, and takes the server's state`() {
		val run = AgentRunState("run-1", "Sign in", nextSeq = 1, gap = true)
			.replaying(listOf(event(5, AgentEventKind.Usage) { copy(inputTokens = 10) }, event(6, AgentEventKind.Finished) { copy(state = AgentRunStatus.Completed, planPath = "/p") }), AgentRunStatus.Completed, 7)

		assertFalse(run.gap)
		assertEquals(7, run.nextSeq)
		assertEquals(AgentRunStatus.Completed, run.status)
		assertEquals("/p", run.planPath)
		assertEquals(10, run.inputTokens)
	}

	@Test
	fun `streamed text accumulates until the full message arrives, and other runs are ignored`() {
		val run = AgentRunState("run-1", "x")
			.applying(event(0, AgentEventKind.AssistantDelta) { copy(text = "Hel") })
			.applying(event(0, AgentEventKind.AssistantDelta) { copy(text = "lo") })
			.applying(event(0, AgentEventKind.AssistantDelta, runId = "other") { copy(text = "!") })

		assertEquals("Hello", run.streamingText)
		assertEquals("", run.applying(event(0, AgentEventKind.AssistantMessage) { copy(text = "Hello") }).streamingText)
	}

	@Test
	fun `the timeline joins a tool call with its approval and result`() {
		val run = AgentRunState("run-1", "x")
			.applying(event(0, AgentEventKind.AssistantMessage) { copy(text = "Uninstalling") })
			.applying(event(1, AgentEventKind.ToolCall) { copy(callId = "c1", tool = "device_uninstall_app", arguments = mapOf("app_id" to "dev.app")) })
			.applying(event(2, AgentEventKind.ApprovalRequired) { copy(callId = "c1") })
			.applying(event(3, AgentEventKind.ApprovalResolved) { copy(callId = "c1", approved = true) })
			.applying(event(4, AgentEventKind.ToolResult) { copy(callId = "c1", ok = true, actionIndex = 0, screenshot = ScreenshotRef("/s.png")) })
			.applying(event(5, AgentEventKind.Finished) { copy(state = AgentRunStatus.Completed) })

		val timeline = run.timeline()
		assertEquals(3, timeline.size)
		val tool = assertIs<AgentTimelineItem.Tool>(timeline[1])
		assertEquals(AgentTimelineItem.Tool.Approval.Approved, tool.approval)
		assertEquals(true, tool.result?.ok)
		assertIs<AgentTimelineItem.Finished>(timeline[2])
		assertEquals(ScreenshotRef("/s.png"), run.stepScreenshots["step-0"]?.screenshot)
	}

	@Test
	fun `a waiting approval opens the approval dialog, and the run's summary joins the conversation`() {
		val started = StudioState().reduce(StudioEvent.AgentRunStarted("run-1", ChatMessage("u", ChatRole.User, "Uninstall it")))
		val waiting = started
			.reduce(StudioEvent.AgentEventReceived(event(0, AgentEventKind.ToolCall) { copy(callId = "c1", tool = "device_uninstall_app", arguments = mapOf("app_id" to "x")) }))
			.reduce(StudioEvent.AgentEventReceived(event(1, AgentEventKind.ApprovalRequired) { copy(callId = "c1", tool = "device_uninstall_app", arguments = mapOf("app_id" to "x"), text = "Destroys state") }))

		assertEquals(ApprovedAction.AgentTool("run-1", "c1"), waiting.pendingApproval?.action)
		assertTrue(waiting.pendingApproval!!.message.contains("app_id = x"))
		assertEquals(AgentRunStatus.AwaitingApproval, waiting.agentRun?.status)
		assertTrue(waiting.agentRunActive)

		val finished = waiting
			.reduce(StudioEvent.AgentEventReceived(event(2, AgentEventKind.ApprovalResolved) { copy(callId = "c1", approved = false) }))
			.reduce(StudioEvent.AgentEventReceived(event(3, AgentEventKind.AssistantMessage) { copy(text = "Skipped the uninstall.") }))
			.reduce(StudioEvent.AgentEventReceived(event(4, AgentEventKind.Finished) { copy(state = AgentRunStatus.Completed) }))

		assertNull(finished.pendingApproval)
		assertEquals(listOf("Uninstall it", "Skipped the uninstall."), finished.chat.messages.map { it.content })
		assertFalse(finished.agentRunActive)
		assertNull(finished.reduce(StudioEvent.DismissAgentRun).agentRun)
	}

	@Test
	fun `messages start agent runs only with a running device and a known app`() {
		val ready = StudioState(connection = ConnectionState.Ready("1", 1, listOf(AGENT_RUNS_CAPABILITY)), devices = listOf(device), selectedDeviceId = "sim-1", appId = "dev.app")

		assertEquals(AgentReadiness.Ready(device, "dev.app"), ready.agentReadiness())
		assertEquals(AgentReadiness.NoApp(device), ready.copy(appId = "").agentReadiness())
		assertEquals(AgentReadiness.Ready(device, "from.test"), ready.copy(appId = "", test = AmooTest(requirements = TestRequirements(appId = "from.test"))).agentReadiness())
		assertEquals(AgentReadiness.NoDevice, ready.copy(devices = listOf(device.copy(status = DeviceStatus.Available))).agentReadiness())
		assertEquals(AgentReadiness.Unsupported, ready.copy(connection = ConnectionState.Ready("1", 1, emptyList())).agentReadiness())
	}

	@Test
	fun `agent events decode from Amoo's JSON`() {
		val event = json.decodeFromString<AgentEvent>(
			"""{"runId":"r","seq":4,"kind":"toolResult","callId":"c","tool":"tap_element","ok":true,"actionIndex":2,"screenshot":{"path":"/s.png","widthPixels":10,"scale":3},"gesturePoint":{"x":1.5,"y":2},"state":"awaitingApproval"}""",
		)

		assertEquals(AgentEventKind.ToolResult, event.kind)
		assertEquals(2, event.actionIndex)
		assertEquals(GesturePoint(1.5, 2.0), event.gesturePoint)
		assertEquals(AgentRunStatus.AwaitingApproval, event.state)
	}

	// MARK: Generated tests

	private val generated = GeneratedSource(
		"SignInTest.swift",
		"import XCTest\n\nfinal class SignInTest {\n        app.buttons[\"login\"].tap()\n        XCTFail(\"Uncompiled required action 1 (pinch): no equivalent\")\n        waitForExistence(app.otherElements[\"home\"])\n}",
		"swift",
		listOf(SourceMapEntry(0, "step-0", 4, 4), SourceMapEntry(1, "step-2", 6, 6)),
	)

	private fun generationState() = GeneratedTestState(
		GenerationSource.Session("s-1", "Sign in"),
		loading = false,
		generated = generated,
		test = AmooTest(compiledPlan = CompiledToolPlan(
			"session", "1",
			toolOperations = listOf(ToolOperation("step-0", "tap_element", mapOf("id" to "login")), ToolOperation("step-2", "assert_visible", mapOf("id" to "home"))),
			warnings = listOf(
				PlanWarning(PlanWarningKind.Excluded, 1, "pinch", "no equivalent"),
				PlanWarning(PlanWarningKind.Approximate, 2, "assert_visible", "matched by text"),
			),
		)),
	)

	@Test
	fun `spans, reverse lookup, and warnings follow operation ids`() {
		val state = generationState()

		assertEquals(4, state.span("step-0")?.startLine)
		assertEquals("step-2", state.operationAt(6))
		assertNull(state.operationAt(5))
		assertEquals(listOf("matched by text"), state.warningsFor("step-2").map { it.reason })
	}

	@Test
	fun `warnings become diagnostics on their step's line or its XCTFail line`() {
		val diagnostics = warningDiagnostics(generationState(), generated)

		assertEquals(2, diagnostics.size)
		val excluded = diagnostics.first { it.message.startsWith("pinch") }
		assertEquals(4, excluded.range.start.line)
		assertEquals(8, excluded.range.start.character)
		assertEquals(DiagnosticSeverity.ERROR, excluded.severity)
		assertEquals(DiagnosticSeverity.WARNING, diagnostics.first { it.message.startsWith("assert_visible") }.severity)
	}

	@Test
	fun `generating selects the first step and keeps a selection that still exists`() {
		val state = StudioState().reduce(StudioEvent.GenerateTest(GenerationSource.Session("s-1", "Sign in")))
		assertEquals(StudioSection.GeneratedTest, state.section)
		assertTrue(state.generatedTest!!.loading)

		val test = generationState().test!!
		val loaded = state.reduce(StudioEvent.TestGenerated(generated, null, test))
		assertEquals("step-0", loaded.generatedTest?.selectedOperationId)

		val regenerated = loaded.reduce(StudioEvent.SelectGeneratedOperation("step-2")).reduce(StudioEvent.RegenerateTest(true)).reduce(StudioEvent.TestGenerated(generated, null, test))
		assertEquals("step-2", regenerated.generatedTest?.selectedOperationId)
		assertEquals(true, regenerated.generatedTest?.allowIncomplete)
	}

	@Test
	fun `generation results decode from Amoo's JSON`() {
		val result = json.decodeFromString<GenerationProblem>("""{"kind":"incomplete","message":"m","warnings":[{"kind":"excluded","actionIndex":3,"toolName":"pinch","reason":"r","transient":false,"category":"inspection_omitted"}]}""")
		val plan = json.decodeFromString<AmooTest>("""{"formatVersion":1,"name":"T","description":"","platform":"Ios","steps":[{"id":"step-0","instruction":"Tap","expected":"x"}],"compiledPlan":{"compiler":"session","compilerVersion":"1","operations":[],"toolOperations":[{"id":"step-0","tool":"tap_element","arguments":{"id":"a"},"helper":"h"}],"warnings":[{"kind":"approximate","actionIndex":0,"toolName":"tap_element","reason":"r"}]},"requirements":{"uiToolkit":"compose","launchArguments":[]}}""")

		assertEquals(GenerationProblemKind.Incomplete, result.kind)
		assertEquals(PlanWarningKind.Excluded, result.warnings.single().kind)
		assertEquals(PlanWarningKind.Approximate, plan.compiledPlan?.warnings?.single()?.kind)
	}
}
