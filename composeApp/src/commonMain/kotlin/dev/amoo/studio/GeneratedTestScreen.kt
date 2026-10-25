package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arjangconsulting.codeeditor.CodeEditor
import com.arjangconsulting.codeeditor.CodeEditorLabels
import com.arjangconsulting.codeeditor.Diagnostic
import com.arjangconsulting.codeeditor.DiagnosticSeverity
import com.arjangconsulting.codeeditor.KotlinRules
import com.arjangconsulting.codeeditor.LineHighlight
import com.arjangconsulting.codeeditor.LineHighlightRole
import com.arjangconsulting.codeeditor.PlainText
import com.arjangconsulting.codeeditor.SwiftRules
import com.arjangconsulting.codeeditor.TextPosition
import com.arjangconsulting.codeeditor.TextRange as EditorRange
import io.github.maniramezan.kmpcomponents.CopyButton
import io.github.maniramezan.kmpcomponents.EmptyState
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.HorizontalSplitPane
import io.github.maniramezan.kmpcomponents.LoadingState
import io.github.maniramezan.kmpcomponents.NoticeCard
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.SelectableCard
import io.github.maniramezan.kmpcomponents.SegmentedContent
import io.github.maniramezan.kmpcomponents.SegmentedItem
import io.github.maniramezan.kmpcomponents.SplitPaneLabels
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeSingleChoiceSegmentedButtonRow
import io.github.maniramezan.kmpcomponents.focusNextOnTab
import io.github.maniramezan.kmpcomponents.kmpSpacing

/** Generated native test source beside the plan it came from and each step's screenshot. */
@Composable internal fun GeneratedTestContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val generated = state.generatedTest
	if (generated == null) {
		EmptyState("Nothing generated yet.", message = "Generate a test from a finished agent run in AI testing, a console recording, or the test open in Tests.", action = {
			PrimaryButton("Generate from ${state.test.name}", { onEvent(StudioEvent.GenerateTest(GenerationSource.AuthoredTest)) }, enabled = state.connection.supports(TESTS_GENERATE_CAPABILITY))
		})
		return
	}
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
		GenerationToolbar(generated, state.test.platform, onEvent)
		when {
			generated.loading -> LoadingState("Generating native test…", Modifier.fillMaxWidth().weight(1f))
			generated.problem != null -> GenerationProblemView(generated.problem, onEvent, Modifier.weight(1f))
			generated.generated != null -> GeneratedPanes(generated, generated.generated, onEvent, Modifier.fillMaxWidth().weight(1f))
		}
	}
}

@Composable private fun GeneratedPanes(generated: GeneratedTestState, source: GeneratedSource, onEvent: (StudioEvent) -> Unit, modifier: Modifier) {
	val steps: @Composable () -> Unit = { OperationList(generated, onEvent, Modifier.fillMaxSize()) }
	val code: @Composable () -> Unit = { GeneratedSourceView(generated, source, onEvent, Modifier.fillMaxSize()) }
	val screenshot: @Composable () -> Unit = { StepScreenshot(generated, Modifier.fillMaxSize()) }
	BoxWithConstraints(modifier) {
		if (maxWidth < 900.dp * LocalDensity.current.fontScale) {
			SegmentedContent(listOf(SegmentedItem("Steps"), SegmentedItem("Source"), SegmentedItem("Screenshot")), modifier = Modifier.fillMaxSize(), initialSelectedIndex = 1) { index, _ ->
				when (index) {
					0 -> steps()
					1 -> code()
					else -> screenshot()
				}
			}
		} else {
			HorizontalSplitPane(
				split = generated.operationsSplit,
				onSplitChange = { onEvent(StudioEvent.ChangeGeneratedTestLayout(operationsSplit = it)) },
				modifier = Modifier.fillMaxSize(),
				labels = SplitPaneLabels(handleDescription = "Resize generated steps"),
				leading = steps,
				trailing = {
					HorizontalSplitPane(
						split = generated.sourceSplit,
						onSplitChange = { onEvent(StudioEvent.ChangeGeneratedTestLayout(sourceSplit = it)) },
						modifier = Modifier.fillMaxSize(),
						labels = SplitPaneLabels(handleDescription = "Resize generated source"),
						leading = code,
						trailing = screenshot,
					)
				},
			)
		}
	}
}

const val TESTS_GENERATE_CAPABILITY = "tests.generate"

@Composable private fun GenerationToolbar(generated: GeneratedTestState, platform: TestPlatform, onEvent: (StudioEvent) -> Unit) {
	FlowLayout(Modifier.fillMaxWidth()) {
		Column(Modifier.widthIn(min = 200.dp, max = 400.dp)) {
			Text(generated.generated?.fileName ?: "Generating…", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
			Text(
				when (val source = generated.source) {
					is GenerationSource.Session -> "From recorded session: ${source.title}"
					GenerationSource.AuthoredTest -> "From the test open in Tests"
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		FormTextField(
			generated.testName,
			{ onEvent(StudioEvent.ChangeGeneratedTestOptions(testName = it)) },
			label = "Test name",
			singleLine = true,
			modifier = Modifier.width(220.dp).focusNextOnTab(),
		)
		if (platform == TestPlatform.Android) {
			ThemeSingleChoiceSegmentedButtonRow(UiToolkit.entries.map { it.label }, UiToolkit.entries.indexOf(generated.uiToolkit), { onEvent(StudioEvent.ChangeGeneratedTestOptions(uiToolkit = UiToolkit.entries[it])) })
		}
		SecondaryButton("Regenerate", { onEvent(StudioEvent.RegenerateTest(generated.allowIncomplete)) }, enabled = !generated.loading)
		CopyButton(generated.generated?.source.orEmpty(), enabled = generated.generated != null, onCopied = { onEvent(StudioEvent.ShowNotice("Copied generated test")) })
		PrimaryButton("Save as…", { onEvent(StudioEvent.SaveGeneratedTest) }, enabled = generated.generated != null)
	}
}

@Composable private fun GenerationProblemView(problem: GenerationProblem, onEvent: (StudioEvent) -> Unit, modifier: Modifier) {
	val title = when (problem.kind) {
		GenerationProblemKind.Incomplete -> "Some recorded steps could not be compiled"
		GenerationProblemKind.Redacted -> "The plan contains redacted values"
		GenerationProblemKind.NoEmitter -> "No generator for this platform"
	}
	Column(modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
		NoticeCard(problem.message, title = title, tone = StatusTone.ERROR)
		if (problem.kind == GenerationProblemKind.Incomplete) {
			Text("Generating anyway inserts a failing assertion where each missing step belongs.", style = MaterialTheme.typography.bodySmall)
			PrimaryButton("Generate anyway", { onEvent(StudioEvent.RegenerateTest(allowIncomplete = true)) })
		}
	}
}

@Composable private fun OperationList(generated: GeneratedTestState, onEvent: (StudioEvent) -> Unit, modifier: Modifier) {
	LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.extraSmall)) {
		item(key = "header") { SectionHeader("Steps") }
		items(generated.operations, key = { it.id }) { operation ->
			SelectableCard(operation.id == generated.selectedOperationId, { onEvent(StudioEvent.SelectGeneratedOperation(operation.id)) }, Modifier.fillMaxWidth()) {
				Text(operation.tool, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
				if (operation.arguments.isNotEmpty()) Text(operation.arguments.entries.joinToString(" ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall)
				generated.warningsFor(operation.id).forEach { warning ->
					StatusMessage("${warning.kind.name}: ${warning.reason}", tone = if (warning.kind.isSerious) StatusTone.ERROR else StatusTone.WARNING)
				}
				if (generated.span(operation.id) == null) Text("Not in the generated code", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}

@Composable private fun GeneratedSourceView(generated: GeneratedTestState, source: GeneratedSource, onEvent: (StudioEvent) -> Unit, modifier: Modifier) {
	val language = when (source.language) {
		"swift" -> SwiftRules
		"kotlin" -> KotlinRules
		else -> PlainText
	}
	val selectedSpan = generated.selectedOperationId?.let(generated::span)
	val highlights = remember(selectedSpan, source) {
		buildList {
			selectedSpan?.let { add(LineHighlight((it.startLine - 1)..(it.endLine - 1), LineHighlightRole.SELECTED)) }
			generated.operations.forEach { operation ->
				val span = generated.span(operation.id) ?: return@forEach
				if (operation.id != generated.selectedOperationId && generated.warningsFor(operation.id).any { it.kind.isSerious }) {
					add(LineHighlight((span.startLine - 1)..(span.endLine - 1), LineHighlightRole.WARNING))
				}
			}
		}
	}
	val diagnostics = remember(source, generated.warnings) { warningDiagnostics(generated, source) }
	ThemeCard(modifier, contentPadding = PaddingValues(0.dp)) {
		CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
			CodeEditor(
				value = source.source,
				onValueChange = {},
				modifier = Modifier.fillMaxSize().padding(MaterialTheme.kmpSpacing.extraSmall),
				language = language,
				readOnly = true,
				lineHighlights = highlights,
				diagnostics = diagnostics,
				revealLine = selectedSpan?.let { it.startLine - 1 },
				onLineClick = { line -> generated.operationAt(line + 1)?.let { onEvent(StudioEvent.SelectGeneratedOperation(it)) } },
				labels = CodeEditorLabels(editorDescription = "Generated test source"),
			)
		}
	}
}

/** Plan warnings drawn on the lines of the step they concern, or the first line when it produced none. */
internal fun warningDiagnostics(generated: GeneratedTestState, source: GeneratedSource): List<Diagnostic> {
	val lines = source.source.split('\n')
	return generated.warnings.mapNotNull { warning ->
		val span = generated.span("step-${warning.actionIndex}")
		val line = (span?.startLine ?: lines.indexOfFirst { "Uncompiled required action ${warning.actionIndex}" in it }.takeIf { it >= 0 }?.plus(1)) ?: return@mapNotNull null
		val text = lines.getOrNull(line - 1) ?: return@mapNotNull null
		val start = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
		Diagnostic(
			range = EditorRange(TextPosition(line - 1, start), TextPosition(line - 1, text.length)),
			message = "${warning.toolName}: ${warning.reason}",
			severity = if (warning.kind.isSerious) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
			source = "amoo",
		)
	}
}

@Composable private fun StepScreenshot(generated: GeneratedTestState, modifier: Modifier) {
	val pinned = generated.selectedOperationId?.let(generated.stepScreenshots::get)
	ThemeCard(modifier) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			if (pinned == null) EmptyState("No screenshot", message = if (generated.stepScreenshots.isEmpty()) "Screenshots appear here for tests generated from an agent run or a console recording." else "No screenshot for this step.")
			else StepScreenshotImage(pinned, "Screen after ${pinned.title}")
		}
	}
}
