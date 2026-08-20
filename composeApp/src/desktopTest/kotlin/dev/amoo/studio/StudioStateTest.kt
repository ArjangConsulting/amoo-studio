package dev.amoo.studio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioStateTest {
	@Test
	fun `selecting a section preserves connection state`() {
		val connection = ConnectionState.Ready("0.1.0", 1, listOf("health"))
		val state = StudioState(connection = connection)

		val updated = state.reduce(StudioEvent.SelectSection(StudioSection.Devices))

		assertEquals(StudioSection.Devices, updated.section)
		assertEquals(connection, updated.connection)
	}

	@Test
	fun `retry returns connection to starting`() {
		val state = StudioState(connection = ConnectionState.Unavailable("offline"))

		assertEquals(ConnectionState.Starting, state.reduce(StudioEvent.RetryConnection).connection)
	}

	@Test
	fun `changing appearance preserves the rest of studio state`() {
		val state = StudioState(section = StudioSection.Settings, testPath = "/tmp/login.amootest")

		val updated = state.reduce(StudioEvent.ChangeThemeMode(ThemeMode.Dark))

		assertEquals(ThemeMode.Dark, updated.themeMode)
		assertEquals(StudioSection.Settings, updated.section)
		assertEquals("/tmp/login.amootest", updated.testPath)
	}

	@Test
	fun `editing a test marks it dirty and preserves connection`() {
		val connection = ConnectionState.Ready("0.1.0", 1, emptyList())
		val state = StudioState(connection = connection)

		val updated = state.reduce(StudioEvent.ChangeTestName("Checkout flow"))

		assertEquals("Checkout flow", updated.test.name)
		assertEquals(true, updated.isTestDirty)
		assertEquals(connection, updated.connection)
	}

	@Test
	fun `adding and removing steps uses stable unique ids`() {
		val state = StudioState().reduce(StudioEvent.AddTestStep).reduce(StudioEvent.AddTestStep)

		assertEquals(listOf("step-1", "step-2", "step-3"), state.test.steps.map { it.id })
		assertEquals(listOf("step-1", "step-3"), state.reduce(StudioEvent.RemoveTestStep("step-2")).test.steps.map { it.id })
	}

	@Test
	fun `saving a provider selects it`() {
		val provider = ProviderProfile("openai", "Work OpenAI", ProviderKind.OpenAI, "https://api.openai.com/v1", "gpt-5", "OPENAI_API_KEY")

		val updated = StudioState().reduce(StudioEvent.SaveProvider(provider))

		assertEquals(provider, updated.providers.first { it.id == provider.id })
		assertEquals(provider.id, updated.selectedProviderId)
	}

	@Test
	fun `new tests default to Android on Linux`() {
		val updated = StudioState(hostPlatform = HostPlatform.Linux).reduce(StudioEvent.NewTest)

		assertEquals(TestPlatform.Android, updated.test.platform)
	}

	@Test
	fun `Linux supports Android but not iOS workflows`() {
		assertEquals(true, HostPlatform.Linux.supports(TestPlatform.Android))
		assertEquals(false, HostPlatform.Linux.supports(TestPlatform.Ios))
	}

	@Test
	fun `protocol features require advertised capabilities`() {
		val ready = ConnectionState.Ready("0.1.0", 1, listOf("devices.list"))

		assertEquals(true, ready.supports("devices.list"))
		assertEquals(false, ready.supports("apps.resetData"))
		assertEquals(false, ConnectionState.Starting.supports("devices.list"))
	}

	@Test
	fun `incompatible protocol fails with remediation`() {
		val connection = connectionFromHandshake("2.0.0", STUDIO_PROTOCOL_VERSION + 1, listOf("devices.list"))

		assertIs<ConnectionState.Unavailable>(connection)
		assertTrue(connection.reason.contains("Update Amoo Studio and Amoo"))
	}

	@Test
	fun `existing version one test files remain readable`() {
		val source = """{"formatVersion":1,"name":"Sign in","description":"","platform":"Android","steps":[{"id":"step-1","instruction":"Tap sign in","expected":"Home"}]}"""

		val test = Json.decodeFromString<AmooTest>(source)

		assertEquals("Sign in", test.name)
		assertEquals(null, test.requirements)
		assertEquals(null, test.compiledPlan)
		assertEquals(emptyMap(), test.metadata)
	}

	@Test
	fun `chat request adds user message and clears composer`() {
		val message = ChatMessage("user-1", ChatRole.User, "Explore this screen")
		val state = StudioState(chat = ChatState(input = message.content))

		val updated = state.reduce(StudioEvent.ChatRequestStarted(message))

		assertEquals("", updated.chat.input)
		assertEquals(listOf(message), updated.chat.messages)
		assertEquals(ChatOperation.Sending, updated.chat.operation)
	}

	@Test
	fun `chat response completes request and preserves history`() {
		val user = ChatMessage("user-1", ChatRole.User, "Explore")
		val assistant = ChatMessage("assistant-1", ChatRole.Assistant, "I found three controls")
		val state = StudioState(chat = ChatState(messages = listOf(user), operation = ChatOperation.Sending))

		val updated = state.reduce(StudioEvent.ChatResponseReceived(assistant))

		assertEquals(listOf(user, assistant), updated.chat.messages)
		assertEquals(ChatOperation.Idle, updated.chat.operation)
	}

	@Test
	fun `AI plan requires explicit apply before changing the test`() {
		val proposal = CompiledToolPlan(
			"ai",
			"1",
			toolOperations = listOf(ToolOperation("operation-1", "assert_visible", mapOf("id" to "home"))),
		)
		val response = StudioState().reduce(
			StudioEvent.ChatResponseReceived(ChatMessage("assistant-1", ChatRole.Assistant, "Review this plan"), proposal),
		)

		assertEquals(null, response.test.compiledPlan)
		assertEquals(proposal, response.chat.proposedPlan)

		val applied = response.reduce(StudioEvent.ApplyProposedPlan)
		assertEquals(proposal, applied.test.compiledPlan)
		assertEquals(null, applied.chat.proposedPlan)
		assertEquals(true, applied.isTestDirty)
	}

	@Test
	fun `failed chat can restore the last prompt for retry`() {
		val state = StudioState(chat = ChatState(messages = listOf(ChatMessage("user-1", ChatRole.User, "Generate a sign-in plan"))))
			.reduce(StudioEvent.ChatRequestFailed("Provider timed out"))

		val retry = state.reduce(StudioEvent.RetryLastChat)

		assertEquals("Generate a sign-in plan", retry.chat.input)
		assertEquals(null, retry.chat.lastError)
	}

	@Test
	fun `provider check renders per-profile result`() {
		val checking = StudioState().reduce(StudioEvent.CheckProvider("ollama"))
		assertEquals(ProviderCheckState.Checking, checking.providerChecks["ollama"])

		val ready = checking.reduce(StudioEvent.ProviderCheckFinished("ollama", "Connected"))
		assertEquals(ProviderCheckState.Ready("Connected"), ready.providerChecks["ollama"])
	}

	@Test
	fun `console suggestions include selected runtime context`() {
		val state = StudioState(
			console = ConsoleState(input = "pixel"),
			devices = listOf(StudioDevice("emulator-5554", "Pixel 9", TestPlatform.Android, "16", DeviceStatus.Running)),
		)

		val suggestions = state.consoleSuggestions()

		assertEquals(listOf("devices inspect emulator-5554"), suggestions.map { it.command })
	}

	@Test
	fun `console completion appends result and clears input`() {
		val entry = ConsoleEntry("command-1", "devices list", "2 devices")
		val state = StudioState(console = ConsoleState(input = "devices list", operation = ConsoleOperation.Running))

		val updated = state.reduce(StudioEvent.ConsoleCommandFinished(entry))

		assertEquals("", updated.console.input)
		assertEquals(listOf(entry), updated.console.entries)
		assertEquals(ConsoleOperation.Idle, updated.console.operation)
	}

	@Test
	fun `console parses quoted tool arguments into typed plan operations`() {
		val state = StudioState().reduce(StudioEvent.AddTestPlanOperation("tap_element label=\"Sign in\""))

		assertEquals(
			ToolOperation("operation-1", "tap_element", mapOf("label" to "Sign in")),
			state.test.compiledPlan?.toolOperations?.single(),
		)
		assertEquals(emptyList(), state.test.compiledPlan?.operations)
	}

	@Test
	fun `console history navigates newest commands first`() {
		val entries = listOf(
			ConsoleEntry("1", "devices list", "ok"),
			ConsoleEntry("2", "assert_visible id=home", "ok"),
		)

		val newest = StudioState(console = ConsoleState(entries = entries)).reduce(StudioEvent.NavigateConsoleHistory(-1))
		val older = newest.reduce(StudioEvent.NavigateConsoleHistory(-1))

		assertEquals("assert_visible id=home", newest.console.input)
		assertEquals("devices list", older.console.input)
	}

	@Test
	fun `invalid tool command has inline remediation`() {
		val state = StudioState(console = ConsoleState(input = "set_text id=email"))

		assertEquals("Check required arguments and use name=value syntax", state.consoleValidationError())
	}

	@Test
	fun `completed test run retains session and report references`() {
		val running = StudioState(testExecution = TestExecution.Running("Running"))

		val updated = running.reduce(StudioEvent.TestRunFinished("Passed", "session-1", "report-1"))

		assertEquals(TestExecution.Idle, updated.testExecution)
		assertEquals("session-1", updated.lastSessionId)
		assertEquals("report-1", updated.lastReportId)
		assertEquals("Passed", updated.notice)
	}

	@Test
	fun `test progress retains backend run identity and operation count`() {
		val updated = StudioState(testExecution = TestExecution.Running("Preparing"))
			.reduce(StudioEvent.TestRunProgress("run-1", "Running assert_visible…", 2, 3))

		assertEquals(TestExecution.Running("Running assert_visible…", "run-1", 2, 3), updated.testExecution)
	}

	@Test
	fun `console commands can build and edit a compiled test plan`() {
		val state = StudioState(test = AmooTest(name = "Smoke"))
		val withPlan = state
			.reduce(StudioEvent.AddTestPlanOperation("devices list"))
			.reduce(StudioEvent.AddTestPlanOperation("tests validate"))

		assertEquals(listOf("devices list", "tests validate"), withPlan.test.compiledPlan?.operations)
		assertEquals(true, withPlan.isTestDirty)

		val removed = withPlan.reduce(StudioEvent.RemoveTestPlanOperation(0))
		assertEquals(listOf("tests validate"), removed.test.compiledPlan?.operations)
	}

	@Test
	fun `typed operations validate required arguments and receive stable ids`() {
		val state = StudioState()
			.reduce(StudioEvent.ChangeToolOperationType("set_text"))

		assertEquals("Required: Value", state.operationDraft.validationError())

		val first = state
			.reduce(StudioEvent.ChangeToolOperationArgument("id", "email"))
			.reduce(StudioEvent.ChangeToolOperationArgument("value", "person@example.com"))
			.reduce(StudioEvent.AddToolOperation)
		val second = first
			.reduce(StudioEvent.ChangeToolOperationArgument("id", "password"))
			.reduce(StudioEvent.ChangeToolOperationArgument("value", "secret"))
			.reduce(StudioEvent.AddToolOperation)

		assertEquals(listOf("operation-1", "operation-2"), second.test.compiledPlan?.toolOperations?.map { it.id })
		assertEquals(true, second.isTestDirty)
	}

	@Test
	fun `typed operations survive amootest serialization`() {
		val expected = AmooTest(
			compiledPlan = CompiledToolPlan(
				compiler = "studio",
				compilerVersion = "1",
				toolOperations = listOf(ToolOperation("operation-1", "assert_visible", mapOf("id" to "home"))),
			),
		)

		assertEquals(expected, Json.decodeFromString<AmooTest>(Json.encodeToString(expected)))
	}

	@Test
	fun `loading reports selects the first result`() {
		val report = TestReport("report-1", "Sign in", ReportStatus.Passed, "2026-08-20T10:00:00Z")

		val updated = StudioState(reportsLoading = true).reduce(StudioEvent.ReportsLoaded(listOf(report)))

		assertEquals(listOf(report), updated.reports)
		assertEquals("report-1", updated.selectedReportId)
		assertEquals(false, updated.reportsLoading)
	}

	@Test
	fun `device creation defaults to host supported platform`() {
		val updated = StudioState(hostPlatform = HostPlatform.Linux).reduce(StudioEvent.RequestCreateDevice)

		assertEquals(TestPlatform.Android, updated.createDevice?.platform)
	}

	@Test
	fun `MCP readiness transitions from checking to ready`() {
		val checking = StudioState().reduce(StudioEvent.RefreshMcpStatus)
		assertEquals(McpStatus.Checking, checking.mcpStatus)

		val ready = checking.reduce(StudioEvent.McpStatusLoaded("stdio", listOf("mcp", "serve")))
		assertEquals(McpStatus.Ready("stdio", listOf("mcp", "serve")), ready.mcpStatus)
	}

	@Test
	fun `confirming device creation closes form and starts progress`() {
		val state = StudioState(createDevice = CreateDeviceState(TestPlatform.Android, "Pixel", "android-36", "pixel_9"))

		val updated = state.reduce(StudioEvent.ConfirmCreateDevice)

		assertEquals(null, updated.createDevice)
		assertEquals(DeviceOperation.Working("Creating device…"), updated.deviceOperation)
	}
}
