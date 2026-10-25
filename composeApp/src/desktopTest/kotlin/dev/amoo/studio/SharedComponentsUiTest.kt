package dev.amoo.studio

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.maniramezan.kmpcomponents.KmpTheme
import io.github.maniramezan.kmpcomponents.LocalReduceMotionEnabled
import io.github.maniramezan.testcommons.CallRecorder
import io.github.maniramezan.testcommons.compose.assertMinimumTouchTarget
import io.github.maniramezan.testcommons.compose.captureToPng
import io.github.maniramezan.testcommons.compose.replaceTextVerified
import io.github.maniramezan.testcommons.compose.waitForText
import io.github.maniramezan.testcommons.compose.waitForContentDescription
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SharedComponentsUiTest {
	private val device = StudioDevice("sim-1", "iPhone 17", TestPlatform.Ios, "26", DeviceStatus.Running)
	private val ready = ConnectionState.Ready("1", STUDIO_PROTOCOL_VERSION, listOf("chat.send", "devices.list", "reports.list", "repl.execute", AGENT_RUNS_CAPABILITY, TESTS_GENERATE_CAPABILITY))

	@Composable private fun Host(content: @Composable () -> Unit) {
		KmpTheme {
			CompositionLocalProvider(LocalReduceMotionEnabled provides true, LocalScreenshotLoader provides ScreenshotLoader.None, content = content)
		}
	}

	@Test
	fun providerFormValidatesUrlsAndPreservesTheExactSecret() = runComposeUiTest {
		val events = CallRecorder<StudioEvent>()
		var state by mutableStateOf(StudioState(providers = emptyList(), providerApiKeysLoaded = true, providerDraft = newProviderDraft().copy(name = "Work", model = "model", baseUrl = "invalid", apiKey = Secret("  exact key  "))))
		setContent { Host { SettingsContent(state) { events.record(it); state = state.reduce(it) } } }

		onNodeWithText("Save provider").performScrollTo().assertIsNotEnabled()
		onNodeWithText("Base URL").performScrollTo().replaceTextVerified("https://api.example.test/v1")
		onNodeWithText("Save provider").performScrollTo().assertIsEnabled().assertMinimumTouchTarget().performClick()

		val saved = events.values().filterIsInstance<StudioEvent.SaveProvider>().single().profile
		assertEquals("  exact key  ", saved.apiKey.value)
		assertEquals("https://api.example.test/v1", saved.baseUrl)
		assertEquals(newProviderDraft(), state.providerDraft)
	}

	@Test
	fun aLockedKeyRequiresSuccessfulRetryBeforeEditing() = runComposeUiTest {
		val profile = newProviderDraft().copy(id = "a", name = "Work", model = "model")
		val events = CallRecorder<StudioEvent>()
		var state by mutableStateOf(StudioState(providers = listOf(profile), providerApiKeysLoaded = true, providerApiKeyReadFailures = mapOf("a" to "Unlock the keychain and retry.")))
		setContent { Host { SettingsContent(state) { events.record(it); state = state.reduce(it) } } }

		onNodeWithText("Edit").performScrollTo().assertIsNotEnabled()
		onNodeWithText("Retry keychain access").performScrollTo().performClick()
		assertTrue(StudioEvent.RetryProviderApiKeys in events.values())
		runOnIdle { state = state.reduce(StudioEvent.ProviderApiKeysLoaded(mapOf("a" to Secret("stored-key")))) }
		onNodeWithText("Edit").performScrollTo().assertIsEnabled().performClick()

		assertEquals(Secret("stored-key"), state.providerDraft.apiKey)
	}

	@Test
	fun chatComposerKeepsShiftEnterAsANewlineAndEnterSends() = runComposeUiTest {
		val events = CallRecorder<StudioEvent>()
		var state by mutableStateOf(StudioState(connection = ready.copy(capabilities = listOf("chat.send")), selectedProviderId = "ollama"))
		setContent { Host { AiTestingContent(state) { events.record(it); state = state.reduce(it) } } }

		onNode(hasSetTextAction()).replaceTextVerified("Explore this screen")
		onNodeWithContentDescription("Ask Amoo").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
		assertTrue(events.values().none { it == StudioEvent.SendChat })
		assertTrue(state.chat.input.contains('\n'))
		waitForContentDescription("Send").assertIsEnabled().assertMinimumTouchTarget()
		onNodeWithContentDescription("Ask Amoo").performKeyInput { pressKey(Key.Enter) }

		assertEquals(1, events.values().count { it == StudioEvent.SendChat })
	}

	@Test
	fun guidanceUsesTheSharedComposerWithoutStartingAnotherRun() = runComposeUiTest {
		val events = CallRecorder<StudioEvent>()
		var state by mutableStateOf(StudioState(connection = ready, agentRun = AgentRunState("run-1", "Sign in")))
		setContent { Host { AiTestingContent(state) { events.record(it); state = state.reduce(it) } } }

		onNode(hasSetTextAction()).replaceTextVerified("Use the demo account")
		waitForContentDescription("Send guidance").performClick()
		onNodeWithText("Stop").performClick()

		assertTrue(StudioEvent.SteerAgentRun in events.values())
		assertTrue(StudioEvent.CancelAgentRun in events.values())
		assertTrue(StudioEvent.SendChat !in events.values())
	}

	@Test
	fun theSharedSplitterKeepsStudioBoundsAndSupportsAccessibilityAdjustment() = runComposeUiTest {
		var state by mutableStateOf(StudioState())
		setContent { Host { Workbench(state.workbenchSplit, { state = state.reduce(StudioEvent.ChangeWorkbenchSplit(it)) }, leading = { Text("Workflow") }, trailing = { Text("Device") }) } }

		onNodeWithContentDescription("Resize panes").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
		assertEquals(MAX_WORKBENCH_SPLIT, state.workbenchSplit)
		onNodeWithContentDescription("Resize panes").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
		assertEquals(MIN_WORKBENCH_SPLIT, state.workbenchSplit)
	}

	@Test
	fun liveDeviceImagesUseTheSharedKeyboardZoomAndReset() = runComposeUiTest {
		val mirror = DeviceMirrorState(status = MirrorStatus.Live, frame = DeviceFrame(1, ImageBitmap(40, 80), 40, 80))
		setContent { Host { DevicePane(mirror, device, {}) } }

		val image = onNodeWithContentDescription("Live view of iPhone 17")
		image.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
		image.performKeyInput { pressKey(Key.Equals) }
		image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 110%"))
		image.performKeyInput { pressKey(Key.Zero) }
		image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 100%"))
	}

	@Test
	fun approvalUsesSharedAccessibleControls() = runComposeUiTest {
		val events = CallRecorder<StudioEvent>()
		val state = StudioState(pendingApproval = PendingApproval("Erase app data?", "This removes the app's data.", ApprovedAction.ResetAppData, "Erase"))
		setContent { Host { AmooStudioApp(state) { events.record(it) } } }

		onNodeWithText("Cancel").assertMinimumTouchTarget().performClick()
		assertTrue(StudioEvent.ResolveApproval(false) in events.values())
		onNodeWithText("Erase").assertMinimumTouchTarget().performClick()
		assertTrue(StudioEvent.ResolveApproval(true) in events.values())
	}

	@Test
	fun sharedNavigationAndThemeControlsUpdateStudioState() = runComposeUiTest {
		var state by mutableStateOf(StudioState(providerApiKeysLoaded = true))
		setContent { Host { AmooStudioApp(state) { state = state.reduce(it) } } }

		onNode(hasText("Settings") and hasClickAction()).performClick()
		assertEquals(StudioSection.Settings, state.section)
		onNodeWithText("Dark").performScrollTo().performClick()
		assertEquals(ThemeMode.Dark, state.themeMode)
	}

	@Test
	fun theRunSegmentsKeepConversationAndActivityAvailable() = runComposeUiTest {
		val run = AgentRunState("run-1", "Sign in")
			.applying(AgentEvent("run-1", 0, AgentEventKind.SessionStarted, sessionId = "s-1"))
			.applying(AgentEvent("run-1", 1, AgentEventKind.AssistantMessage, text = "Signed in."))
		val state = StudioState(connection = ready, agentRun = run, chat = ChatState(messages = listOf(ChatMessage("u", ChatRole.User, "Sign in"))))
		setContent { Host { AiTestingContent(state) {} } }

		onNodeWithText("Signed in.").assertExists()
		onNodeWithText("Conversation").performClick()
		onNodeWithText("Sign in").assertExists()
		onNodeWithText("Run activity").performClick()
		onNodeWithText("Signed in.").assertExists()
	}

	@Test
	fun generatedStepsAndSourceResizeThroughSharedSplitters() = runComposeUiTest {
		val generated = GeneratedTestState(GenerationSource.AuthoredTest, loading = false, generated = GeneratedSource("Test.swift", "import XCTest", "swift"))
		var state by mutableStateOf(StudioState(generatedTest = generated))
		setContent { Host { GeneratedTestContent(state) { state = state.reduce(it) } } }

		onNodeWithContentDescription("Resize generated steps").performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
		onNodeWithContentDescription("Resize generated source").performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }

		assertEquals(0.4f, state.generatedTest?.operationsSplit)
		assertEquals(0.6f, state.generatedTest?.sourceSplit)
		onNodeWithContentDescription("Generated test source").assertExists()
	}

	@Test
	fun unreadablePinnedScreenshotsShowAnErrorInsteadOfLoadingForever() = runComposeUiTest {
		val mirror = DeviceMirrorState(pinned = PinnedScreenshot("Sign in", ScreenshotRef("/missing.png")))
		setContent { Host { DevicePane(mirror, device, {}) } }

		waitForText("Screenshot unavailable").assertExists()
		onNodeWithText("The screenshot file could not be read.").assertExists()
		onNodeWithText("Loading screenshot…").assertDoesNotExist()
	}

	@Test
	fun typedPlansNeedTheTypedRunCapabilityWithoutTheLegacyOne() = runComposeUiTest {
		val state = StudioState(
			connection = ready.copy(capabilities = listOf("tests.start", TESTS_GENERATE_CAPABILITY)),
			selectedDeviceId = device.id,
			test = AmooTest(steps = listOf(TestStep("step-0", "Tap Sign in", "Home is visible")), compiledPlan = CompiledToolPlan("studio", "1", toolOperations = listOf(ToolOperation("step-0", "tap_element", mapOf("id" to "login"))))),
		)
		val events = CallRecorder<StudioEvent>()
		setContent { Host { TestEditor(state) { events.record(it) } } }

		onNodeWithText("Run test").assertIsEnabled().performClick()
		assertTrue(StudioEvent.RunTest in events.values())
		onNodeWithText("Update Amoo to run this test's execution plan.").assertDoesNotExist()
	}

	@Test
	fun screenshotThumbnailsKeepKeyboardActivationAndAccessibleTargets() = runComposeUiTest {
		val entry = ConsoleEntry("entry", "tap_element id=login", "Tapped", screenshot = ScreenshotRef("/missing.png"))
		val state = StudioState(connection = ready, console = ConsoleState(entries = listOf(entry)))
		val events = CallRecorder<StudioEvent>()
		setContent { Host { ConsoleContent(state) { events.record(it) } } }

		val thumbnail = onNodeWithContentDescription("Screen after tap_element id=login")
		thumbnail.assertMinimumTouchTarget().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
		thumbnail.performKeyInput { pressKey(Key.Enter) }

		assertEquals(entry.screenshot, events.values().filterIsInstance<StudioEvent.PinScreenshot>().single().pinned.screenshot)
	}

	@Test
	fun narrowGeneratedViewsKeepStepsSourceAndScreenshotReachable() = runDesktopComposeUiTest(width = 800, height = 700) {
		val generated = GeneratedTestState(GenerationSource.AuthoredTest, loading = false, generated = GeneratedSource("Test.swift", "import XCTest", "swift"), test = AmooTest(compiledPlan = CompiledToolPlan("studio", "1", toolOperations = listOf(ToolOperation("step-0", "tap_element")))))
		var state by mutableStateOf(StudioState(generatedTest = generated))
		setContent { Host { GeneratedTestContent(state) { state = state.reduce(it) } } }

		onNodeWithContentDescription("Generated test source").assertExists()
		onNodeWithText("Steps").performClick()
		onNodeWithText("tap_element").performClick()
		assertEquals("step-0", state.generatedTest?.selectedOperationId)
		onNodeWithText("Screenshot").performClick()
		onNodeWithText("No screenshot").assertExists()
		onNodeWithText("Source").performClick()
		onNodeWithContentDescription("Generated test source").assertExists()
	}

	@Test
	fun migratedScreensRenderInLightDarkAndLargeRtlLayouts() = runDesktopComposeUiTest(width = 1440, height = 1000) {
		data class Appearance(val name: String, val theme: ThemeMode, val fontScale: Float, val direction: LayoutDirection)
		val appearances = listOf(Appearance("light", ThemeMode.Light, 1f, LayoutDirection.Ltr), Appearance("dark", ThemeMode.Dark, 1f, LayoutDirection.Ltr), Appearance("large-rtl", ThemeMode.Light, 2f, LayoutDirection.Rtl))
		var appearance by mutableStateOf(appearances.first())
		val generated = GeneratedTestState(GenerationSource.AuthoredTest, loading = false, generated = GeneratedSource("SignInTest.swift", "import XCTest\n\nfinal class SignInTest {}\n", "swift", emptyList()))
		val base = StudioState(connection = ready, devices = listOf(device), selectedDeviceId = device.id, appId = "dev.app", selectedProviderId = "ollama", providerApiKeysLoaded = true, generatedTest = generated, reports = listOf(TestReport("report-1", "Sign in", ReportStatus.Passed, "2026-10-24", summary = "The test passed.")), selectedReportId = "report-1")
		var state by mutableStateOf(base)
		setContent {
			CompositionLocalProvider(LocalDensity provides Density(1f, appearance.fontScale), LocalLayoutDirection provides appearance.direction, LocalReduceMotionEnabled provides true, LocalScreenshotLoader provides ScreenshotLoader.None) {
				AmooStudioApp(state) { state = state.reduce(it) }
			}
		}
		val output = File("build/reports/ui-integration").apply { mkdirs() }
		for (variant in appearances) {
			for (section in StudioSection.entries) {
				runOnIdle { appearance = variant; state = base.copy(section = section, themeMode = variant.theme) }
				waitForIdle()
				if (section == StudioSection.Chat) onNodeWithContentDescription("Start agent run").assertIsDisplayed()
				File(output, "${variant.name}-${section.name.lowercase()}.png").writeBytes(onRoot().captureToPng())
			}
		}
	}
}
