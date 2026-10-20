package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
			items(state.console.entries, key = { it.id }) { entry -> ConsoleEntryCard(entry, onEvent) }
			if (state.console.operation == ConsoleOperation.Running) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Running command…") } }
		}
		OutlinedTextField(
			state.console.input,
			{ onEvent(StudioEvent.ChangeConsoleInput(it)) },
			label = { Text("Command") },
			placeholder = { Text("devices list") },
			supportingText = { Text(validationError ?: "↑/↓ history · Enter runs") },
			isError = validationError != null,
			singleLine = true,
			modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				when (event.key) {
					Key.DirectionDown -> { onEvent(StudioEvent.NavigateConsoleHistory(1)); true }
					Key.DirectionUp -> { onEvent(StudioEvent.NavigateConsoleHistory(-1)); true }
					Key.Escape -> { onEvent(StudioEvent.ChangeConsoleInput("")); true }
					Key.Enter -> if (canExecute && validationError == null && state.console.input.isNotBlank() && state.console.operation == ConsoleOperation.Idle) {
						onEvent(StudioEvent.ExecuteConsoleCommand)
						true
					} else false
					else -> false
				}
			},
		)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
			OutlinedButton({ onEvent(StudioEvent.AddTestPlanOperation(state.console.input)) }, enabled = parseToolCommand(state.console.input, state.toolCatalog) != null && state.console.operation == ConsoleOperation.Idle) { Text("Add to test plan") }
			if (state.console.operation == ConsoleOperation.Running) OutlinedButton({ onEvent(StudioEvent.CancelConsoleCommand) }) { Text("Cancel") }
			else Button({ onEvent(StudioEvent.ExecuteConsoleCommand) }, enabled = canExecute && validationError == null && state.console.input.isNotBlank()) { Text("Run") }
		}
	}
}

@Composable private fun ConsoleEntryCard(entry: ConsoleEntry, onEvent: (StudioEvent) -> Unit) {
	val container = if (entry.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
		Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("> ${entry.command}", fontWeight = FontWeight.Bold)
				Text(entry.output, color = if (entry.failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
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
