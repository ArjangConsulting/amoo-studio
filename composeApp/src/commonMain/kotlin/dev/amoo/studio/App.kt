package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.maniramezan.kmpcomponents.ConfirmDialog
import io.github.maniramezan.kmpcomponents.KmpTheme
import io.github.maniramezan.kmpcomponents.NoticeCard
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.SidebarItem
import io.github.maniramezan.kmpcomponents.SidebarNavigation
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeDialog
import io.github.maniramezan.kmpcomponents.ThemeMode as KmpThemeMode
import io.github.maniramezan.kmpcomponents.kmpSpacing

@Composable
fun AmooStudioApp(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	KmpTheme(state.themeMode.toKmpThemeMode()) {
		Surface(Modifier.fillMaxSize()) {
			Row(Modifier.fillMaxSize()) {
				SidebarNavigation(
					modifier = Modifier.width(220.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).fillMaxHeight(),
					header = {
						Text("Amoo Studio", style = MaterialTheme.typography.titleLarge)
						Text("Mobile testing workspace", style = MaterialTheme.typography.bodySmall)
					},
				) {
					StudioSection.entries.forEach { section ->
						SidebarItem(section.title, section == state.section, { onEvent(StudioEvent.SelectSection(section)) })
					}
				}
				Column(
					Modifier.weight(1f).fillMaxHeight().padding(MaterialTheme.kmpSpacing.large),
					verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium),
				) {
					SectionHeader(state.section.title, titleStyle = MaterialTheme.typography.headlineLarge)
					state.notice?.let { notice ->
						NoticeCard(notice.message, tone = notice.tone.toStatusTone(), onDismiss = { onEvent(StudioEvent.ShowNotice(null)) })
					}
					Box(Modifier.weight(1f).fillMaxWidth()) {
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
			}
			state.pendingApproval?.let { approval ->
				ConfirmDialog(
					title = approval.title,
					message = approval.message,
					onConfirm = { onEvent(StudioEvent.ResolveApproval(true)) },
					onDismiss = { onEvent(StudioEvent.ResolveApproval(false)) },
					confirmLabel = approval.confirmLabel,
					destructive = approval.action != ApprovedAction.InstallAmoo,
				)
			}
			if (state.aboutVisible) AboutDialog(state.connection, onDismiss = { onEvent(StudioEvent.DismissAbout) })
			state.createDevice?.let { form -> CreateDeviceDialog(state, form, onEvent) }
		}
	}
}

internal fun NoticeTone.toStatusTone(): StatusTone = when (this) {
	NoticeTone.Info -> StatusTone.INFO
	NoticeTone.Success -> StatusTone.SUCCESS
	NoticeTone.Error -> StatusTone.ERROR
}

internal fun ThemeMode.toKmpThemeMode(): KmpThemeMode = when (this) {
	ThemeMode.System -> KmpThemeMode.SYSTEM
	ThemeMode.Light -> KmpThemeMode.LIGHT
	ThemeMode.Dark -> KmpThemeMode.DARK
}

internal fun KmpThemeMode.toStudioThemeMode(): ThemeMode = when (this) {
	KmpThemeMode.SYSTEM -> ThemeMode.System
	KmpThemeMode.LIGHT -> ThemeMode.Light
	KmpThemeMode.DARK -> ThemeMode.Dark
}

@Composable internal fun AboutDialog(connection: ConnectionState, onDismiss: () -> Unit) {
	val amoo = when (connection) {
		is ConnectionState.Ready -> "Amoo ${connection.version} · Studio protocol ${connection.protocolVersion}"
		ConnectionState.Starting -> "Amoo is starting"
		is ConnectionState.Unavailable -> "Amoo is not connected"
	}
	ThemeDialog(
		title = "Amoo Studio",
		text = "Version ${StudioBuildInfo.VERSION}\n$amoo",
		confirmText = "Close",
		onConfirm = onDismiss,
		onDismissRequest = onDismiss,
	)
}
