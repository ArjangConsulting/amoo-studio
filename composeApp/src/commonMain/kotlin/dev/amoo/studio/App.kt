package dev.amoo.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
						StudioSection.GeneratedTest -> GeneratedTestContent(state, onEvent)
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
