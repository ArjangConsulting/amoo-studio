package dev.amoo.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun ReportsContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
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
