package dev.amoo.studio

import androidx.compose.foundation.clickable
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
	val canExecute = state.connection.supports("repl.execute")
	val suggestions = state.consoleSuggestions()
	val selectedSuggestion = suggestions.getOrNull(state.console.suggestionIndex.coerceAtMost((suggestions.size - 1).coerceAtLeast(0)))
	val validationError = state.consoleValidationError()
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
			items(state.console.entries, key = { it.id }) { entry ->
				Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
					Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						Text("> ${entry.command}", fontWeight = FontWeight.Bold)
						Text(entry.output, color = if (entry.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
					}
				}
			}
			if (state.console.operation == ConsoleOperation.Running) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Running command…") } }
		}
		if (suggestions.isNotEmpty()) {
			Text("Suggestions", style = MaterialTheme.typography.labelLarge)
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				suggestions.take(4).forEach { suggestion ->
					Card(
						Modifier.fillMaxWidth().clickable { onEvent(StudioEvent.ChooseConsoleSuggestion(suggestion.command)) },
						colors = CardDefaults.cardColors(containerColor = if (suggestion == selectedSuggestion) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
					) {
						Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
							Text(suggestion.command, Modifier.width(220.dp), fontWeight = FontWeight.Medium)
							Text(suggestion.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
				}
			}
		}
		OutlinedTextField(
			state.console.input,
			{ onEvent(StudioEvent.ChangeConsoleInput(it)) },
			label = { Text("Command") },
			placeholder = { Text("devices list") },
			supportingText = { Text(validationError ?: "↑/↓ selects suggestions or history · Tab completes · Enter runs") },
			isError = validationError != null,
			singleLine = true,
			modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				when (event.key) {
					Key.Tab -> selectedSuggestion?.let {
						onEvent(StudioEvent.ChooseConsoleSuggestion(it.command))
						true
					} ?: false
					Key.DirectionDown -> if (suggestions.isNotEmpty()) {
						onEvent(StudioEvent.MoveConsoleSuggestion(1)); true
					} else false
					Key.DirectionUp -> if (state.console.input.isBlank()) {
						onEvent(StudioEvent.NavigateConsoleHistory(-1)); true
					} else if (suggestions.isNotEmpty()) {
						onEvent(StudioEvent.MoveConsoleSuggestion(-1)); true
					} else false
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
			OutlinedButton({ onEvent(StudioEvent.AddTestPlanOperation(state.console.input)) }, enabled = validationError == null && parseToolCommand(state.console.input) != null && state.console.operation == ConsoleOperation.Idle) { Text("Add to test plan") }
			if (state.console.operation == ConsoleOperation.Running) OutlinedButton({ onEvent(StudioEvent.CancelConsoleCommand) }) { Text("Cancel") }
			else Button({ onEvent(StudioEvent.ExecuteConsoleCommand) }, enabled = canExecute && validationError == null && state.console.input.isNotBlank()) { Text("Run") }
		}
	}
}
