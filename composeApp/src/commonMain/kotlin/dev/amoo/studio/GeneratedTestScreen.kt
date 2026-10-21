package dev.amoo.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/** Generated native test source beside the plan it came from and each step's screenshot. */
@Composable internal fun GeneratedTestContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val generated = state.generatedTest
	if (generated == null) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Text("Nothing generated yet.", style = MaterialTheme.typography.titleMedium)
			Text("Generate a test from a finished agent run in AI testing, a console recording, or the test open in Tests.", color = MaterialTheme.colorScheme.onSurfaceVariant)
			Button({ onEvent(StudioEvent.GenerateTest(GenerationSource.AuthoredTest)) }, enabled = state.connection.supports(TESTS_GENERATE_CAPABILITY)) { Text("Generate from ${state.test.name}") }
		}
		return
	}
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		GenerationToolbar(generated, state.test.platform, onEvent)
		when {
			generated.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
			generated.problem != null -> GenerationProblemView(generated.problem, onEvent, Modifier.weight(1f))
			generated.generated != null -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				OperationList(generated, onEvent, Modifier.width(260.dp).fillMaxHeight())
				GeneratedSourceView(generated, generated.generated, onEvent, Modifier.weight(1f).fillMaxHeight())
				StepScreenshot(generated, Modifier.width(240.dp).fillMaxHeight())
			}
		}
	}
}

const val TESTS_GENERATE_CAPABILITY = "tests.generate"

@Composable private fun GenerationToolbar(generated: GeneratedTestState, platform: TestPlatform, onEvent: (StudioEvent) -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Column(Modifier.weight(1f)) {
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
		OutlinedTextField(
			generated.testName,
			{ onEvent(StudioEvent.ChangeGeneratedTestOptions(testName = it)) },
			label = { Text("Test name") },
			singleLine = true,
			modifier = Modifier.width(220.dp).focusNextOnTab(),
		)
		if (platform == TestPlatform.Android) {
			UiToolkit.entries.forEach { toolkit ->
				FilterChip(generated.uiToolkit == toolkit, { onEvent(StudioEvent.ChangeGeneratedTestOptions(uiToolkit = toolkit)) }, { Text(toolkit.label) })
			}
		}
		OutlinedButton({ onEvent(StudioEvent.RegenerateTest(generated.allowIncomplete)) }, enabled = !generated.loading) { Text("Regenerate") }
		OutlinedButton({ onEvent(StudioEvent.CopyGeneratedTest) }, enabled = generated.generated != null) { Text("Copy") }
		Button({ onEvent(StudioEvent.SaveGeneratedTest) }, enabled = generated.generated != null) { Text("Save as…") }
	}
}

@Composable private fun GenerationProblemView(problem: GenerationProblem, onEvent: (StudioEvent) -> Unit, modifier: Modifier) {
	Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text(
				when (problem.kind) {
					GenerationProblemKind.Incomplete -> "Some recorded steps could not be compiled"
					GenerationProblemKind.Redacted -> "The plan contains redacted values"
					GenerationProblemKind.NoEmitter -> "No generator for this platform"
				},
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onErrorContainer,
			)
			Text(problem.message, color = MaterialTheme.colorScheme.onErrorContainer)
			if (problem.kind == GenerationProblemKind.Incomplete) {
				Text("Generating anyway inserts a failing assertion where each missing step belongs.", style = MaterialTheme.typography.bodySmall)
				Button({ onEvent(StudioEvent.RegenerateTest(allowIncomplete = true)) }) { Text("Generate anyway") }
			}
		}
	}
}

@Composable private fun OperationList(generated: GeneratedTestState, onEvent: (StudioEvent) -> Unit, modifier: Modifier) {
	LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
		item(key = "header") { Text("Steps", style = MaterialTheme.typography.titleSmall) }
		items(generated.operations, key = { it.id }) { operation ->
			val selected = operation.id == generated.selectedOperationId
			val warnings = generated.warningsFor(operation.id)
			Card(
				Modifier.fillMaxWidth().semantics { this.selected = selected }.clickable(onClickLabel = "Show generated lines") { onEvent(StudioEvent.SelectGeneratedOperation(operation.id)) },
				colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
			) {
				Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(operation.tool, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
					if (operation.arguments.isNotEmpty()) {
						Text(operation.arguments.entries.joinToString(" ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
					}
					warnings.forEach { warning ->
						Text(
							"${warning.kind.name}: ${warning.reason}",
							style = MaterialTheme.typography.labelSmall,
							color = if (warning.kind.isSerious) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
							maxLines = 3,
						)
					}
					if (generated.span(operation.id) == null) Text("Not in the generated code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
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
	Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
		CodeEditor(
			value = source.source,
			onValueChange = {},
			modifier = Modifier.fillMaxSize().padding(4.dp),
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
	Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
		Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
			if (pinned == null) {
				Text(
					if (generated.stepScreenshots.isEmpty()) "Screenshots appear here for tests generated from an agent run or a console recording." else "No screenshot for this step.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				StepScreenshotImage(pinned)
			}
		}
	}
}

@Composable private fun StepScreenshotImage(pinned: PinnedScreenshot) {
	val loader = LocalScreenshotLoader.current
	val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pinned.screenshot.path) { value = loader.load(pinned.screenshot.path, 1_200) }
	val bitmap = image ?: run { CircularProgressIndicator(); return }
	ZoomableBitmap(bitmap, "Screen after ${pinned.title}", overlay = pinned.gesturePoint?.let { pointInImage(it, pinned.screenshot, bitmap) })
}
