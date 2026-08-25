package dev.amoo.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import io.github.maniramezan.kmpcomponents.KmpTheme
import io.github.maniramezan.kmpcomponents.ThemeMode as KmpThemeMode

@Composable
fun AmooStudioApp(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	KmpTheme(state.themeMode.toKmpThemeMode()) {
		Surface(Modifier.fillMaxSize()) {
			Row(Modifier.fillMaxSize()) {
				NavigationSidebar(state.section, onEvent)
				Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
					Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
						Text(state.section.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
						state.notice?.let { AssistChip(onClick = { onEvent(StudioEvent.ShowNotice(null)) }, label = { Text(it) }) }
					}
					when (state.section) {
						StudioSection.Overview -> OverviewContent(state, onEvent)
						StudioSection.Tests -> TestEditor(state, onEvent)
						StudioSection.Devices -> DevicesContent(state, onEvent)
						StudioSection.Chat -> AiTestingContent(state, onEvent)
						StudioSection.Console -> ConsoleContent(state, onEvent)
						StudioSection.Reports -> ReportsContent(state, onEvent)
						StudioSection.Settings -> SettingsContent(state, onEvent)
					}
				}
			}
			state.pendingApproval?.let { approval -> AlertDialog(
				onDismissRequest = { onEvent(StudioEvent.ResolveApproval(false)) },
				title = { Text(approval.title) }, text = { Text(approval.message) },
				confirmButton = { Button({ onEvent(StudioEvent.ResolveApproval(true)) }) { Text(approval.confirmLabel) } },
				dismissButton = { TextButton({ onEvent(StudioEvent.ResolveApproval(false)) }) { Text("Cancel") } },
			) }
			state.createDevice?.let { form -> CreateDeviceDialog(state, form, onEvent) }
		}
	}
}

private fun ThemeMode.toKmpThemeMode(): KmpThemeMode = when (this) {
	ThemeMode.System -> KmpThemeMode.SYSTEM
	ThemeMode.Light -> KmpThemeMode.LIGHT
	ThemeMode.Dark -> KmpThemeMode.DARK
}

@Composable private fun DevicesContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }
	val canListDevices = state.connection.supports("devices.list")
	val canStartDevices = state.connection.supports("devices.start")
	val canBuild = state.connection.supports("apps.buildInstallRun")
	val canReinstall = state.connection.supports("apps.reinstallRun")
	val canResetData = state.connection.supports("apps.resetData")
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Button({ onEvent(StudioEvent.RefreshDevices) }, enabled = canListDevices && state.deviceOperation is DeviceOperation.Idle) { Text("Refresh devices") }
			OutlinedButton({ onEvent(StudioEvent.RequestCreateDevice) }, enabled = state.connection.supports("devices.create") && state.deviceOperation is DeviceOperation.Idle) { Text("Create device") }
			if (state.deviceOperation is DeviceOperation.Working) { CircularProgressIndicator(Modifier.size(20.dp)); Text(state.deviceOperation.message) }
		}
		if (!canListDevices) Text("Device discovery is unavailable in the connected Amoo version.", color = MaterialTheme.colorScheme.error)
		Text("Running", style = MaterialTheme.typography.titleLarge)
		val running = state.devices.filter { it.status == DeviceStatus.Running }
		if (running.isEmpty()) Text("No running simulators, emulators, or connected devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		running.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, false, onEvent) } }
		Text("Available to start", style = MaterialTheme.typography.titleLarge)
		val available = state.devices.filter { it.status == DeviceStatus.Available }
		if (available.isEmpty()) Text(if (state.hostPlatform == HostPlatform.MacOS) "No stopped simulators or emulators found. Install a runtime in Xcode or create an Android Virtual Device." else "No stopped Android emulators found. Create an Android Virtual Device and refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		available.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, canStartDevices, onEvent) } }
		HorizontalDivider()
		Text("App project", style = MaterialTheme.typography.titleLarge)
		OutlinedTextField(state.projectPath, { onEvent(StudioEvent.ChangeProjectPath(it)) }, label = { Text(if (state.hostPlatform == HostPlatform.MacOS) "Xcode or Gradle project path" else "Gradle project path") }, trailingIcon = { TextButton({ onEvent(StudioEvent.ChooseProjectPath) }) { Text("Choose…") } }, modifier = Modifier.fillMaxWidth())
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			OutlinedTextField(state.appId, { onEvent(StudioEvent.ChangeAppId(it)) }, label = { Text("Bundle ID / application ID") }, modifier = Modifier.weight(1f))
			OutlinedTextField(state.schemeOrModule, { onEvent(StudioEvent.ChangeSchemeOrModule(it)) }, label = { Text(if (selected?.platform == TestPlatform.Android) "Gradle module" else "Xcode scheme") }, modifier = Modifier.weight(1f))
		}
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Button({ onEvent(StudioEvent.BuildInstallAndRun) }, enabled = canBuild && selected != null && state.hostPlatform.supports(selected.platform) && state.projectPath.isNotBlank() && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle) { Text("Build, install & run") }
			OutlinedButton({ onEvent(StudioEvent.ReinstallAndRun) }, enabled = canReinstall && selected != null && state.hostPlatform.supports(selected.platform) && state.lastBuildArtifact != null && state.deviceOperation is DeviceOperation.Idle) { Text("Reinstall without building") }
			OutlinedButton({ onEvent(StudioEvent.RequestResetAppData) }, enabled = canResetData && selected != null && state.hostPlatform.supports(selected.platform) && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Erase app data") }
		}
		if (selected != null && !state.hostPlatform.supports(selected.platform)) Text("${selected.platform.label} workflows require macOS. Amoo Studio on ${state.hostPlatform.label} supports Android targets.", color = MaterialTheme.colorScheme.error)
		state.lastBuildArtifact?.let { Text("Last artifact: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
		Spacer(Modifier.height(16.dp))
	}
}

@Composable private fun CreateDeviceDialog(state: StudioState, form: CreateDeviceState, onEvent: (StudioEvent) -> Unit) {
	AlertDialog(
		onDismissRequest = { onEvent(StudioEvent.CancelCreateDevice) },
		title = { Text("Create simulator or emulator") },
		text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				TestPlatform.entries.filter(state.hostPlatform::supports).forEach { platform -> FilterChip(form.platform == platform, { onEvent(StudioEvent.ChangeCreateDevicePlatform(platform)) }, { Text(platform.label) }) }
			}
			OutlinedTextField(form.name, { onEvent(StudioEvent.ChangeCreateDeviceName(it)) }, label = { Text("Name") }, placeholder = { Text(if (form.platform == TestPlatform.Ios) "Amoo iPhone" else "Amoo Pixel") }, singleLine = true)
			OutlinedTextField(form.runtime, { onEvent(StudioEvent.ChangeCreateDeviceRuntime(it)) }, label = { Text("Runtime / system image") }, placeholder = { Text(if (form.platform == TestPlatform.Ios) "iOS 26.0" else "android-36") }, singleLine = true)
			OutlinedTextField(form.deviceType, { onEvent(StudioEvent.ChangeCreateDeviceType(it)) }, label = { Text("Device type") }, placeholder = { Text(if (form.platform == TestPlatform.Ios) "iPhone 17" else "pixel_9") }, singleLine = true)
			Text("Amoo validates installed runtimes and returns remediation when an image or device type is unavailable.", style = MaterialTheme.typography.bodySmall)
		} },
		confirmButton = { Button({ onEvent(StudioEvent.ConfirmCreateDevice) }, enabled = form.name.isNotBlank() && form.runtime.isNotBlank() && form.deviceType.isNotBlank()) { Text("Create") } },
		dismissButton = { TextButton({ onEvent(StudioEvent.CancelCreateDevice) }) { Text("Cancel") } },
	)
}

@Composable private fun DeviceCard(device: StudioDevice, selected: Boolean, canStart: Boolean, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth().clickable { onEvent(StudioEvent.SelectDevice(device.id)) }, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) { Text(device.name, style = MaterialTheme.typography.titleMedium); Text("${device.platform.label} ${device.osVersion}${if (device.physical) " · physical device" else ""}") }
			if (canStart) Button({ onEvent(StudioEvent.StartDevice(device.id)) }) { Text("Start") } else Text("Running", color = MaterialTheme.colorScheme.primary)
		}
	}
}

@Composable private fun NavigationSidebar(selected: StudioSection, onEvent: (StudioEvent) -> Unit) {
	Column(Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("Amoo Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
		Text("Mobile testing workspace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Spacer(Modifier.height(12.dp))
		StudioSection.entries.forEach { section ->
			Text(section.title, Modifier.fillMaxWidth().background(if (section == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).clickable { onEvent(StudioEvent.SelectSection(section)) }.padding(horizontal = 14.dp, vertical = 11.dp))
		}
	}
}

@Composable private fun OverviewContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
		ConnectionCard(state.connection, state.amooInstall, state.hostPlatform, onEvent)
		Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text("${state.hostPlatform.label} workspace", style = MaterialTheme.typography.titleMedium)
			Text(if (state.hostPlatform == HostPlatform.MacOS) "iOS and Android workflows are available when advertised by Amoo." else "Android workflows are available. iOS and Xcode require macOS.")
		} }
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			FeatureCard("Create a test", "Write reusable steps and expectations in a portable .amootest file.", Modifier.weight(1f)) { onEvent(StudioEvent.NewTest) }
			FeatureCard("Connect an AI", "Configure OpenAI, Claude, Ollama, or an OpenAI-compatible provider.", Modifier.weight(1f)) { onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }
			FeatureCard("Install MCP", "Copy a client configuration that launches the bundled Amoo MCP server.", Modifier.weight(1f)) { onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }
		}
	}
}

@Composable private fun TestEditor(state: StudioState, onEvent: (StudioEvent) -> Unit) {
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
			OutlinedTextField(state.test.name, { onEvent(StudioEvent.ChangeTestName(it)) }, label = { Text("Test name") }, modifier = Modifier.fillMaxWidth())
			OutlinedTextField(state.test.description, { onEvent(StudioEvent.ChangeTestDescription(it)) }, label = { Text("What does this test cover?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
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
					modifier = Modifier.fillMaxWidth(),
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
			OutlinedTextField(step.instruction, { onEvent(StudioEvent.ChangeTestStep(step.id, instruction = it)) }, label = { Text("Action or instruction") }, modifier = Modifier.fillMaxWidth())
			OutlinedTextField(step.expected, { onEvent(StudioEvent.ChangeTestStep(step.id, expected = it)) }, label = { Text("Expected result") }, modifier = Modifier.fillMaxWidth())
		}
	}
}

@Composable private fun AiTestingContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val provider = state.providers.firstOrNull { it.id == state.selectedProviderId }
	val canChat = state.connection.supports("chat.send")
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("Provider", fontWeight = FontWeight.Medium)
			state.providers.forEach { profile -> FilterChip(profile.id == state.selectedProviderId, { onEvent(StudioEvent.SelectProvider(profile.id)) }, { Text(profile.name) }) }
			Spacer(Modifier.weight(1f))
			TextButton({ onEvent(StudioEvent.ClearChat) }, enabled = state.chat.messages.isNotEmpty()) { Text("Clear") }
			TextButton({ onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }) { Text("Provider settings") }
		}
		if (!canChat) Text("The connected Amoo version does not advertise AI chat. Update Amoo to enable chat.send.", color = MaterialTheme.colorScheme.error)
		state.chat.lastError?.let { error ->
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
				Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
					TextButton({ onEvent(StudioEvent.RetryLastChat) }) { Text("Retry") }
				}
			}
		}
		LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			if (state.chat.messages.isEmpty()) item {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Text("Ask Amoo to explore, explain, or turn an idea into test steps.", style = MaterialTheme.typography.titleLarge)
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						listOf("Explore the current screen", "Suggest edge cases", "Turn this test into a robust flow").forEach { suggestion ->
							SuggestionChip(onClick = { onEvent(StudioEvent.ChangeChatInput(suggestion)) }, label = { Text(suggestion) })
						}
					}
				}
			}
			items(state.chat.messages, key = { it.id }) { message -> ChatMessageCard(message) }
			if (state.chat.operation == ChatOperation.Sending) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Amoo is thinking…") } }
		}
		FeatureCard("Active test: ${state.test.name}", "${state.test.platform.label} · ${state.test.steps.size} steps${if (state.isTestDirty) " · unsaved changes" else ""}", Modifier.fillMaxWidth()) { onEvent(StudioEvent.SelectSection(StudioSection.Tests)) }
		state.chat.proposedPlan?.let { plan -> ProposedPlanCard(plan, onEvent) }
		OutlinedTextField(
			value = state.chat.input,
			onValueChange = { onEvent(StudioEvent.ChangeChatInput(it)) },
			label = { Text("Ask Amoo") },
			placeholder = { Text("Describe what you want to test…") },
			minLines = 2,
			modifier = Modifier.fillMaxWidth(),
		)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			if (state.chat.operation == ChatOperation.Sending) OutlinedButton({ onEvent(StudioEvent.CancelChat) }) { Text("Cancel") }
			else Button({ onEvent(StudioEvent.SendChat) }, enabled = canChat && provider != null && state.chat.input.isNotBlank()) { Text("Send") }
		}
	}
}

@Composable
private fun ProposedPlanCard(plan: CompiledToolPlan, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("Review AI-proposed plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
			Text("Nothing changes until you apply this plan.", color = MaterialTheme.colorScheme.onTertiaryContainer)
			plan.toolOperations.forEachIndexed { index, operation ->
				Text("${index + 1}. ${operation.tool}${if (operation.arguments.isEmpty()) "" else " · " + operation.arguments.entries.joinToString { "${it.key}=${it.value}" }}")
			}
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				TextButton({ onEvent(StudioEvent.RejectProposedPlan) }) { Text("Discard") }
				Button({ onEvent(StudioEvent.ApplyProposedPlan) }, enabled = plan.toolOperations.isNotEmpty()) { Text("Apply to test") }
			}
		}
	}
}

@Composable private fun ChatMessageCard(message: ChatMessage) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == ChatRole.User) Arrangement.End else Arrangement.Start) {
		Card(
			modifier = Modifier.fillMaxWidth(0.82f),
			colors = CardDefaults.cardColors(containerColor = if (message.role == ChatRole.User) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
		) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Text(if (message.role == ChatRole.User) "You" else "Amoo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
			Text(message.content)
		} }
	}
}

@Composable private fun ConsoleContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
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

@Composable private fun ReportsContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val canList = state.connection.supports("reports.list")
	val selected = state.reports.firstOrNull { it.id == state.selectedReportId }
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text("Test reports", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
			Button({ onEvent(StudioEvent.RefreshReports) }, enabled = canList && !state.reportsLoading) { Text("Refresh") }
		}
		if (!canList) Text("The connected Amoo version does not advertise reports.list.", color = MaterialTheme.colorScheme.error)
		if (state.reportsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
		Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			LazyColumn(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				if (!state.reportsLoading && state.reports.isEmpty()) item { Text("No reports yet. Run a test to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
				items(state.reports, key = { it.id }) { report ->
					Card(Modifier.fillMaxWidth().clickable { onEvent(StudioEvent.SelectReport(report.id)) }, colors = CardDefaults.cardColors(containerColor = if (report.id == state.selectedReportId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
						Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
							Row(Modifier.fillMaxWidth()) { Text(report.testName, Modifier.weight(1f), fontWeight = FontWeight.Bold); ReportStatusLabel(report.status) }
							Text("${report.startedAt}${report.deviceName.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
						}
					}
				}
			}
			Card(Modifier.weight(0.58f).fillMaxHeight()) {
				if (selected == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Choose a report") }
				else Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Row(Modifier.fillMaxWidth()) { Text(selected.testName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); ReportStatusLabel(selected.status) }
					Text(selected.summary.ifBlank { "No summary was provided." })
					selected.durationMillis?.let { Text("Duration: ${it} ms") }
					Text("Session report ID: ${selected.id}", style = MaterialTheme.typography.bodySmall)
					if (selected.artifacts.isNotEmpty()) { HorizontalDivider(); Text("Artifacts", style = MaterialTheme.typography.titleMedium); selected.artifacts.forEach { path ->
						OutlinedButton({ onEvent(StudioEvent.OpenReportArtifact(path)) }) { Text("Open ${path.substringAfterLast('/')}") }
						Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					} }
				}
			}
		}
	}
}

@Composable private fun ReportStatusLabel(status: ReportStatus) {
	val color = when (status) {
		ReportStatus.Passed -> MaterialTheme.colorScheme.primary
		ReportStatus.Failed -> MaterialTheme.colorScheme.error
		ReportStatus.Cancelled, ReportStatus.Running -> MaterialTheme.colorScheme.tertiary
	}
	Text(status.name, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
}

@Composable private fun SettingsContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text("Appearance", style = MaterialTheme.typography.titleLarge)
			Text("Choose how Amoo Studio follows your desktop appearance.", color = MaterialTheme.colorScheme.onSurfaceVariant)
			SingleChoiceSegmentedButtonRow {
				ThemeMode.entries.forEachIndexed { index, mode ->
					SegmentedButton(
						selected = state.themeMode == mode,
						onClick = { onEvent(StudioEvent.ChangeThemeMode(mode)) },
						shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
					) { Text(mode.label) }
				}
			}
		} }
		Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text("Amoo MCP", style = MaterialTheme.typography.titleLarge)
			Text("Add Amoo to Claude Desktop, Claude Code, Cursor, or another MCP client. The generated configuration runs `amoo mcp serve` over stdio; Studio never proxies tool traffic.")
			when (val status = state.mcpStatus) {
				McpStatus.Unknown -> Text("MCP readiness has not been checked.", color = MaterialTheme.colorScheme.onSurfaceVariant)
				McpStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Checking MCP readiness…") }
				is McpStatus.Ready -> Text("Ready · ${status.transport} · amoo ${status.arguments.joinToString(" ")}", color = MaterialTheme.colorScheme.primary)
				is McpStatus.Unavailable -> Text(status.reason, color = MaterialTheme.colorScheme.error)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button({ onEvent(StudioEvent.CopyMcpConfiguration) }, enabled = state.mcpStatus is McpStatus.Ready) { Text("Copy MCP configuration") }
				OutlinedButton({ onEvent(StudioEvent.RefreshMcpStatus) }, enabled = state.connection.supports("mcp.status") && state.mcpStatus !is McpStatus.Checking) { Text("Check again") }
			}
		} }
		Text("AI providers", style = MaterialTheme.typography.titleLarge)
		state.providers.forEach { profile -> key(profile.id) {
			Card(Modifier.fillMaxWidth().clickable { onEvent(StudioEvent.SelectProvider(profile.id)) }, colors = CardDefaults.cardColors(containerColor = if (profile.id == state.selectedProviderId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
				Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text(profile.name, style = MaterialTheme.typography.titleMedium); Text("${profile.kind.label} · ${profile.model}")
						when (val check = state.providerChecks[profile.id]) {
							ProviderCheckState.Checking -> Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
							is ProviderCheckState.Ready -> Text(check.message, color = MaterialTheme.colorScheme.primary)
							is ProviderCheckState.Failed -> Text(check.message, color = MaterialTheme.colorScheme.error)
							null -> Unit
						}
					}
					OutlinedButton({ onEvent(StudioEvent.CheckProvider(profile.id)) }, enabled = state.connection.supports("providers.check") && state.providerChecks[profile.id] !is ProviderCheckState.Checking) { Text("Test") }
					TextButton({ onEvent(StudioEvent.RemoveProvider(profile.id)) }) { Text("Remove") }
				}
			}
		} }
		ProviderEditor(onEvent)
		Spacer(Modifier.height(16.dp))
	}
}

@Composable private fun ProviderEditor(onEvent: (StudioEvent) -> Unit) {
	var name by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }; var env by remember { mutableStateOf("OPENAI_API_KEY") }; var kind by remember { mutableStateOf(ProviderKind.OpenAI) }
	Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text("Add provider", style = MaterialTheme.typography.titleLarge)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ProviderKind.entries.forEach { item -> FilterChip(kind == item, { kind = item; when (item) { ProviderKind.OpenAI -> { baseUrl = "https://api.openai.com/v1"; env = "OPENAI_API_KEY" }; ProviderKind.Anthropic -> { baseUrl = "https://api.anthropic.com"; env = "ANTHROPIC_API_KEY" }; ProviderKind.Ollama -> { baseUrl = "http://localhost:11434"; env = "" }; ProviderKind.Custom -> Unit } }, { Text(item.label) }) } }
		OutlinedTextField(name, { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
		OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
		OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
		OutlinedTextField(env, { env = it }, label = { Text("API key environment variable") }, supportingText = { Text("Studio stores the variable name, never the secret.") }, modifier = Modifier.fillMaxWidth())
		Button({ onEvent(StudioEvent.SaveProvider(ProviderProfile("provider-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-${model.hashCode()}", name.trim(), kind, baseUrl.trim(), model.trim(), env.trim()))) }, enabled = name.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()) { Text("Save provider") }
	} }
}

@Composable private fun ConnectionCard(connection: ConnectionState, amooInstall: AmooInstallState, hostPlatform: HostPlatform, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		when (connection) {
			ConnectionState.Starting -> { CircularProgressIndicator(); Column { Text("Starting Amoo", style = MaterialTheme.typography.titleMedium); Text("Connecting to the bundled automation engine…") } }
			is ConnectionState.Ready -> Column { Text("Amoo ${connection.version} is ready", style = MaterialTheme.typography.titleMedium); Text("Protocol ${connection.protocolVersion} · ${connection.capabilities.joinToString()}") }
			is ConnectionState.Unavailable -> {
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text("Amoo is unavailable", color = MaterialTheme.colorScheme.error)
					Text(connection.reason)
					if (amooInstall is AmooInstallState.Running) {
						Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							CircularProgressIndicator(Modifier.size(16.dp))
							Text("Installing Amoo via Homebrew…")
						}
					}
					if (amooInstall is AmooInstallState.Failed) Text(amooInstall.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
					if (connection.canInstallAmoo && hostPlatform != HostPlatform.Unsupported) {
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							OutlinedButton({ onEvent(StudioEvent.RequestInstallAmoo) }, enabled = amooInstall !is AmooInstallState.Running) { Text("Install via Homebrew") }
							TextButton({ onEvent(StudioEvent.CopyInstallCommands) }) { Text("Copy install commands") }
						}
					}
				}
				Button({ onEvent(StudioEvent.RetryConnection) }) { Text("Retry") }
			}
		}
	} }
}

@Composable private fun FeatureCard(title: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) { Card(modifier.clickable(onClick = onClick)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
