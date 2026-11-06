package dev.amoo.studio

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.arjangconsulting.codeeditor.CodeEditorDefaults
import io.github.maniramezan.kmpcomponents.KmpTheme
import io.github.maniramezan.testcommons.compose.captureToPng
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ConsoleEditorIntegrationTest {
	@Composable private fun Host(assist: ReplAssist = ReplAssist.None, content: @Composable () -> Unit) {
		KmpTheme { CompositionLocalProvider(LocalReplAssist provides assist, content = content) }
	}

	private fun ComposeUiTest.command() = onNodeWithContentDescription("Amoo command")

	private fun ComposeUiTest.completionVisible(visible: Boolean) {
		waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("CodeEditorCompletionList").fetchSemanticsNodes().isNotEmpty() == visible }
	}

	private fun SemanticsNodeInteraction.invokeCompletion() = performKeyInput {
		withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) }
	}

	@Test
	fun repeatedHistoryNavigationDoesNotEchoControlledValues() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		var state by mutableStateOf(StudioState(console = ConsoleState(entries = listOf(
			ConsoleEntry("1", "devices list", "ok"),
			ConsoleEntry("2", "find_elements", "ok"),
			ConsoleEntry("3", "tap_element id=login", "ok"),
		))))
		setContent { Host { ConsoleInput(state, true) { events += it; state = state.reduce(it) } } }
		command().requestFocus()
		for ((key, index) in listOf(Key.DirectionUp to 2, Key.DirectionUp to 1, Key.DirectionUp to 0, Key.DirectionDown to 1, Key.DirectionDown to 2, Key.DirectionDown to 3, Key.DirectionUp to 2)) {
			command().performKeyInput { pressKey(key) }
			waitForIdle()
			assertEquals(index.takeIf { it < 3 }, state.console.historyIndex)
			assertEquals(state.console.entries.getOrNull(index)?.command.orEmpty(), state.console.input)
		}
		assertTrue(events.none { it is StudioEvent.ChangeConsoleInput })
		command().performTextInput("!")
		waitForIdle()
		assertEquals(null, state.console.historyIndex)
		assertEquals(1, events.count { it is StudioEvent.ChangeConsoleInput })
	}

	@Test
	fun enterAcceptsCompletionBeforeItCanSubmitACommand() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		var state by mutableStateOf(StudioState(console = ConsoleState(input = "devices li")))
		setContent { Host { ConsoleInput(state, true) { events += it; state = state.reduce(it) } } }
		command().requestFocus()
		command().invokeCompletion()
		completionVisible(true)
		command().performKeyInput { pressKey(Key.Enter) }
		completionVisible(false)
		assertEquals("devices list", state.console.input)
		assertFalse(StudioEvent.ExecuteConsoleCommand in events)
		command().performKeyInput { pressKey(Key.Enter) }
		waitForIdle()
		assertEquals(1, events.count { it == StudioEvent.ExecuteConsoleCommand })
		runOnIdle { state = state.copy(console = state.console.copy(operation = ConsoleOperation.Running)) }
		command().performKeyInput { pressKey(Key.Enter) }
		waitForIdle()
		assertEquals(1, events.count { it == StudioEvent.ExecuteConsoleCommand })
	}

	@Test
	fun invalidCommandsCannotSubmitWithEnter() = runComposeUiTest {
		val events = mutableListOf<StudioEvent>()
		val state = StudioState(
			connection = ConnectionState.Ready("1", STUDIO_PROTOCOL_VERSION, listOf("repl.execute")),
			console = ConsoleState(input = "swipe_in_direction direction=sideways"),
		)
		setContent { Host { ConsoleContent(state, events::add) } }
		onNodeWithText("Run").assertIsNotEnabled()
		command().requestFocus()
		command().performKeyInput { pressKey(Key.Enter) }
		assertFalse(StudioEvent.ExecuteConsoleCommand in events)
	}

	@Test
	fun runtimeChangesClosePublishedCompletionsWithoutChangingTheCommand() = runComposeUiTest {
		val requestedDevices = CopyOnWriteArrayList<String?>()
		var state by mutableStateOf(StudioState(console = ConsoleState(input = "tap_element id="), selectedDeviceId = "device-a"))
		val assist = object : ReplAssist {
			override suspend fun complete(line: String, cursor: Int): ReplCompletion {
				val device = state.selectedDeviceId
				requestedDevices += device
				return ReplCompletion(cursor, cursor, listOf(ReplCompletionItem("target-$device", "target-$device", kind = ReplCompletionKind.Element)))
			}
			override suspend fun validate(line: String): List<ReplDiagnostic> = emptyList()
		}
		setContent { Host(assist) { ConsoleInput(state, true) { state = state.reduce(it) } } }
		command().requestFocus()
		command().invokeCompletion()
		completionVisible(true)
		runOnIdle { state = state.reduce(StudioEvent.SelectDevice("device-b")) }
		completionVisible(false)
		assertEquals("tap_element id=", state.console.input)
		command().invokeCompletion()
		completionVisible(true)
		onNodeWithText("target-device-b").assertExists()
		assertEquals(listOf("device-a", "device-b"), requestedDevices.toList())
	}

	@Test
	fun aLateCompletionCannotReopenAfterExecutionChangesRuntimeContext() = runComposeUiTest {
		val started = CompletableDeferred<Unit>()
		val result = CompletableDeferred<ReplCompletion>()
		val returned = CompletableDeferred<Unit>()
		var state by mutableStateOf(StudioState(console = ConsoleState(input = "tap_element id=")))
		val assist = object : ReplAssist {
			override suspend fun complete(line: String, cursor: Int): ReplCompletion = withContext(NonCancellable) {
				started.complete(Unit)
				result.await().also { returned.complete(Unit) }
			}
			override suspend fun validate(line: String): List<ReplDiagnostic> = emptyList()
		}
		setContent { Host(assist) { ConsoleInput(state, true) { state = state.reduce(it) } } }
		command().requestFocus()
		command().invokeCompletion()
		waitUntil(timeoutMillis = 3_000) { started.isCompleted }
		runOnIdle { state = state.reduce(StudioEvent.ConsoleCommandStarted).reduce(StudioEvent.CancelConsoleCommand) }
		waitForIdle()
		result.complete(ReplCompletion(15, 15, listOf(ReplCompletionItem("stale-target", "stale-target", kind = ReplCompletionKind.Element))))
		waitUntil(timeoutMillis = 3_000) { returned.isCompleted }
		waitForIdle()
		completionVisible(false)
		assertEquals("tap_element id=", state.console.input)
		onNodeWithText("stale-target").assertDoesNotExist()
	}

	@Test
	fun largeStructuredResultsStayBoundedAndSupportReadOnlySelectionAndCopy() = runComposeUiTest {
		val text = "{\"data\":\"${"x".repeat(16_000)}\"}"
		val clipboard = object : Clipboard {
			var entry: ClipEntry? = null
			override suspend fun getClipEntry(): ClipEntry? = entry
			override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
		}
		setContent { Host { CompositionLocalProvider(LocalClipboard provides clipboard) { StructuredConsoleResult(text) } } }
		val editor = onNodeWithContentDescription("Structured command result")
		assertTrue(editor.fetchSemanticsNode().boundsInRoot.height <= 320f * density.density + 1f)
		editor.requestFocus()
		editor.performKeyInput {
			withKeyDown(if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft) {
				pressKey(Key.A)
				pressKey(Key.C)
			}
		}
		waitForIdle()
		assertEquals(text, runBlocking { (clipboard.getClipEntry()?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) })
		editor.performKeyInput { pressKey(Key.Backspace) }
		editor.performKeyInput {
			withKeyDown(if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft) {
				pressKey(Key.A)
				pressKey(Key.C)
			}
		}
		waitForIdle()
		assertEquals(text, runBlocking { (clipboard.getClipEntry()?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) })
		File("build/reports/editor-integration").mkdirs()
		File("build/reports/editor-integration/large-structured-result.png").writeBytes(onRoot().captureToPng())
	}

	@Test
	fun selectingAGeneratedStepRevealsAndHighlightsItsSourceAndClickRevealsTheStep() = runComposeUiTest {
		val source = GeneratedSource("Test.swift", (1..240).joinToString("\n") { "let value$it = $it" }, "swift", listOf(SourceMapEntry(0, "step-0", 2, 3), SourceMapEntry(1, "step-1", 200, 202)))
		var generated by mutableStateOf(GeneratedTestState(GenerationSource.AuthoredTest, loading = false, generated = source))
		val events = mutableListOf<StudioEvent>()
		var selectedColor = Color.Unspecified
		setContent {
			Host {
				val colors = CodeEditorDefaults.colors()
				selectedColor = colors.lineHighlightSelected.compositeOver(colors.background)
				GeneratedSourceView(generated, source, { events += it }, Modifier.size(600.dp, 300.dp))
			}
		}
		fun highlightedRows(): List<Int> {
			val pixels = onRoot().captureToImage().toPixelMap()
			return (0 until pixels.height).filter { y ->
				(100 until pixels.width).count { x ->
					val pixel = pixels[x, y]
					abs(pixel.red - selectedColor.red) < 0.004f && abs(pixel.green - selectedColor.green) < 0.004f && abs(pixel.blue - selectedColor.blue) < 0.004f
				} > pixels.width / 2
			}
		}
		assertTrue(highlightedRows().isEmpty())
		runOnIdle { generated = generated.copy(selectedOperationId = "step-1") }
		mainClock.advanceTimeBy(2_000)
		waitForIdle()
		val rows = highlightedRows()
		assertTrue(rows.size >= 30, "Selected source lines 200–202 must be revealed and visibly highlighted")
		File("build/reports/editor-integration").mkdirs()
		File("build/reports/editor-integration/generated-step-reveal.png").writeBytes(onRoot().captureToPng())
		onRoot().performMouseInput { click(Offset(30f, rows.first() + 3f)) }
		waitForIdle()
		assertTrue(StudioEvent.SelectGeneratedOperation("step-1") in events)
	}
}
