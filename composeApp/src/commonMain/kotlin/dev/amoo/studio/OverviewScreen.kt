package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun OverviewContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
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
