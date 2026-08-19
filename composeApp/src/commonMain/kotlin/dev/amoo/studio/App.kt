package dev.amoo.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AmooStudioApp(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	MaterialTheme {
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
						StudioSection.Settings -> SettingsContent(state, onEvent)
						else -> ComingSoonContent(state.section)
					}
				}
			}
			state.pendingApproval?.let { approval -> AlertDialog(
				onDismissRequest = { onEvent(StudioEvent.ResolveApproval(false)) },
				title = { Text(approval.title) }, text = { Text(approval.message) },
				confirmButton = { Button({ onEvent(StudioEvent.ResolveApproval(true)) }) { Text("Erase data") } },
				dismissButton = { TextButton({ onEvent(StudioEvent.ResolveApproval(false)) }) { Text("Cancel") } },
			) }
		}
	}
}

@Composable private fun DevicesContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Button({ onEvent(StudioEvent.RefreshDevices) }, enabled = state.deviceOperation is DeviceOperation.Idle) { Text("Refresh devices") }
			if (state.deviceOperation is DeviceOperation.Working) { CircularProgressIndicator(Modifier.size(20.dp)); Text(state.deviceOperation.message) }
		}
		Text("Running", style = MaterialTheme.typography.titleLarge)
		val running = state.devices.filter { it.status == DeviceStatus.Running }
		if (running.isEmpty()) Text("No running simulators, emulators, or connected devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		running.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, false, onEvent) } }
		Text("Available to start", style = MaterialTheme.typography.titleLarge)
		val available = state.devices.filter { it.status == DeviceStatus.Available }
		if (available.isEmpty()) Text("No stopped simulators or emulators found. Install a runtime in Xcode or create an Android Virtual Device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		available.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, true, onEvent) } }
		HorizontalDivider()
		Text("App project", style = MaterialTheme.typography.titleLarge)
		OutlinedTextField(state.projectPath, { onEvent(StudioEvent.ChangeProjectPath(it)) }, label = { Text("Xcode or Gradle project path") }, trailingIcon = { TextButton({ onEvent(StudioEvent.ChooseProjectPath) }) { Text("Choose…") } }, modifier = Modifier.fillMaxWidth())
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			OutlinedTextField(state.appId, { onEvent(StudioEvent.ChangeAppId(it)) }, label = { Text("Bundle ID / application ID") }, modifier = Modifier.weight(1f))
			OutlinedTextField(state.schemeOrModule, { onEvent(StudioEvent.ChangeSchemeOrModule(it)) }, label = { Text(if (selected?.platform == TestPlatform.Android) "Gradle module" else "Xcode scheme") }, modifier = Modifier.weight(1f))
		}
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Button({ onEvent(StudioEvent.BuildInstallAndRun) }, enabled = selected != null && state.projectPath.isNotBlank() && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle) { Text("Build, install & run") }
			OutlinedButton({ onEvent(StudioEvent.ReinstallAndRun) }, enabled = selected != null && state.lastBuildArtifact != null && state.deviceOperation is DeviceOperation.Idle) { Text("Reinstall without building") }
			OutlinedButton({ onEvent(StudioEvent.RequestResetAppData) }, enabled = selected != null && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Erase app data") }
		}
		state.lastBuildArtifact?.let { Text("Last artifact: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
		Spacer(Modifier.height(16.dp))
	}
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
		ConnectionCard(state.connection, onEvent)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			FeatureCard("Create a test", "Write reusable steps and expectations in a portable .amootest file.", Modifier.weight(1f)) { onEvent(StudioEvent.NewTest) }
			FeatureCard("Connect an AI", "Configure OpenAI, Claude, Ollama, or an OpenAI-compatible provider.", Modifier.weight(1f)) { onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }
			FeatureCard("Install MCP", "Copy a client configuration that launches the bundled Amoo MCP server.", Modifier.weight(1f)) { onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }
		}
	}
}

@Composable private fun TestEditor(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Button({ onEvent(StudioEvent.NewTest) }) { Text("New") }
			OutlinedButton({ onEvent(StudioEvent.OpenTest) }) { Text("Open…") }
			Button({ onEvent(StudioEvent.SaveTest) }) { Text("Save") }
			OutlinedButton({ onEvent(StudioEvent.SaveTestAs) }) { Text("Save as…") }
			Text((state.testPath ?: "Not saved") + if (state.isTestDirty) " • Modified" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			OutlinedTextField(state.test.name, { onEvent(StudioEvent.ChangeTestName(it)) }, label = { Text("Test name") }, modifier = Modifier.fillMaxWidth())
			OutlinedTextField(state.test.description, { onEvent(StudioEvent.ChangeTestDescription(it)) }, label = { Text("What does this test cover?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				Text("Platform", fontWeight = FontWeight.Medium)
				TestPlatform.entries.forEach { platform -> FilterChip(state.test.platform == platform, { onEvent(StudioEvent.ChangeTestPlatform(platform)) }, { Text(platform.label) }) }
			}
			Text("Steps", style = MaterialTheme.typography.titleLarge)
			state.test.steps.forEachIndexed { index, step -> key(step.id) { StepCard(index, step, state.test.steps.size > 1, onEvent) } }
			OutlinedButton({ onEvent(StudioEvent.AddTestStep) }) { Text("Add step") }
			Spacer(Modifier.height(16.dp))
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
	Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(if (provider == null) "Choose an AI provider" else "${provider.name} · ${provider.model}", style = MaterialTheme.typography.titleLarge)
			Text("Amoo will execute the active test through its provider-neutral tools. Provider execution becomes available when advertised by the Studio protocol.")
			Button({ onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }) { Text(if (provider == null) "Configure provider" else "Change provider") }
		} }
		FeatureCard("Active test: ${state.test.name}", "${state.test.platform.label} · ${state.test.steps.size} steps${if (state.isTestDirty) " · unsaved changes" else ""}", Modifier.fillMaxWidth()) { onEvent(StudioEvent.SelectSection(StudioSection.Tests)) }
	}
}

@Composable private fun SettingsContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text("Amoo MCP", style = MaterialTheme.typography.titleLarge)
			Text("Add Amoo to Claude Desktop, Claude Code, Cursor, or another MCP client. The generated configuration runs `amoo mcp serve` over stdio; Studio never proxies tool traffic.")
			Button({ onEvent(StudioEvent.CopyMcpConfiguration) }, enabled = state.connection is ConnectionState.Ready) { Text("Copy MCP configuration") }
			if (state.connection !is ConnectionState.Ready) Text("Connect Amoo before copying its resolved executable configuration.", color = MaterialTheme.colorScheme.error)
		} }
		Text("AI providers", style = MaterialTheme.typography.titleLarge)
		state.providers.forEach { profile -> key(profile.id) {
			Card(Modifier.fillMaxWidth().clickable { onEvent(StudioEvent.SelectProvider(profile.id)) }, colors = CardDefaults.cardColors(containerColor = if (profile.id == state.selectedProviderId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
				Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) { Text(profile.name, style = MaterialTheme.typography.titleMedium); Text("${profile.kind.label} · ${profile.model}") }
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

@Composable private fun ConnectionCard(connection: ConnectionState, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		when (connection) {
			ConnectionState.Starting -> { CircularProgressIndicator(); Column { Text("Starting Amoo", style = MaterialTheme.typography.titleMedium); Text("Connecting to the bundled automation engine…") } }
			is ConnectionState.Ready -> Column { Text("Amoo ${connection.version} is ready", style = MaterialTheme.typography.titleMedium); Text("Protocol ${connection.protocolVersion} · ${connection.capabilities.joinToString()}") }
			is ConnectionState.Unavailable -> { Column(Modifier.weight(1f)) { Text("Amoo is unavailable", color = MaterialTheme.colorScheme.error); Text(connection.reason) }; Button({ onEvent(StudioEvent.RetryConnection) }) { Text("Retry") } }
		}
	} }
}

@Composable private fun FeatureCard(title: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) { Card(modifier.clickable(onClick = onClick)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun ComingSoonContent(section: StudioSection) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("${section.title} will be enabled by the corresponding Amoo Studio protocol capability.") } }
