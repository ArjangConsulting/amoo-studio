package dev.amoo.studio

import io.github.maniramezan.processrpc.ProcessRpcState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioControllerTest {
	private fun fixture(block: suspend StudioControllerFixture.() -> Unit) = runBlocking {
		StudioControllerFixture().use { it.block() }
	}

	private suspend fun StudioControllerFixture.useOllama() {
		awaitState { it.providerApiKeysLoaded }
		send(StudioEvent.SaveProvider(ProviderProfile("ollama", "Local Ollama", ProviderKind.Ollama, "http://localhost:11434", "llama3")))
	}

	// MARK: Handshake and connection

	@Test
	fun `a successful handshake enables the advertised capabilities`() = fixture {
		connect("devices.list", "reports.list", version = "0.9.1")

		assertEquals(ConnectionState.Ready("0.9.1", STUDIO_PROTOCOL_VERSION, listOf("devices.list", "reports.list")), state.connection)
		assertEquals(1, rpc.starts)
		assertEquals(1, rpc.count("system.handshake"))
	}

	@Test
	fun `a protocol mismatch fails with remediation`() = fixture {
		connect("devices.list", protocol = 2)

		val connection = assertIs<ConnectionState.Unavailable>(state.connection)
		assertTrue("protocol 2" in connection.reason)
	}

	@Test
	fun `a failed handshake reports Amoo's message`() = fixture {
		rpc.reply("system.handshake", failure("handshake exploded"))
		rpc.state.value = ProcessRpcState.Ready(1)

		val connection = assertIs<ConnectionState.Unavailable>(awaitState { it.connection is ConnectionState.Unavailable }.connection)
		assertTrue("handshake exploded" in connection.reason)
	}

	@Test
	fun `a process that cannot start offers installing Amoo only when it is missing`() = fixture {
		host.amooMissing = true
		rpc.state.value = ProcessRpcState.Unavailable("no such file")

		val connection = assertIs<ConnectionState.Unavailable>(awaitState { it.connection is ConnectionState.Unavailable }.connection)
		assertEquals(ConnectionState.Unavailable("no such file", canInstallAmoo = true), connection)
	}

	@Test
	fun `retrying the connection restarts the process`() = fixture {
		send(StudioEvent.RetryConnection)

		assertEquals(2, rpc.starts)
		assertEquals(ConnectionState.Starting, state.connection)
	}

	@Test
	fun `a failing tool catalog keeps the legacy console vocabulary`() = fixture {
		rpc.reply("tools.catalog", failure("no catalog"))
		connect(TOOLS_CATALOG_CAPABILITY)

		val updated = awaitState { it.noticeMessage != null }
		assertTrue("Could not load Amoo's tool catalog" in updated.noticeMessage.orEmpty())
		assertEquals(ToolCatalog.Legacy, updated.toolCatalog)
		assertIs<ConnectionState.Ready>(updated.connection)
	}

	@Test
	fun `a process restart ends an interrupted recording`() = fixture {
		connect(REPL_RECORDING_CAPABILITY)
		selectRunningDevice()
		send(StudioEvent.ChangeAppId("dev.app"))
		rpc.reply("repl.startRecording", ok("""{"sessionId":"s-1","actionCount":0}"""))
		send(StudioEvent.StartConsoleRecording)
		awaitState { it.console.recording?.status == ConsoleRecordingStatus.Recording }

		rpc.state.value = ProcessRpcState.Unavailable("crashed")

		val recording = awaitState { it.console.recording?.status == ConsoleRecordingStatus.Stopped }.console.recording
		assertNotNull(recording?.message)
	}

	// MARK: Capability gating

	@Test
	fun `workflows the connected Amoo does not support are refused without calling it`() = fixture {
		connect("health")
		selectRunningDevice()

		send(StudioEvent.RefreshDevices)
		assertTrue("devices.list" in awaitState { it.noticeMessage != null }.noticeMessage.orEmpty())

		send(StudioEvent.ShowNotice(null), StudioEvent.RunTest)
		assertTrue("tests.run" in awaitState { it.noticeMessage != null }.noticeMessage.orEmpty())

		send(StudioEvent.ShowNotice(null), StudioEvent.ExportTest)
		assertTrue("tests.export" in awaitState { it.noticeMessage != null }.noticeMessage.orEmpty())

		send(StudioEvent.ShowNotice(null), StudioEvent.ExecuteConsoleCommand)
		assertTrue("repl.execute" in awaitState { it.noticeMessage != null }.noticeMessage.orEmpty())

		send(StudioEvent.ShowNotice(null), StudioEvent.CheckProvider("ollama"))
		assertTrue("providers.check" in awaitState { it.noticeMessage != null }.noticeMessage.orEmpty())

		send(StudioEvent.RefreshReports)
		awaitState { it.noticeMessage?.contains("reports.list") == true }

		assertEquals(listOf("system.handshake"), rpc.calls.values().map { it.method })
	}

	// MARK: Devices and reports

	@Test
	fun `devices and reports load from Amoo`() = fixture {
		connect("devices.list", "reports.list")
		rpc.reply("devices.list", ok("""{"devices":[{"id":"sim-1","name":"iPhone 17","platform":"Ios","status":"Running"}]}"""))
		rpc.reply("reports.list", ok("""{"reports":[{"id":"r-1","testName":"Sign in","status":"Passed","startedAt":"2026-11-01T10:00:00Z"}]}"""))

		send(StudioEvent.RefreshDevices, StudioEvent.RefreshReports)

		assertEquals(listOf("sim-1"), awaitState { it.devices.isNotEmpty() }.devices.map { it.id })
		assertEquals(listOf("r-1"), awaitState { it.reports.isNotEmpty() }.reports.map { it.id })
	}

	@Test
	fun `a device discovery failure is reported`() = fixture {
		connect("devices.list")
		rpc.reply("devices.list", failure("simctl missing"))

		send(StudioEvent.RefreshDevices)

		assertTrue("simctl missing" in awaitState { it.noticeMessage != null }.noticeMessage.orEmpty())
	}

	@Test
	fun `starting a device runs the operation then refreshes the list`() = fixture {
		connect("devices.list", "devices.start")
		rpc.reply("devices.start", ok("""{"message":"Started"}"""))
		rpc.reply("devices.list", ok("""{"devices":[]}"""))

		send(StudioEvent.StartDevice("sim-9"))

		awaitState { it.noticeMessage == "Started" }
		assertEquals("sim-9", rpc.requests("devices.start").single().jsonObject["id"]?.jsonPrimitive?.content)
		awaitState { rpc.count("devices.list") == 1 }
	}

	@Test
	fun `a failed device operation is reported and releases the busy state`() = fixture {
		connect("devices.start")
		rpc.reply("devices.start", failure("boot failed"))

		send(StudioEvent.StartDevice("sim-9"))

		val updated = awaitState { it.noticeMessage != null }
		assertTrue("boot failed" in updated.noticeMessage.orEmpty())
		assertEquals(DeviceOperation.Idle, updated.deviceOperation)
	}

	// MARK: Test runs

	@Test
	fun `a test run follows pushed progress to completion without polling`() = fixture {
		connect("tests.run", "tests.start", TESTS_PROGRESS_CAPABILITY, "tests.status")
		rpc.reply("tests.start", ok("""{"runId":"run-1"}"""))
		selectRunningDevice()

		send(StudioEvent.RunTest)
		awaitState { it.testExecution is TestExecution.Running && rpc.count("tests.start") == 1 }
		rpc.notify("tests.progress", """{"runId":"run-1","state":"Running","currentOperation":1,"totalOperations":2,"message":"Tapping"}""")
		val running = awaitState { (it.testExecution as? TestExecution.Running)?.currentOperation == 1 }
		assertEquals("run-1", (running.testExecution as TestExecution.Running).runId)

		rpc.notify("tests.progress", """{"runId":"run-1","state":"Passed","currentOperation":2,"totalOperations":2,"message":"Passed","sessionId":"s-1","reportId":"r-1"}""")

		val finished = awaitState { it.testExecution == TestExecution.Idle }
		assertEquals("r-1", finished.lastReportId)
		assertEquals("s-1", finished.lastSessionId)
		assertEquals("Passed", finished.noticeMessage)
		assertEquals(0, rpc.count("tests.status"))
	}

	@Test
	fun `a test run without pushed progress polls its status until it finishes`() = fixture {
		connect("tests.run", "tests.start", "tests.status")
		rpc.reply("tests.start", ok("""{"runId":"run-1"}"""))
		rpc.reply(
			"tests.status",
			listOf(
				ok("""{"runId":"run-1","state":"Running","currentOperation":1,"totalOperations":3,"message":"One"}"""),
				ok("""{"runId":"run-1","state":"Running","currentOperation":2,"totalOperations":3,"message":"Two"}"""),
				ok("""{"runId":"run-1","state":"Failed","currentOperation":2,"totalOperations":3,"message":"Step 3 failed"}"""),
			),
		)
		selectRunningDevice()

		send(StudioEvent.RunTest)

		val finished = awaitState { it.testExecution == TestExecution.Idle && it.noticeMessage != null }
		assertEquals("Step 3 failed", finished.noticeMessage)
		assertEquals(3, rpc.count("tests.status"))
	}

	@Test
	fun `a run that cannot start reports the failure`() = fixture {
		connect("tests.run", "tests.start")
		rpc.reply("tests.start", failure("no provider"))
		selectRunningDevice()

		send(StudioEvent.RunTest)

		val updated = awaitState { it.testExecution == TestExecution.Idle && it.noticeMessage != null }
		assertTrue("Test run failed: no provider" in updated.noticeMessage.orEmpty())
	}

	@Test
	fun `a progress report for another run fails the tracked run`() = fixture {
		connect("tests.run", "tests.start", "tests.status")
		rpc.reply("tests.start", ok("""{"runId":"run-1"}"""))
		rpc.reply("tests.status", ok("""{"runId":"other","state":"Running","currentOperation":0,"totalOperations":1,"message":"x"}"""))
		selectRunningDevice()

		send(StudioEvent.RunTest)

		assertTrue("other" in awaitState { it.testExecution == TestExecution.Idle && it.noticeMessage != null }.noticeMessage.orEmpty())
	}

	@Test
	fun `cancelling a run tells Amoo and stops polling`() = fixture {
		connect("tests.run", "tests.start", "tests.status", "tests.cancel")
		rpc.reply("tests.start", ok("""{"runId":"run-1"}"""))
		rpc.reply("tests.status", ok("""{"runId":"run-1","state":"Running","currentOperation":1,"totalOperations":3,"message":"Working"}"""))
		rpc.reply("tests.cancel", ok("{}"))
		selectRunningDevice()
		send(StudioEvent.RunTest)
		awaitState { (it.testExecution as? TestExecution.Running)?.runId == "run-1" }

		send(StudioEvent.CancelTestRun)

		awaitState { rpc.count("tests.cancel") == 1 }
		assertEquals("run-1", rpc.requests("tests.cancel").single().jsonObject["runId"]?.jsonPrimitive?.content)
		assertEquals(TestExecution.Idle, state.testExecution)
		val polls = rpc.count("tests.status")
		delay(700)
		assertTrue(rpc.count("tests.status") <= polls + 1, "polling continued after cancellation")
	}

	@Test
	fun `older Amoo builds run tests synchronously`() = fixture {
		connect("tests.run")
		rpc.reply("tests.run", ok("""{"message":"All steps passed","sessionId":"s-2","reportId":"r-2"}"""))
		selectRunningDevice()

		send(StudioEvent.RunTest)

		val finished = awaitState { it.lastReportId == "r-2" }
		assertEquals("All steps passed", finished.noticeMessage)
		assertEquals(TestExecution.Idle, finished.testExecution)
	}

	@Test
	fun `a run needs a selected device`() = fixture {
		connect("tests.run", "tests.start")

		send(StudioEvent.RunTest)

		assertEquals("Choose a device before running the test", state.noticeMessage)
		assertEquals(0, rpc.count("tests.start"))
	}

	// MARK: Agent runs

	private suspend fun StudioControllerFixture.prepareAgent() {
		connect(AGENT_RUNS_CAPABILITY)
		selectRunningDevice()
		useOllama()
		send(StudioEvent.ChangeAppId("dev.app"), StudioEvent.ChangeChatInput("Sign in"))
	}

	@Test
	fun `agent events that arrive before the run id is known are replayed in order`() = fixture {
		prepareAgent()
		val start = rpc.reply("agent.start", ok("""{"runId":"run-1"}"""))
		val gate = start.hold(0)

		send(StudioEvent.SendChat)
		start.waitForCalls(1)
		rpc.notify("agent.event", """{"runId":"run-1","seq":1,"kind":"assistantMessage","text":"Opening the app"}""")
		rpc.notify("agent.event", """{"runId":"run-1","seq":0,"kind":"sessionStarted","sessionId":"s-1"}""")
		delay(200)
		gate.open()

		val run = awaitState { it.agentRun?.events?.size == 2 }.agentRun
		assertEquals(listOf(0, 1), run?.events?.map { it.seq })
		assertEquals("s-1", run?.sessionId)
		assertEquals(listOf("Sign in"), state.chat.messages.map { it.content })
	}

	@Test
	fun `a gap in agent events is repaired by asking Amoo to replay`() = fixture {
		prepareAgent()
		rpc.reply("agent.start", ok("""{"runId":"run-1"}"""))
		rpc.reply(
			"agent.events",
			ok("""{"state":"completed","nextSeq":4,"events":[
				{"runId":"run-1","seq":1,"kind":"assistantMessage","text":"One"},
				{"runId":"run-1","seq":2,"kind":"assistantMessage","text":"Two"},
				{"runId":"run-1","seq":3,"kind":"finished","state":"completed","sessionId":"s-1"}]}"""),
		)
		send(StudioEvent.SendChat)
		awaitState { it.agentRun?.runId == "run-1" }
		rpc.notify("agent.event", """{"runId":"run-1","seq":0,"kind":"sessionStarted","sessionId":"s-1"}""")
		awaitState { it.agentRun?.nextSeq == 1 }

		rpc.notify("agent.event", """{"runId":"run-1","seq":3,"kind":"finished","state":"completed","sessionId":"s-1"}""")

		val finished = awaitState { it.agentRun?.status == AgentRunStatus.Completed }.agentRun
		assertEquals(listOf(0, 1, 2, 3), finished?.events?.map { it.seq })
		assertEquals(0, rpc.requests("agent.events").single().jsonObject["after"]?.jsonPrimitive?.content?.toInt())
	}

	@Test
	fun `an agent run that fails to start reports it`() = fixture {
		prepareAgent()
		rpc.reply("agent.start", failure("no model"))

		send(StudioEvent.SendChat)

		assertTrue("Agent run failed to start: no model" in awaitState { it.chat.lastError != null }.chat.lastError.orEmpty())
	}

	@Test
	fun `answering an agent approval and cancelling go through Amoo`() = fixture {
		prepareAgent()
		rpc.reply("agent.start", ok("""{"runId":"run-1"}"""))
		rpc.reply("agent.approve", ok("{}"))
		rpc.reply("agent.cancel", ok("{}"))
		send(StudioEvent.SendChat)
		awaitState { it.agentRun?.runId == "run-1" }
		rpc.notify("agent.event", """{"runId":"run-1","seq":0,"kind":"sessionStarted","sessionId":"s-1"}""")
		rpc.notify("agent.event", """{"runId":"run-1","seq":1,"kind":"toolCall","callId":"c1","tool":"device_uninstall_app","arguments":{"app_id":"dev.app"}}""")
		rpc.notify("agent.event", """{"runId":"run-1","seq":2,"kind":"approvalRequired","callId":"c1","tool":"device_uninstall_app"}""")
		awaitState { it.pendingApproval?.action is ApprovedAction.AgentTool }

		send(StudioEvent.ResolveApproval(true))
		awaitState { rpc.count("agent.approve") == 1 }
		val approval = rpc.requests("agent.approve").single().jsonObject
		assertEquals("c1", approval["callId"]?.jsonPrimitive?.content)
		assertEquals("true", approval["approved"]?.jsonPrimitive?.content)

		send(StudioEvent.CancelAgentRun)
		awaitState { rpc.count("agent.cancel") == 1 }
	}

	@Test
	fun `a conversation without a device uses plain chat and persists the history`() = fixture {
		connect("chat.send")
		useOllama()
		send(StudioEvent.ChangeChatInput("Hello"))
		rpc.reply("chat.send", ok("""{"message":"Hi there"}"""))

		send(StudioEvent.SendChat)

		val updated = awaitState { it.chat.messages.size == 2 }
		assertEquals(listOf("Hello", "Hi there"), updated.chat.messages.map { it.content })
		assertTrue(chatHistoryFile.isFile)
		assertEquals(2, ChatHistoryStore(chatHistoryFile, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).load().size)
	}

	@Test
	fun `a chat failure is shown and clearing chat empties the saved history`() = fixture {
		connect("chat.send")
		useOllama()
		send(StudioEvent.ChangeChatInput("Hello"))
		rpc.reply("chat.send", failure("provider down"))

		send(StudioEvent.SendChat)

		assertTrue("provider down" in awaitState { it.chat.lastError != null }.chat.lastError.orEmpty())
		send(StudioEvent.ClearChat)
		assertEquals(0, ChatHistoryStore(chatHistoryFile, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).load().size)
	}

	@Test
	fun `chatting needs a provider`() = fixture {
		connect("chat.send")
		send(StudioEvent.ChangeChatInput("Hello"))

		send(StudioEvent.SendChat)

		assertEquals("Choose an AI provider before sending a message", state.noticeMessage)
		assertEquals(0, rpc.count("chat.send"))
	}

	// MARK: Console and recording

	@Test
	fun `a console command runs through Amoo and records its result`() = fixture {
		connect("repl.execute")
		send(StudioEvent.ChangeConsoleInput("devices list"))
		rpc.reply("repl.execute", ok("""{"output":"2 devices","tool":"devices_list"}"""))

		send(StudioEvent.ExecuteConsoleCommand)

		val entry = awaitState { it.console.entries.isNotEmpty() }.console.entries.single()
		assertEquals("2 devices", entry.output)
		assertFalse(entry.failed)
	}

	@Test
	fun `a failing console command becomes a failed entry`() = fixture {
		connect("repl.execute")
		send(StudioEvent.ChangeConsoleInput("devices list"))
		rpc.reply("repl.execute", failure("bridge down"))

		send(StudioEvent.ExecuteConsoleCommand)

		val entry = awaitState { it.console.entries.isNotEmpty() }.console.entries.single()
		assertTrue(entry.failed)
		assertTrue("bridge down" in entry.output)
	}

	@Test
	fun `a recording starts and stops through Amoo`() = fixture {
		connect(REPL_RECORDING_CAPABILITY)
		selectRunningDevice()
		send(StudioEvent.ChangeAppId("dev.app"))
		rpc.reply("repl.startRecording", ok("""{"sessionId":"s-1","actionCount":0}"""))
		rpc.reply("repl.stopRecording", ok("""{"sessionId":"s-1","actionCount":3,"planPath":"/plan.json"}"""))

		send(StudioEvent.StartConsoleRecording)
		awaitState { it.console.recording?.status == ConsoleRecordingStatus.Recording }
		assertEquals(15 * 60_000L, rpc.calls.values().single { it.method == "repl.startRecording" }.timeoutMs)
		send(StudioEvent.StopConsoleRecording)

		val recording = awaitState { it.console.recording?.status == ConsoleRecordingStatus.Stopped }.console.recording
		assertEquals(3, recording?.actionCount)
		assertEquals("/plan.json", recording?.planPath)
	}

	@Test
	fun `a recording that cannot start is dropped with an explanation`() = fixture {
		connect(REPL_RECORDING_CAPABILITY)
		selectRunningDevice()
		send(StudioEvent.ChangeAppId("dev.app"))
		rpc.reply("repl.startRecording", failure("companion build failed"))

		send(StudioEvent.StartConsoleRecording)

		val updated = awaitState { it.noticeMessage != null && it.console.recording == null }
		assertTrue("companion build failed" in updated.noticeMessage.orEmpty())
	}

	@Test
	fun `a recording that cannot stop keeps recording`() = fixture {
		connect(REPL_RECORDING_CAPABILITY)
		selectRunningDevice()
		send(StudioEvent.ChangeAppId("dev.app"))
		rpc.reply("repl.startRecording", ok("""{"sessionId":"s-1","actionCount":0}"""))
		rpc.reply("repl.stopRecording", failure("disk full"))
		send(StudioEvent.StartConsoleRecording)
		awaitState { it.console.recording?.status == ConsoleRecordingStatus.Recording }

		send(StudioEvent.StopConsoleRecording)

		val updated = awaitState { it.noticeMessage != null }
		assertEquals(ConsoleRecordingStatus.Recording, updated.console.recording?.status)
		assertTrue("disk full" in updated.noticeMessage.orEmpty())
	}

	// MARK: Providers

	private val openAi = ProviderProfile("openai", "OpenAI", ProviderKind.OpenAI, "https://api.openai.com/v1", "gpt-5", apiKey = Secret("sk-secret"))

	@Test
	fun `saving a provider stores the profile and keeps its key in the keychain only`() = fixture {
		awaitState { it.providerApiKeysLoaded }

		send(StudioEvent.SaveProvider(openAi))

		awaitState { "openai" !in it.providerApiKeysSaving && it.providers.any { provider -> provider.id == "openai" } }
		assertEquals("sk-secret", keychain.entries["openai"])
		assertFalse("sk-secret" in preferences.get("providers", ""))
		assertEquals("openai", state.selectedProviderId)
	}

	@Test
	fun `a keychain failure is surfaced and the save can be retried`() = fixture {
		awaitState { it.providerApiKeysLoaded }
		keychain.mutationFailure = IllegalStateException("keychain locked")

		send(StudioEvent.SaveProvider(openAi))
		awaitState { "openai" in it.providerApiKeySaveFailures }
		assertNull(keychain.entries["openai"])

		keychain.mutationFailure = null
		send(StudioEvent.RetryProviderApiKeySave("openai"))

		awaitState { "openai" !in it.providerApiKeySaveFailures && "openai" !in it.providerApiKeysSaving }
		assertEquals("sk-secret", keychain.entries["openai"])
	}

	@Test
	fun `a provider whose key could not be read is not overwritten by a save`() = fixture {
		keychain.readFailures["ollama"] = IllegalStateException("denied")
		retryApiKeyLoad()

		val ollama = state.providers.first { it.id == "ollama" }
		send(StudioEvent.SaveProvider(ollama.copy(apiKey = Secret("new"))))
		delay(200)

		assertNull(keychain.entries["ollama"])
	}

	private suspend fun StudioControllerFixture.retryApiKeyLoad() {
		send(StudioEvent.RetryProviderApiKeys)
		awaitState { "ollama" in it.providerApiKeyReadFailures }
	}

	@Test
	fun `removing a provider deletes its key`() = fixture {
		awaitState { it.providerApiKeysLoaded }
		send(StudioEvent.SaveProvider(openAi))
		awaitState { keychain.entries.containsKey("openai") }

		send(StudioEvent.RemoveProvider("openai"))

		awaitState { !keychain.entries.containsKey("openai") }
		assertTrue(state.providers.none { it.id == "openai" })
	}

	@Test
	fun `a provider check reports success and failure`() = fixture {
		connect("providers.check", PROVIDER_API_KEY_CAPABILITY)
		useOllama()
		rpc.reply("providers.check", listOf(ok("""{"message":"Reachable"}"""), failure("401")))

		send(StudioEvent.CheckProvider("ollama"))
		assertEquals(ProviderCheckState.Ready("Reachable"), awaitState { it.providerChecks["ollama"] is ProviderCheckState.Ready }.providerChecks["ollama"])

		send(StudioEvent.CheckProvider("ollama"))
		val failed = awaitState { it.providerChecks["ollama"] is ProviderCheckState.Failed }.providerChecks["ollama"]
		assertTrue("401" in (failed as ProviderCheckState.Failed).message)
	}

	@Test
	fun `API keys are sent to Amoo only when it advertises support`() = fixture {
		connect("chat.send")
		send(StudioEvent.SaveProvider(openAi.copy(apiKeyEnvironmentVariable = "OPENAI_API_KEY")), StudioEvent.ChangeChatInput("hi"))
		rpc.reply("chat.send", ok("""{"message":"ok"}"""))

		send(StudioEvent.SendChat)
		awaitState { it.chat.messages.size == 2 }

		val provider = rpc.requests("chat.send").single().jsonObject["provider"]!!.jsonObject
		assertFalse("apiKey" in provider)
		assertEquals("OPENAI_API_KEY", provider["apiKeyEnvironmentVariable"]?.jsonPrimitive?.content)
	}

	// MARK: Test files

	@Test
	fun `saving writes the test and clears the dirty flag`() = fixture {
		val target = File(directory.file, "checkout")
		picker.toSave = target
		send(StudioEvent.ChangeTestName("Checkout"))

		send(StudioEvent.SaveTest)

		val saved = File(directory.file, "checkout.amootest")
		assertTrue(saved.isFile)
		assertEquals("Checkout", AmooTestFileStore().load(saved).name)
		assertFalse(state.isTestDirty)
		assertEquals(saved.absolutePath, state.testPath)
		send(StudioEvent.ChangeTestName("Checkout 2"), StudioEvent.SaveTest)
		assertEquals("Checkout 2", AmooTestFileStore().load(saved).name)
		assertEquals(1, picker.prompts.values().count { it.startsWith("save:") })
	}

	@Test
	fun `a cancelled save leaves the test dirty`() = fixture {
		send(StudioEvent.ChangeTestName("Checkout"))

		send(StudioEvent.SaveTest)

		assertTrue(state.isTestDirty)
		assertNull(state.testPath)
	}

	@Test
	fun `opening a test needs approval while there are unsaved changes`() = fixture {
		val file = File(directory.file, "other.amootest")
		AmooTestFileStore().save(AmooTest(name = "Other"), file)
		picker.toOpen = file
		send(StudioEvent.ChangeTestName("Edited"))

		send(StudioEvent.OpenTest)
		assertEquals(0, picker.prompts.values().size)
		assertEquals("Edited", state.test.name)

		send(StudioEvent.ResolveApproval(true))
		assertEquals("Other", state.test.name)
		assertFalse(state.isTestDirty)
		assertEquals(file.absolutePath, state.testPath)
	}

	@Test
	fun `declining the discard keeps the edits and never asks for a file`() = fixture {
		send(StudioEvent.ChangeTestName("Edited"), StudioEvent.OpenTest, StudioEvent.ResolveApproval(false))

		assertEquals("Edited", state.test.name)
		assertEquals(0, picker.prompts.values().size)
	}

	@Test
	fun `an unreadable test file is reported`() = fixture {
		picker.toOpen = File(directory.file, "missing.amootest")

		send(StudioEvent.OpenTest)

		assertTrue("Could not open test" in state.noticeMessage.orEmpty())
	}

	@Test
	fun `quitting with unsaved changes waits for confirmation`() = fixture {
		send(StudioEvent.ChangeTestName("Edited"), StudioEvent.RequestQuit)
		assertFalse(state.exitRequested)

		send(StudioEvent.ResolveApproval(true))

		assertTrue(state.exitRequested)
	}

	// MARK: Generation and export

	@Test
	fun `a generated test is saved atomically to the chosen file`() = fixture {
		connect(TESTS_GENERATE_CAPABILITY)
		rpc.reply(
			"tests.generate",
			ok("""{"generated":{"fileName":"SignInTest.swift","source":"import XCTest","language":"swift"},"test":{"name":"Sign in"}}"""),
		)
		send(StudioEvent.GenerateTest(GenerationSource.AuthoredTest))
		awaitState { it.generatedTest?.generated != null }
		val target = File(directory.file, "SignInTest.swift")
		picker.toSave = target

		send(StudioEvent.SaveGeneratedTest)

		assertEquals("import XCTest", target.readText())
		assertEquals("Saved SignInTest.swift", state.noticeMessage)
	}

	@Test
	fun `generation reports Amoo failures and old builds`() = fixture {
		connect(TESTS_GENERATE_CAPABILITY)
		rpc.reply("tests.generate", failure("emitter crashed"))
		send(StudioEvent.GenerateTest(GenerationSource.AuthoredTest))
		assertTrue("emitter crashed" in awaitState { it.generatedTest?.loading == false }.generatedTest?.problem?.message.orEmpty() + state.noticeMessage.orEmpty())
	}

	@Test
	fun `exporting a test saves the source Amoo returns`() = fixture {
		connect("tests.export")
		rpc.reply("tests.export", ok("""{"fileName":"CheckoutTest.kt","source":"class CheckoutTest"}"""))
		val target = File(directory.file, "CheckoutTest.kt")
		picker.toSave = target
		send(StudioEvent.AddTestPlanOperation("tap_element id=login"))

		send(StudioEvent.ExportTest)

		awaitState { it.noticeMessage == "Exported CheckoutTest.kt" }
		assertEquals("class CheckoutTest", target.readText())
		assertFalse(state.testExportInProgress)
	}

	@Test
	fun `exporting needs at least one action`() = fixture {
		connect("tests.export")

		send(StudioEvent.ExportTest)

		assertEquals("Add at least one action before exporting a test", state.noticeMessage)
		assertEquals(0, rpc.count("tests.export"))
	}

	@Test
	fun `a cancelled export file choice finishes the export`() = fixture {
		connect("tests.export")
		rpc.reply("tests.export", ok("""{"fileName":"CheckoutTest.kt","source":"class CheckoutTest"}"""))
		send(StudioEvent.AddTestPlanOperation("tap_element id=login"))

		send(StudioEvent.ExportTest)

		awaitState { it.noticeMessage == "Export cancelled" }
	}

	// MARK: MCP and session persistence

	@Test
	fun `MCP readiness is read from Amoo`() = fixture {
		connect("mcp.status")
		rpc.reply("mcp.status", ok("""{"available":true,"transport":"stdio","arguments":["mcp","serve"]}"""))

		send(StudioEvent.SelectSection(StudioSection.Settings))

		assertEquals(McpStatus.Ready("stdio", listOf("mcp", "serve")), awaitState { it.mcpStatus is McpStatus.Ready }.mcpStatus)
	}

	@Test
	fun `the working context is restored by the next launch`() = fixture {
		picker.project = File("/work/App.xcodeproj")
		send(StudioEvent.ChooseProjectPath, StudioEvent.ChangeAppId("dev.app"), StudioEvent.ChangeSchemeOrModule("App"), StudioEvent.SelectProvider("ollama"), StudioEvent.SelectDevice("sim-1"))
		assertEquals("/work/App.xcodeproj", state.projectPath)
		delay(900)

		val relaunched = StudioControllerFixture(preferencesPath = preferencesPath)
		try {
			val restored = relaunched.state
			assertEquals("/work/App.xcodeproj", restored.projectPath)
			assertEquals("dev.app", restored.appId)
			assertEquals("App", restored.schemeOrModule)
			assertEquals("ollama", restored.selectedProviderId)
			assertEquals("sim-1", restored.selectedDeviceId)
		} finally {
			relaunched.controller.close()
		}
	}

	@Test
	fun `closing the controller closes the connection`() = fixture {
		controller.close()

		assertTrue(rpc.closed)
	}

	@Test
	fun `a provider without a model is refused until one is chosen`() = fixture {
		connect("chat.send")
		awaitState { it.providerApiKeysLoaded }
		send(StudioEvent.SelectProvider("ollama"), StudioEvent.ChangeChatInput("Hello"))

		send(StudioEvent.SendChat)

		assertTrue("Choose a model" in state.noticeMessage.orEmpty())
		assertEquals(0, rpc.count("chat.send"))
	}

	@Test
	fun `the window position is stored with its size`() = fixture {
		controller.persistWindowBounds(WindowOrigin(40, 60), WindowSize(1100, 700))

		val stored = StudioPreferences(preferences)
		assertEquals(WindowOrigin(40, 60), stored.windowOrigin())
		assertEquals(WindowSize(1100, 700), stored.windowSize())
	}
}
