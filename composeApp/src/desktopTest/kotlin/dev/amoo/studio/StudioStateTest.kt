package dev.amoo.studio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
	fun `completed test run retains session and report references`() {
		val running = StudioState(testExecution = TestExecution.Running("Running"))

		val updated = running.reduce(StudioEvent.TestRunFinished("Passed", "session-1", "report-1"))

		assertEquals(TestExecution.Idle, updated.testExecution)
		assertEquals("session-1", updated.lastSessionId)
		assertEquals("report-1", updated.lastReportId)
		assertEquals("Passed", updated.notice)
	}

	@Test
	fun `loading reports selects the first result`() {
		val report = TestReport("report-1", "Sign in", ReportStatus.Passed, "2026-08-20T10:00:00Z")

		val updated = StudioState(reportsLoading = true).reduce(StudioEvent.ReportsLoaded(listOf(report)))

		assertEquals(listOf(report), updated.reports)
		assertEquals("report-1", updated.selectedReportId)
		assertEquals(false, updated.reportsLoading)
	}
}
