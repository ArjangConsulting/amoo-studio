package dev.amoo.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun SettingsContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
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
			ProviderCard(profile, state, onEvent)
		} }
		val editing = state.providers.firstOrNull { it.id == state.editingProviderId }
		key(editing?.id) { ProviderEditor(editing, onEvent) }
		Spacer(Modifier.height(16.dp))
	}
}

@Composable private fun ProviderCard(profile: ProviderProfile, state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth().clickable { onEvent(StudioEvent.SelectProvider(profile.id)) }, colors = CardDefaults.cardColors(containerColor = if (profile.id == state.selectedProviderId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(profile.name, style = MaterialTheme.typography.titleMedium); Text("${profile.kind.label} · ${profile.model}")
				val credential = when {
					!profile.apiKey.isBlank -> "API key ${profile.apiKey.hint} · stored in keychain"
					profile.apiKeyEnvironmentVariable.isNotBlank() -> "API key from \$${profile.apiKeyEnvironmentVariable}"
					else -> null
				}
				credential?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
				when (val check = state.providerChecks[profile.id]) {
					ProviderCheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
					is ProviderCheckState.Ready -> Text(check.message, color = MaterialTheme.colorScheme.primary)
					is ProviderCheckState.Failed -> Text(check.message, color = MaterialTheme.colorScheme.error)
					null -> Unit
				}
			}
			OutlinedButton({ onEvent(StudioEvent.CheckProvider(profile.id)) }, enabled = state.connection.supports("providers.check") && state.providerChecks[profile.id] !is ProviderCheckState.Checking) { Text("Test") }
			// Editing waits for keychain reads so a not-yet-loaded key isn't saved back as blank.
			TextButton({ onEvent(StudioEvent.EditProvider(profile.id)) }, enabled = state.providerApiKeysLoaded && state.editingProviderId != profile.id) { Text("Edit") }
			TextButton({ onEvent(StudioEvent.RemoveProvider(profile.id)) }) { Text("Remove") }
		}
	}
}

/** Adds a provider, or edits [editing] in place (keeping its id and keychain entry) when it is non-null. */
@Composable private fun ProviderEditor(editing: ProviderProfile?, onEvent: (StudioEvent) -> Unit) {
	var name by remember { mutableStateOf(editing?.name.orEmpty()) }
	var model by remember { mutableStateOf(editing?.model.orEmpty()) }
	var baseUrl by remember { mutableStateOf(editing?.baseUrl ?: ProviderKind.OpenAI.defaultBaseUrl) }
	var env by remember { mutableStateOf(editing?.apiKeyEnvironmentVariable ?: ProviderKind.OpenAI.defaultApiKeyEnvironmentVariable) }
	var kind by remember { mutableStateOf(editing?.kind ?: ProviderKind.OpenAI) }
	var apiKey by remember { mutableStateOf(editing?.apiKey?.value.orEmpty()) }
	var apiKeyVisible by remember { mutableStateOf(false) }
	fun reset() {
		name = ""; model = ""; kind = ProviderKind.OpenAI; baseUrl = kind.defaultBaseUrl; env = kind.defaultApiKeyEnvironmentVariable; apiKey = ""; apiKeyVisible = false
	}
	Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(if (editing == null) "Add provider" else "Edit ${editing.name}", style = MaterialTheme.typography.titleLarge)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			ProviderKind.entries.forEach { item ->
				FilterChip(kind == item, {
					kind = item
					if (item != ProviderKind.Custom) { baseUrl = item.defaultBaseUrl; env = item.defaultApiKeyEnvironmentVariable }
				}, { Text(item.label) })
			}
		}
		OutlinedTextField(name, { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
		OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
		OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
		OutlinedTextField(
			apiKey, { apiKey = it }, label = { Text("API key") },
			visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
			trailingIcon = { PasswordVisibilityToggle(apiKeyVisible) { apiKeyVisible = !apiKeyVisible } },
			supportingText = { Text("Stored in the OS keychain, never in plain text.") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth().focusNextOnTab(),
		)
		OutlinedTextField(env, { env = it }, label = { Text("API key environment variable (fallback)") }, supportingText = { Text("Optional. Used when no API key above is set.") }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(
				{
					val id = editing?.id ?: providerId(name, model)
					onEvent(StudioEvent.SaveProvider(ProviderProfile(id, name.trim(), kind, baseUrl.trim(), model.trim(), env.trim(), Secret(apiKey.trim()))))
					reset()
				},
				enabled = name.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank(),
			) { Text(if (editing == null) "Save provider" else "Save changes") }
			if (editing != null) TextButton({ onEvent(StudioEvent.CancelProviderEdit) }) { Text("Cancel") }
		}
	} }
}

/** A minimal eye/eye-off glyph so toggling API key visibility doesn't require a large icon-font dependency. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordVisibilityToggle(visible: Boolean, onToggle: () -> Unit) {
	val description = if (visible) "Hide API key" else "Show API key"
	val color = MaterialTheme.colorScheme.onSurfaceVariant
	TooltipBox(
		positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
		tooltip = { PlainTooltip { Text(description) } },
		state = rememberTooltipState(),
	) {
		IconButton(onToggle, Modifier.size(36.dp).semantics { contentDescription = description }) {
			Canvas(Modifier.size(20.dp)) {
				val eyeHeight = size.height * 0.6f
				val top = (size.height - eyeHeight) / 2f
				drawOval(color, topLeft = Offset(0f, top), size = Size(size.width, eyeHeight), style = Stroke(width = 1.6.dp.toPx()))
				drawCircle(color, radius = eyeHeight / 4f, center = Offset(size.width / 2f, size.height / 2f))
				if (!visible) drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 1.6.dp.toPx())
			}
		}
	}
}
