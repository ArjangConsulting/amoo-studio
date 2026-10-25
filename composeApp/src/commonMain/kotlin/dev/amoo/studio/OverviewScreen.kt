package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable private fun ConnectionCard(connection: ConnectionState, amooInstall: AmooInstallState, hostPlatform: HostPlatform, onEvent: (StudioEvent) -> Unit) {
	when (connection) {
		ConnectionState.Starting -> InlineProgressIndicator("Starting Amoo · Connecting to the bundled automation engine…")
		is ConnectionState.Ready -> StatusMessage("Protocol ${connection.protocolVersion} · ${connection.capabilities.joinToString()}", title = "Amoo ${connection.version} is ready", tone = StatusTone.SUCCESS)
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
