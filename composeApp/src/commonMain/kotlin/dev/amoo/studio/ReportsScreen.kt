package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.maniramezan.kmpcomponents.EmptyState
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.KeyValueRow
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.SelectableCard
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeHorizontalDivider
import io.github.maniramezan.kmpcomponents.TonalBadge
import io.github.maniramezan.kmpcomponents.kmpSpacing

@Composable internal fun ReportsContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val canList = state.connection.supports("reports.list")
	val selected = state.reports.firstOrNull { it.id == state.selectedReportId }
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
		SectionHeader("Test reports", titleStyle = MaterialTheme.typography.titleLarge, actions = {
			SecondaryButton("Refresh", { onEvent(StudioEvent.RefreshReports) }, enabled = canList && !state.reportsLoading)
		})
		if (!canList) StatusMessage("The connected Amoo version does not advertise reports.list.", tone = StatusTone.WARNING)
		if (state.reportsLoading) InlineProgressIndicator("Loading reports…")
		Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
			LazyColumn(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
				if (!state.reportsLoading && state.reports.isEmpty()) item(key = "empty") { EmptyState("No reports yet", message = "Run a test to create one.") }
				items(state.reports, key = { it.id }) { report ->
					SelectableCard(report.id == state.selectedReportId, { onEvent(StudioEvent.SelectReport(report.id)) }, Modifier.fillMaxWidth()) {
						Text(report.testName, style = MaterialTheme.typography.titleMedium)
						ReportStatusLabel(report.status)
						Text("${report.startedAt}${report.deviceName.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
					}
				}
			}
			if (selected == null) EmptyState("Choose a report", Modifier.weight(0.58f))
			else SectionCard(selected.testName, Modifier.weight(0.58f).fillMaxHeight()) {
				Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
					ReportStatusLabel(selected.status)
					Text(selected.summary.ifBlank { "No summary was provided." })
					selected.durationMillis?.let { KeyValueRow("Duration", "$it ms") }
					KeyValueRow("Session report ID", selected.id)
					if (selected.artifacts.isNotEmpty()) {
						ThemeHorizontalDivider()
						SectionHeader("Artifacts")
						selected.artifacts.forEach { path ->
							SecondaryButton("Open ${path.substringAfterLast('/')}", { onEvent(StudioEvent.OpenReportArtifact(path)) })
							Text(path, style = MaterialTheme.typography.bodySmall)
						}
					}
				}
			}
		}
	}
}

@Composable private fun ReportStatusLabel(status: ReportStatus) {
	TonalBadge(status.name, tone = when (status) {
		ReportStatus.Passed -> StatusTone.SUCCESS
		ReportStatus.Failed -> StatusTone.ERROR
		ReportStatus.Cancelled -> StatusTone.WARNING
		ReportStatus.Running -> StatusTone.INFO
	})
}
