package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.NoticeCard
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.kmpSpacing

@Composable internal fun OverviewContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.large)) {
		ConnectionCard(state.connection, state.amooInstall, state.hostPlatform, onEvent)
		val checklist = state.firstRunChecklist()
		if (checklist.any { !it.done }) {
			SectionCard("Get started", Modifier.fillMaxWidth(), description = "Finish these to run your first test.") {
				checklist.forEach { item -> key(item.title) { ChecklistRow(item, onEvent) } }
			}
		}
		SectionCard("${state.hostPlatform.label} workspace", Modifier.fillMaxWidth()) {
			Text(if (state.hostPlatform == HostPlatform.MacOS) "iOS and Android workflows are available when advertised by Amoo." else "Android workflows are available. iOS and Xcode require macOS.")
		}
		SectionCard("Create a test", Modifier.fillMaxWidth(), description = "Write reusable steps and expectations in a portable .amootest file.") {
			PrimaryButton("New test", { onEvent(StudioEvent.NewTest) })
		}
		SectionCard("Connect an AI", Modifier.fillMaxWidth(), description = "Configure OpenAI, Claude, Ollama, or an OpenAI-compatible provider.") {
			SecondaryButton("Provider settings", { onEvent(StudioEvent.SelectSection(StudioSection.Settings)) })
		}
		SectionCard("Install MCP", Modifier.fillMaxWidth(), description = "Copy a client configuration that launches the bundled Amoo MCP server.") {
			SecondaryButton("MCP settings", { onEvent(StudioEvent.SelectSection(StudioSection.Settings)) })
		}
	}
}

@Composable private fun ChecklistRow(item: ChecklistItem, onEvent: (StudioEvent) -> Unit) {
	Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small), verticalAlignment = Alignment.CenterVertically) {
		Text(if (item.done) "✓" else "○", Modifier.semantics { contentDescription = if (item.done) "Done" else "To do" })
		Text(item.title, Modifier.weight(1f))
		if (!item.done && item.action != null && item.actionLabel != null) SecondaryButton(item.actionLabel, { onEvent(item.action) })
	}
}

@Composable private fun ConnectionCard(connection: ConnectionState, amooInstall: AmooInstallState, hostPlatform: HostPlatform, onEvent: (StudioEvent) -> Unit) {
	when (connection) {
		ConnectionState.Starting -> InlineProgressIndicator("Starting Amoo · Connecting to the bundled automation engine…")
		is ConnectionState.Ready -> {
			var showCapabilities by remember { mutableStateOf(false) }
			StatusMessage("Protocol ${connection.protocolVersion} · ${connection.capabilities.size} capabilities", title = "Amoo ${connection.version} is ready", tone = StatusTone.SUCCESS)
			ThemeTextButton(if (showCapabilities) "Hide capabilities" else "Show capabilities", { showCapabilities = !showCapabilities })
			if (showCapabilities) Text(connection.capabilities.sorted().joinToString(", "), style = MaterialTheme.typography.bodySmall)
		}
		is ConnectionState.Unavailable -> {
			NoticeCard(connection.reason, title = "Amoo is unavailable", tone = StatusTone.ERROR, actionLabel = "Retry", onAction = { onEvent(StudioEvent.RetryConnection) })
			if (amooInstall is AmooInstallState.Running) InlineProgressIndicator("Installing Amoo via Homebrew…")
			if (amooInstall is AmooInstallState.Failed) StatusMessage(amooInstall.message, tone = StatusTone.ERROR)
			if (connection.canInstallAmoo && hostPlatform != HostPlatform.Unsupported) {
				FlowLayout {
					SecondaryButton("Install via Homebrew", { onEvent(StudioEvent.RequestInstallAmoo) }, enabled = amooInstall !is AmooInstallState.Running)
					ThemeTextButton("Copy install commands", { onEvent(StudioEvent.CopyInstallCommands) })
				}
			}
		}
	}
}
