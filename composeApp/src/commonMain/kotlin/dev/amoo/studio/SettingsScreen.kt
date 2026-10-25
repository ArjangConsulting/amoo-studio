package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.maniramezan.kmpcomponents.CheckStatus
import io.github.maniramezan.kmpcomponents.CheckStatusLabels
import io.github.maniramezan.kmpcomponents.CheckStatusRow
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.MenuPicker
import io.github.maniramezan.kmpcomponents.NoticeCard
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.ProviderConfigurationForm
import io.github.maniramezan.kmpcomponents.ProviderConfigurationLabels
import io.github.maniramezan.kmpcomponents.ProviderConfigurationState
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.SelectableCard
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeModeSelector
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.focusNextOnTab
import io.github.maniramezan.kmpcomponents.isValid
import io.github.maniramezan.kmpcomponents.kmpSpacing

@Composable internal fun SettingsContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.large)) {
		SectionCard("Appearance", Modifier.fillMaxWidth(), description = "Choose how Amoo Studio follows your desktop appearance.") {
			ThemeModeSelector(state.themeMode.toKmpThemeMode(), { onEvent(StudioEvent.ChangeThemeMode(it.toStudioThemeMode())) })
		}
		SectionCard("Amoo MCP", Modifier.fillMaxWidth(), description = "Add Amoo to Claude Desktop, Claude Code, Cursor, or another MCP client. The generated configuration runs `amoo mcp serve` over stdio; Studio never proxies tool traffic.") {
			CheckStatusRow(
				status = when (val status = state.mcpStatus) {
					McpStatus.Unknown -> CheckStatus.Idle
					McpStatus.Checking -> CheckStatus.Checking
					is McpStatus.Ready -> CheckStatus.Success("Ready · ${status.transport} · amoo ${status.arguments.joinToString(" ")}")
					is McpStatus.Unavailable -> CheckStatus.Failure(status.reason)
				},
				labels = CheckStatusLabels(idle = "MCP readiness has not been checked.", checking = "Checking MCP readiness…"),
			)
			FlowLayout {
				PrimaryButton("Copy MCP configuration", { onEvent(StudioEvent.CopyMcpConfiguration) }, enabled = state.mcpStatus is McpStatus.Ready)
				SecondaryButton("Check again", { onEvent(StudioEvent.RefreshMcpStatus) }, enabled = state.connection.supports("mcp.status") && state.mcpStatus !is McpStatus.Checking)
			}
		}
		SectionHeader("AI providers", titleStyle = MaterialTheme.typography.titleLarge)
		if (!state.providerApiKeysLoaded) InlineProgressIndicator("Reading stored API keys…")
		state.providerApiKeyDeletionFailures.forEach { (id, message) -> key("delete-$id") {
			NoticeCard(message, title = "Could not delete the removed provider’s API key", tone = StatusTone.ERROR, actionLabel = "Retry deleting API key", onAction = { onEvent(StudioEvent.RetryProviderApiKeyDelete(id)) })
		} }
		state.providers.forEach { profile -> key(profile.id) { ProviderCard(profile, state, onEvent) } }
		val editing = state.providers.firstOrNull { it.id == state.editingProviderId }
		key(state.editingProviderId) { ProviderEditor(state.providerDraft, editing != null, onEvent) }
	}
}

@Composable private fun ProviderCard(profile: ProviderProfile, state: StudioState, onEvent: (StudioEvent) -> Unit) {
	SelectableCard(profile.id == state.selectedProviderId, { onEvent(StudioEvent.SelectProvider(profile.id)) }, Modifier.fillMaxWidth()) {
		SectionHeader(profile.name, subtitle = "${profile.kind.label} · ${profile.model}", titleStyle = MaterialTheme.typography.titleMedium)
		val credential = when {
			!profile.apiKey.isBlank -> "API key ${profile.apiKey.hint} · " + when {
				profile.id in state.providerApiKeysSaving -> "saving to keychain"
				profile.id in state.providerApiKeySaveFailures -> "not saved to keychain"
				else -> "stored in keychain"
			}
			profile.apiKeyEnvironmentVariable.isNotBlank() -> "API key from \$${profile.apiKeyEnvironmentVariable}"
			else -> null
		}
		credential?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
		CheckStatusRow(when (val check = state.providerChecks[profile.id]) {
			ProviderCheckState.Checking -> CheckStatus.Checking
			is ProviderCheckState.Ready -> CheckStatus.Success(check.message)
			is ProviderCheckState.Failed -> CheckStatus.Failure(check.message)
			null -> CheckStatus.Idle
		})
		state.providerApiKeyReadFailures[profile.id]?.let { message ->
			NoticeCard(message, title = "Could not read the API key", tone = StatusTone.ERROR, actionLabel = if (state.providerApiKeysLoaded) "Retry keychain access" else null, onAction = if (state.providerApiKeysLoaded) ({ onEvent(StudioEvent.RetryProviderApiKeys) }) else null)
		}
		state.providerApiKeySaveFailures[profile.id]?.let { message ->
			NoticeCard(message, title = "API key has not been saved", tone = StatusTone.ERROR, actionLabel = if (profile.id !in state.providerApiKeysSaving && profile.id !in state.providerApiKeyReadFailures) "Retry saving API key" else null, onAction = if (profile.id !in state.providerApiKeysSaving && profile.id !in state.providerApiKeyReadFailures) ({ onEvent(StudioEvent.RetryProviderApiKeySave(profile.id)) }) else null)
		}
		FlowLayout {
			SecondaryButton("Test", { onEvent(StudioEvent.CheckProvider(profile.id)) }, enabled = state.connection.supports("providers.check") && state.providerChecks[profile.id] !is ProviderCheckState.Checking)
			ThemeTextButton("Edit", { onEvent(StudioEvent.EditProvider(profile.id)) }, enabled = state.providerApiKeysLoaded && profile.id !in state.providerApiKeyReadFailures && profile.id !in state.providerApiKeysSaving && state.editingProviderId != profile.id)
			ThemeTextButton("Remove", { onEvent(StudioEvent.RemoveProvider(profile.id)) }, destructive = true)
		}
	}
}

/** Renders the provider draft owned by StudioState through the shared validated form. */
@Composable private fun ProviderEditor(draft: ProviderProfile, editing: Boolean, onEvent: (StudioEvent) -> Unit) {
	val configuration = ProviderConfigurationState(draft.name, draft.baseUrl, draft.model, draft.apiKey.value)
	SectionCard(if (editing) "Edit ${draft.name}" else "Add provider", Modifier.fillMaxWidth()) {
		MenuPicker(ProviderKind.entries, draft.kind, { kind ->
			onEvent(StudioEvent.ChangeProviderDraft(if (kind == ProviderKind.Custom) draft.copy(kind = kind) else draft.copy(kind = kind, baseUrl = kind.defaultBaseUrl, apiKeyEnvironmentVariable = kind.defaultApiKeyEnvironmentVariable)))
		}, "Provider type", { it.label })
		ProviderConfigurationForm(
			state = configuration,
			onStateChange = { onEvent(StudioEvent.ChangeProviderDraft(draft.copy(name = it.providerName, baseUrl = it.baseUrl, model = it.model, apiKey = Secret(it.apiKey)))) },
			apiKeyRequired = false,
			showValidationErrors = true,
			labels = ProviderConfigurationLabels(providerName = "Display name", apiKeyOptional = "API key", showSecret = "Show API key", hideSecret = "Hide API key"),
		)
		Text("API keys are stored in the OS keychain, never in plain text.", style = MaterialTheme.typography.bodySmall)
		FormTextField(draft.apiKeyEnvironmentVariable, { onEvent(StudioEvent.ChangeProviderDraft(draft.copy(apiKeyEnvironmentVariable = it))) }, "API key environment variable (fallback)", modifier = Modifier.fillMaxWidth().focusNextOnTab(), supportingText = "Optional. Used when no API key above is set.")
		FlowLayout {
			PrimaryButton(if (editing) "Save changes" else "Save provider", {
				onEvent(StudioEvent.SaveProvider(draft.copy(id = if (editing) draft.id else providerId(draft.name, draft.model), name = draft.name.trim(), baseUrl = draft.baseUrl.trim(), model = draft.model.trim(), apiKeyEnvironmentVariable = draft.apiKeyEnvironmentVariable.trim())))
			}, enabled = configuration.isValid(apiKeyRequired = false))
			if (editing) ThemeTextButton("Cancel", { onEvent(StudioEvent.CancelProviderEdit) })
		}
	}
}
