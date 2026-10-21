package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import com.arjangconsulting.codeeditor.CodeEditor
import com.arjangconsulting.codeeditor.CodeSnippet
import com.arjangconsulting.codeeditor.JsonRules
import com.arjangconsulting.codeeditor.TokenRules
import androidx.compose.ui.text.style.TextOverflow
import com.arjangconsulting.codeeditor.CodeEditorLabels
import com.arjangconsulting.codeeditor.CodeEditorOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun ConsoleContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Workbench(
		split = state.workbenchSplit,
		onSplitChange = { onEvent(StudioEvent.ChangeWorkbenchSplit(it)) },
		leading = { ConsolePanel(state, onEvent) },
		trailing = { DevicePane(state.mirror, state.devices.firstOrNull { it.id == state.selectedDeviceId }, onEvent) },
	)
}

@Composable private fun ConsolePanel(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val rules = remember(state.toolCatalog) { amooCommandRules(state.toolCatalog) }
	val canExecute = state.connection.supports("repl.execute")
	val parse = state.consoleParse()
	val validationError = parse.diagnostics.firstOrNull { it.severity == ReplSeverity.Error }?.message
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text("Amoo command console", style = MaterialTheme.typography.titleLarge)
				Text("Run structured Amoo operations without leaving Studio.", color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			TextButton({ onEvent(StudioEvent.ClearConsole) }, enabled = state.console.entries.isNotEmpty()) { Text("Clear history") }
		}
		if (!canExecute) Text("The connected Amoo version does not advertise repl.execute. Update Amoo to enable command execution.", color = MaterialTheme.colorScheme.error)
		LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			if (state.console.entries.isEmpty()) item { Text("Type a command below or choose a suggestion. Destructive app operations remain in Devices so Studio can request explicit approval.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
			items(state.console.entries, key = { it.id }) { entry -> ConsoleEntryCard(entry, rules, onEvent) }
			if (state.console.operation == ConsoleOperation.Running) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Running command…") } }
		}
		ConsoleInput(state, canExecute && validationError == null, onEvent)
		Text(
			validationError ?: "Tab or Ctrl+Space completes · ↑/↓ history · Enter runs",
			style = MaterialTheme.typography.bodySmall,
			color = if (validationError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
			OutlinedButton({ onEvent(StudioEvent.AddTestPlanOperation(state.console.input)) }, enabled = parseToolCommand(state.console.input, state.toolCatalog) != null && state.console.operation == ConsoleOperation.Idle) { Text("Add to test plan") }
			if (state.console.operation == ConsoleOperation.Running) OutlinedButton({ onEvent(StudioEvent.CancelConsoleCommand) }) { Text("Cancel") }
			else Button({ onEvent(StudioEvent.ExecuteConsoleCommand) }, enabled = canExecute && validationError == null && state.console.input.isNotBlank()) { Text("Run") }
		}
	}
}

@Composable private fun ConsoleEntryCard(entry: ConsoleEntry, rules: TokenRules, onEvent: (StudioEvent) -> Unit) {
	val container = if (entry.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow
	var showDetails by remember(entry.id) { mutableStateOf(false) }
	val details = remember(entry.structuredContent) { entry.structuredContent?.let(::prettyJson) }
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
		Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				CodeSnippet(entry.command, language = rules, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(entry.output, color = if (entry.failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
				if (details != null) {
					TextButton({ showDetails = !showDetails }, contentPadding = PaddingValues(0.dp)) { Text(if (showDetails) "Hide details" else "Show details") }
					if (showDetails) CodeSnippet(details, language = JsonRules, softWrap = true)
				}
			}
			entry.screenshot?.let { screenshot ->
				ScreenshotThumbnail(
					screenshot,
					description = "Screen after ${entry.command}",
					onClick = { onEvent(StudioEvent.PinScreenshot(PinnedScreenshot(entry.command, screenshot, entry.gesturePoint))) },
					modifier = Modifier.size(width = 72.dp, height = 132.dp),
				)
			}
		}
	}
}

/**
 * The console line: a single-line code editor with the console language's highlighting, completion
 * (including elements on the device's screen when Amoo supports it), and inline diagnostics.
 */
@Composable private fun ConsoleInput(state: StudioState, canRun: Boolean, onEvent: (StudioEvent) -> Unit) {
	val assist = LocalReplAssist.current
	val current by rememberUpdatedState(state)
	val service = remember(assist) { ConsoleLanguageService({ current.toolCatalog }, { current.devices }, assist) }
	val rules = remember(state.toolCatalog) { amooCommandRules(state.toolCatalog) }
	val running = state.console.operation == ConsoleOperation.Running
	OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
		CodeEditor(
			value = state.console.input,
			onValueChange = { onEvent(StudioEvent.ChangeConsoleInput(it)) },
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
			language = rules,
			languageService = service,
			readOnly = running,
			singleLine = true,
			onSubmit = { if (canRun && it.isNotBlank() && !running) onEvent(StudioEvent.ExecuteConsoleCommand) },
			onHistory = { onEvent(StudioEvent.NavigateConsoleHistory(it)) },
			labels = CodeEditorLabels(editorDescription = "Amoo command"),
			options = CodeEditorOptions(highlightCurrentLine = false),
		)
	}
}

