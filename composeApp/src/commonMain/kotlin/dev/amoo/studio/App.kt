package dev.amoo.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AmooStudioApp(
	state: StudioState,
	onEvent: (StudioEvent) -> Unit,
) {
	MaterialTheme {
		Surface(modifier = Modifier.fillMaxSize()) {
			Row(modifier = Modifier.fillMaxSize()) {
				NavigationSidebar(state.section, onEvent)
				Column(
					modifier = Modifier.fillMaxSize().padding(32.dp),
					verticalArrangement = Arrangement.spacedBy(24.dp),
				) {
					Text(state.section.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
					when (state.section) {
						StudioSection.Overview -> OverviewContent(state.connection, onEvent)
						else -> ComingSoonContent(state.section)
					}
				}
			}
		}
	}
}

@Composable
private fun NavigationSidebar(
	selected: StudioSection,
	onEvent: (StudioEvent) -> Unit,
) {
	Column(
		modifier = Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text("Amoo Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
		Text("Mobile testing workspace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Spacer(Modifier.padding(top = 12.dp))
		StudioSection.entries.forEach { section ->
			val background = if (section == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
			Text(
				text = section.title,
				modifier = Modifier
					.fillMaxWidth()
					.background(background, RoundedCornerShape(10.dp))
					.clickable { onEvent(StudioEvent.SelectSection(section)) }
					.padding(horizontal = 14.dp, vertical = 11.dp),
				color = if (section == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
			)
		}
	}
}

@Composable
private fun OverviewContent(
	connection: ConnectionState,
	onEvent: (StudioEvent) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
		ConnectionCard(connection, onEvent)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			FeatureCard("Devices", "Select an iOS simulator, physical device, or Android emulator.", Modifier.weight(1f))
			FeatureCard("AI testing", "Describe a test and inspect every tool action as it runs.", Modifier.weight(1f))
			FeatureCard("Reports", "Review session artifacts, audits, screenshots, and failures.", Modifier.weight(1f))
		}
	}
}

@Composable
private fun ConnectionCard(
	connection: ConnectionState,
	onEvent: (StudioEvent) -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
	) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(24.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			when (connection) {
				ConnectionState.Starting -> {
					CircularProgressIndicator()
					Column {
						Text("Starting Amoo", style = MaterialTheme.typography.titleMedium)
						Text("Connecting to the bundled automation engine…")
					}
				}
				is ConnectionState.Ready -> Column {
					Text("Amoo ${connection.version} is ready", style = MaterialTheme.typography.titleMedium)
					Text("Protocol ${connection.protocolVersion} · ${connection.capabilities.joinToString()}")
				}
				is ConnectionState.Unavailable -> {
					Column(modifier = Modifier.weight(1f)) {
						Text("Amoo is unavailable", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
						Text(connection.reason)
						Text("Build Amoo or set AMOO_BINARY to its executable path.", style = MaterialTheme.typography.bodySmall)
					}
					Button(onClick = { onEvent(StudioEvent.RetryConnection) }) { Text("Retry") }
				}
			}
		}
	}
}

@Composable
private fun FeatureCard(title: String, description: String, modifier: Modifier = Modifier) {
	Card(modifier = modifier) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium)
			Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun ComingSoonContent(section: StudioSection) {
	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("${section.title} is next", style = MaterialTheme.typography.headlineSmall)
			Text("The interface is ready for the corresponding Amoo Studio protocol methods.")
		}
	}
}
