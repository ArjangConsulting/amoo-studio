package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.maniramezan.kmpcomponents.ContentRow
import io.github.maniramezan.kmpcomponents.EmptyState
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.MenuPicker
import io.github.maniramezan.kmpcomponents.PillChip
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.ProgressIndicator
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeHorizontalDivider
import io.github.maniramezan.kmpcomponents.ThemeSingleChoiceSegmentedButtonRow
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.focusNextOnTab
import io.github.maniramezan.kmpcomponents.kmpSpacing

@Composable internal fun TestEditor(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val plan = state.test.compiledPlan
	val hasExecutablePlan = plan?.toolOperations?.isNotEmpty() == true || plan?.operations?.isNotEmpty() == true
	val backendSupportsPlan = if (plan?.toolOperations?.isNotEmpty() == true) state.connection.supports("tests.start") else state.connection.supports("tests.run")
	val canRun = backendSupportsPlan && state.selectedDeviceId != null && state.test.steps.any { it.instruction.isNotBlank() } && hasExecutablePlan
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
		FlowLayout {
			PrimaryButton("New", { onEvent(StudioEvent.NewTest) })
			SecondaryButton("Open…", { onEvent(StudioEvent.OpenTest) })
			PrimaryButton("Save", { onEvent(StudioEvent.SaveTest) })
			SecondaryButton("Save as…", { onEvent(StudioEvent.SaveTestAs) })
			if (state.testExecution is TestExecution.Running) SecondaryButton("Cancel run", { onEvent(StudioEvent.CancelTestRun) })
			else PrimaryButton("Run test", { onEvent(StudioEvent.RunTest) }, enabled = canRun)
			val canExport = plan?.toolOperations?.isNotEmpty() == true && state.connection.supports("tests.export") && !state.testExportInProgress
			if (state.connection.supports(TESTS_GENERATE_CAPABILITY)) {
				SecondaryButton("Generate test", { onEvent(StudioEvent.GenerateTest(GenerationSource.AuthoredTest)) }, enabled = plan?.toolOperations?.isNotEmpty() == true)
			} else {
				SecondaryButton(if (state.testExportInProgress) "Exporting…" else "Export test", { onEvent(StudioEvent.ExportTest) }, enabled = canExport)
			}
			Text((state.testPath ?: "Not saved") + if (state.isTestDirty) " • Modified" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		val runRequirement = when {
			state.connection !is ConnectionState.Ready -> "Connect to Amoo before running this test."
			!backendSupportsPlan -> "Update Amoo to run this test's execution plan."
			state.selectedDeviceId == null -> "Choose a device before running this test."
			else -> null
		}
		runRequirement?.let { StatusMessage(it, tone = StatusTone.INFO) }
		if (plan?.toolOperations?.isNotEmpty() == true && !state.connection.supports(TESTS_GENERATE_CAPABILITY) && !state.connection.supports("tests.export")) StatusMessage("Update Amoo to export native test code.", tone = StatusTone.WARNING)
		if (state.testExecution is TestExecution.Running) {
			val execution = state.testExecution
			ProgressIndicator(execution.message, Modifier.fillMaxWidth(), progress = if (execution.totalOperations > 0) execution.currentOperation.toFloat() / execution.totalOperations else null)
		}
		Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
			FormTextField(state.test.name, { onEvent(StudioEvent.ChangeTestName(it)) }, label = "Test name", modifier = Modifier.fillMaxWidth().focusNextOnTab())
			FormTextField(state.test.description, { onEvent(StudioEvent.ChangeTestDescription(it)) }, label = "What does this test cover?", modifier = Modifier.fillMaxWidth().focusNextOnTab(), singleLine = false)
			FlowLayout {
				Text("Platform", fontWeight = FontWeight.Medium)
				val platforms = TestPlatform.entries.filter(state.hostPlatform::supports)
				ThemeSingleChoiceSegmentedButtonRow(platforms.map { it.label }, platforms.indexOf(state.test.platform), { onEvent(StudioEvent.ChangeTestPlatform(platforms[it])) })
			}
			SectionHeader("Steps", titleStyle = MaterialTheme.typography.titleLarge)
			state.test.steps.forEachIndexed { index, step -> key(step.id) { StepCard(index, step, state.test.steps.size > 1, onEvent) } }
			SecondaryButton("Add step", { onEvent(StudioEvent.AddTestStep) })
			ThemeHorizontalDivider()
			SectionHeader("Execution plan", titleStyle = MaterialTheme.typography.titleLarge)
			Text("Add verified Amoo actions and assertions. Authored steps remain the source of truth.", color = MaterialTheme.colorScheme.onSurfaceVariant)
			ToolOperationEditor(state.operationDraft, state.toolCatalog, onEvent)
			val toolOperations = plan?.toolOperations.orEmpty()
			if (toolOperations.isEmpty() && plan?.operations.isNullOrEmpty()) EmptyState("No executable actions yet", message = "Add at least one action or assertion before running this test.")
			toolOperations.forEachIndexed { index, operation -> key(operation.id) {
				ContentRow("${index + 1}. ${operation.tool}", supportingText = operation.arguments.takeIf { it.isNotEmpty() }?.entries?.joinToString(" · ") { "${it.key}=${it.value}" }, trailingContent = {
					ThemeTextButton("Remove", { onEvent(StudioEvent.RemoveToolOperation(operation.id)) }, destructive = true)
				})
			} }
			val operations = plan?.operations.orEmpty()
			if (operations.isNotEmpty()) SectionHeader("Legacy console operations")
			operations.forEachIndexed { index, operation -> key("plan-$index-$operation") {
				ContentRow("${index + 1}. $operation", trailingContent = { ThemeTextButton("Remove", { onEvent(StudioEvent.RemoveTestPlanOperation(index)) }, destructive = true) })
			} }
			Spacer(Modifier.height(16.dp))
		}
	}
}

@Composable
private fun ToolOperationEditor(draft: ToolOperationDraft, catalog: ToolCatalog, onEvent: (StudioEvent) -> Unit) {
	val tools = catalog.planTools
	val definition = catalog.tool(draft.tool) ?: tools.firstOrNull() ?: return
	val validationError = draft.validationError(catalog)
	SectionCard("New operation", Modifier.fillMaxWidth(), action = {
		PrimaryButton("Add operation", { onEvent(StudioEvent.AddToolOperation) }, enabled = validationError == null)
	}) {
		MenuPicker(tools, definition, { onEvent(StudioEvent.ChangeToolOperationType(it.name)) }, "Operation", { it.name }, Modifier.fillMaxWidth())
		Text(definition.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
		definition.arguments.forEach { argument -> key(argument.name) { ToolArgumentField(argument, draft.arguments[argument.name].orEmpty(), onEvent) } }
		validationError?.let { StatusMessage(it, tone = StatusTone.ERROR) }
	}
}

/** An argument input: chips for an exhaustive value set, otherwise a text field that lists known values as its placeholder. */
@Composable
private fun ToolArgumentField(argument: ToolArgumentSchema, value: String, onEvent: (StudioEvent) -> Unit) {
	val label = argument.name + if (argument.required) " *" else ""
	val values = argument.values.orEmpty()
	if (argument.valuesAreExhaustive && values.isNotEmpty()) {
		Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.extraSmall)) {
			Text(label, style = MaterialTheme.typography.labelLarge)
			FlowLayout {
				values.forEach { option ->
					PillChip(option, value.equals(option, ignoreCase = true), onClick = { onEvent(StudioEvent.ChangeToolOperationArgument(argument.name, if (value.equals(option, ignoreCase = true)) "" else option)) })
				}
			}
		}
	} else {
		FormTextField(
			value = value,
			onValueChange = { onEvent(StudioEvent.ChangeToolOperationArgument(argument.name, it)) },
			label = label,
			placeholder = values.takeIf { it.isNotEmpty() }?.joinToString(", "),
			supportingText = argument.description.takeIf(String::isNotBlank),
			singleLine = true,
			modifier = Modifier.fillMaxWidth().focusNextOnTab(),
		)
	}
}

@Composable private fun StepCard(index: Int, step: TestStep, canRemove: Boolean, onEvent: (StudioEvent) -> Unit) {
	SectionCard("Step ${index + 1}", Modifier.fillMaxWidth(), action = {
		if (canRemove) ThemeTextButton("Remove", { onEvent(StudioEvent.RemoveTestStep(step.id)) }, destructive = true)
	}) {
		FormTextField(step.instruction, { onEvent(StudioEvent.ChangeTestStep(step.id, instruction = it)) }, "Action or instruction", modifier = Modifier.fillMaxWidth().focusNextOnTab(), singleLine = false)
		FormTextField(step.expected, { onEvent(StudioEvent.ChangeTestStep(step.id, expected = it)) }, "Expected result", modifier = Modifier.fillMaxWidth().focusNextOnTab(), singleLine = false)
	}
}
