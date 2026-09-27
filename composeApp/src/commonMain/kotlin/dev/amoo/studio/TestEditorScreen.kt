package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun TestEditor(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val plan = state.test.compiledPlan
	val hasExecutablePlan = plan?.toolOperations?.isNotEmpty() == true || plan?.operations?.isNotEmpty() == true
	val backendSupportsPlan = if (plan?.toolOperations?.isNotEmpty() == true) state.connection.supports("tests.start") else state.connection.supports("tests.run")
	val canRun = backendSupportsPlan && state.selectedDeviceId != null && state.test.steps.any { it.instruction.isNotBlank() } && hasExecutablePlan
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Button({ onEvent(StudioEvent.NewTest) }) { Text("New") }
			OutlinedButton({ onEvent(StudioEvent.OpenTest) }) { Text("Open…") }
			Button({ onEvent(StudioEvent.SaveTest) }) { Text("Save") }
			OutlinedButton({ onEvent(StudioEvent.SaveTestAs) }) { Text("Save as…") }
			if (state.testExecution is TestExecution.Running) OutlinedButton({ onEvent(StudioEvent.CancelTestRun) }) { Text("Cancel run") }
			else Button({ onEvent(StudioEvent.RunTest) }, enabled = canRun) { Text("Run test") }
			val canExport = plan?.toolOperations?.isNotEmpty() == true && state.connection.supports("tests.export") && !state.testExportInProgress
			OutlinedButton({ onEvent(StudioEvent.ExportTest) }, enabled = canExport) { Text(if (state.testExportInProgress) "Exporting…" else "Export test") }
			Text((state.testPath ?: "Not saved") + if (state.isTestDirty) " • Modified" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		if (!state.connection.supports("tests.run")) Text("Test execution requires the tests.run capability from Amoo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		else if (plan?.toolOperations?.isNotEmpty() == true && !state.connection.supports("tests.start")) Text("Typed mobile plans require a newer Amoo with tests.start support.", color = MaterialTheme.colorScheme.error)
		else if (state.selectedDeviceId == null) Text("Choose a device before running this test.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		if (plan?.toolOperations?.isNotEmpty() == true && !state.connection.supports("tests.export")) Text("Exporting native test code requires the tests.export capability from Amoo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		if (state.testExecution is TestExecution.Running) {
			val execution = state.testExecution
			if (execution.totalOperations > 0) LinearProgressIndicator({ execution.currentOperation.toFloat() / execution.totalOperations }, Modifier.fillMaxWidth())
			else LinearProgressIndicator(Modifier.fillMaxWidth())
			Text(execution.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			OutlinedTextField(state.test.name, { onEvent(StudioEvent.ChangeTestName(it)) }, label = { Text("Test name") }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
			OutlinedTextField(state.test.description, { onEvent(StudioEvent.ChangeTestDescription(it)) }, label = { Text("What does this test cover?") }, minLines = 2, modifier = Modifier.fillMaxWidth().focusNextOnTab())
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				Text("Platform", fontWeight = FontWeight.Medium)
				TestPlatform.entries.filter(state.hostPlatform::supports).forEach { platform -> FilterChip(state.test.platform == platform, { onEvent(StudioEvent.ChangeTestPlatform(platform)) }, { Text(platform.label) }) }
			}
			Text("Steps", style = MaterialTheme.typography.titleLarge)
			state.test.steps.forEachIndexed { index, step -> key(step.id) { StepCard(index, step, state.test.steps.size > 1, onEvent) } }
			OutlinedButton({ onEvent(StudioEvent.AddTestStep) }) { Text("Add step") }
			HorizontalDivider()
			Text("Execution plan", style = MaterialTheme.typography.titleLarge)
			Text("Add verified Amoo actions and assertions. Authored steps remain the source of truth.", color = MaterialTheme.colorScheme.onSurfaceVariant)
			ToolOperationEditor(state.operationDraft, onEvent)
			val toolOperations = plan?.toolOperations.orEmpty()
			if (toolOperations.isEmpty() && plan?.operations.isNullOrEmpty()) Text("Add at least one action or assertion before running this test.", color = MaterialTheme.colorScheme.error)
			toolOperations.forEachIndexed { index, operation -> key(operation.id) {
				Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
					Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
						Column(Modifier.weight(1f)) {
							Text("${index + 1}. ${operation.tool}", fontWeight = FontWeight.SemiBold)
							if (operation.arguments.isNotEmpty()) Text(operation.arguments.entries.joinToString(" · ") { "${it.key}=${it.value}" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
						TextButton({ onEvent(StudioEvent.RemoveToolOperation(operation.id)) }) { Text("Remove") }
					}
				}
			} }
			val operations = plan?.operations.orEmpty()
			if (operations.isNotEmpty()) Text("Legacy console operations", style = MaterialTheme.typography.titleMedium)
			operations.forEachIndexed { index, operation -> key("plan-$index-$operation") {
				Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
					Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
						Text("${index + 1}. $operation", Modifier.weight(1f))
						TextButton({ onEvent(StudioEvent.RemoveTestPlanOperation(index)) }) { Text("Remove") }
					}
				}
			} }
			Spacer(Modifier.height(16.dp))
		}
	}
}

@Composable
private fun ToolOperationEditor(draft: ToolOperationDraft, onEvent: (StudioEvent) -> Unit) {
	var expanded by remember { mutableStateOf(false) }
	val definition = TOOL_CATALOG.first { it.name == draft.tool }
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text("Add operation", style = MaterialTheme.typography.titleMedium)
			Box {
				OutlinedButton({ expanded = true }) { Text(definition.name) }
				DropdownMenu(expanded, { expanded = false }) {
					TOOL_CATALOG.forEach { tool -> DropdownMenuItem(
						text = { Column { Text(tool.name); Text(tool.description, style = MaterialTheme.typography.bodySmall) } },
						onClick = { expanded = false; onEvent(StudioEvent.ChangeToolOperationType(tool.name)) },
					) }
				}
			}
			Text(definition.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
			definition.arguments.forEach { argument ->
				OutlinedTextField(
					value = draft.arguments[argument.name].orEmpty(),
					onValueChange = { onEvent(StudioEvent.ChangeToolOperationArgument(argument.name, it)) },
					label = { Text(argument.label + if (argument.required) " *" else "") },
					placeholder = argument.placeholder.takeIf(String::isNotBlank)?.let { { Text(it) } },
					singleLine = true,
					modifier = Modifier.fillMaxWidth().focusNextOnTab(),
				)
			}
			val validationError = draft.validationError()
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
				validationError?.let { Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
				Button({ onEvent(StudioEvent.AddToolOperation) }, enabled = validationError == null) { Text("Add operation") }
			}
		}
	}
}

@Composable private fun StepCard(index: Int, step: TestStep, canRemove: Boolean, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Text("Step ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
				if (canRemove) TextButton({ onEvent(StudioEvent.RemoveTestStep(step.id)) }) { Text("Remove") }
			}
			OutlinedTextField(step.instruction, { onEvent(StudioEvent.ChangeTestStep(step.id, instruction = it)) }, label = { Text("Action or instruction") }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
			OutlinedTextField(step.expected, { onEvent(StudioEvent.ChangeTestStep(step.id, expected = it)) }, label = { Text("Expected result") }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
		}
	}
}
