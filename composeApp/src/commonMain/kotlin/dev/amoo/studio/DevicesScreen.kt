package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.SelectableCard
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeHorizontalDivider
import io.github.maniramezan.kmpcomponents.ThemeSingleChoiceSegmentedButtonRow
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.TonalBadge
import io.github.maniramezan.kmpcomponents.focusNextOnTab
import io.github.maniramezan.kmpcomponents.kmpSpacing

@Composable internal fun DevicesContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }
	val canListDevices = state.connection.supports("devices.list")
	val canStartDevices = state.connection.supports("devices.start")
	val canBuild = state.connection.supports("apps.buildInstallRun")
	val canReinstall = state.connection.supports("apps.reinstallRun")
	val canResetData = state.connection.supports("apps.resetData")
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
		FlowLayout {
			PrimaryButton("Refresh devices", { onEvent(StudioEvent.RefreshDevices) }, enabled = canListDevices && state.deviceOperation is DeviceOperation.Idle)
			SecondaryButton("Create device", { onEvent(StudioEvent.RequestCreateDevice) }, enabled = state.connection.supports("devices.create") && state.deviceOperation is DeviceOperation.Idle)
			if (state.deviceOperation is DeviceOperation.Working) InlineProgressIndicator(state.deviceOperation.message)
		}
		if (!canListDevices) Text("Device discovery is unavailable in the connected Amoo version.", color = MaterialTheme.colorScheme.error)
		SectionHeader("Running", titleStyle = MaterialTheme.typography.titleLarge)
		val running = state.devices.filter { it.status == DeviceStatus.Running }
		if (running.isEmpty()) Text("No running simulators, emulators, or connected devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		running.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, false, onEvent) } }
		SectionHeader("Available to start", titleStyle = MaterialTheme.typography.titleLarge)
		val available = state.devices.filter { it.status == DeviceStatus.Available }
		if (available.isEmpty()) Text(if (state.hostPlatform == HostPlatform.MacOS) "No stopped simulators or emulators found. Install a runtime in Xcode or create an Android Virtual Device." else "No stopped Android emulators found. Create an Android Virtual Device and refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		available.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, canStartDevices, onEvent) } }
		ThemeHorizontalDivider()
		SectionHeader("App project", titleStyle = MaterialTheme.typography.titleLarge)
		FormTextField(state.projectPath, { onEvent(StudioEvent.ChangeProjectPath(it)) }, label = if (state.hostPlatform == HostPlatform.MacOS) "Xcode or Gradle project path" else "Gradle project path", trailingContent = { ThemeTextButton("Choose…", { onEvent(StudioEvent.ChooseProjectPath) }) }, modifier = Modifier.fillMaxWidth().focusNextOnTab(), singleLine = false)
		Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
			FormTextField(state.appId, { onEvent(StudioEvent.ChangeAppId(it)) }, label = "Bundle ID / application ID", modifier = Modifier.weight(1f).focusNextOnTab(), singleLine = false)
			FormTextField(state.schemeOrModule, { onEvent(StudioEvent.ChangeSchemeOrModule(it)) }, label = if (selected?.platform == TestPlatform.Android) "Gradle module" else "Xcode scheme", modifier = Modifier.weight(1f).focusNextOnTab(), singleLine = false)
		}
		FlowLayout {
			PrimaryButton("Build, install & run", { onEvent(StudioEvent.BuildInstallAndRun) }, enabled = canBuild && selected != null && state.hostPlatform.supports(selected.platform) && state.projectPath.isNotBlank() && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle)
			SecondaryButton("Reinstall without building", { onEvent(StudioEvent.ReinstallAndRun) }, enabled = canReinstall && selected != null && state.hostPlatform.supports(selected.platform) && state.lastBuildArtifact != null && state.deviceOperation is DeviceOperation.Idle)
			ThemeTextButton("Erase app data", { onEvent(StudioEvent.RequestResetAppData) }, enabled = canResetData && selected != null && state.hostPlatform.supports(selected.platform) && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle, destructive = true)
		}
		if (selected != null && !state.hostPlatform.supports(selected.platform)) Text("${selected.platform.label} workflows require macOS. Amoo Studio on ${state.hostPlatform.label} supports Android targets.", color = MaterialTheme.colorScheme.error)
		state.lastBuildArtifact?.let { Text("Last artifact: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
		Spacer(Modifier.height(16.dp))
	}
}

@Composable internal fun CreateDeviceDialog(state: StudioState, form: CreateDeviceState, onEvent: (StudioEvent) -> Unit) {
	AlertDialog(
		onDismissRequest = { onEvent(StudioEvent.CancelCreateDevice) },
		title = { Text("Create simulator or emulator") },
		text = { Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
			Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
				val platforms = TestPlatform.entries.filter(state.hostPlatform::supports)
				ThemeSingleChoiceSegmentedButtonRow(platforms.map { it.label }, platforms.indexOf(form.platform), { onEvent(StudioEvent.ChangeCreateDevicePlatform(platforms[it])) })
			}
			FormTextField(form.name, { onEvent(StudioEvent.ChangeCreateDeviceName(it)) }, label = "Name", placeholder = if (form.platform == TestPlatform.Ios) "Amoo iPhone" else "Amoo Pixel", singleLine = true, modifier = Modifier.focusNextOnTab())
			FormTextField(form.runtime, { onEvent(StudioEvent.ChangeCreateDeviceRuntime(it)) }, label = "Runtime / system image", placeholder = if (form.platform == TestPlatform.Ios) "iOS 26.0" else "android-36", singleLine = true, modifier = Modifier.focusNextOnTab())
			FormTextField(form.deviceType, { onEvent(StudioEvent.ChangeCreateDeviceType(it)) }, label = "Device type", placeholder = if (form.platform == TestPlatform.Ios) "iPhone 17" else "pixel_9", singleLine = true, modifier = Modifier.focusNextOnTab())
			Text("Amoo validates installed runtimes and returns remediation when an image or device type is unavailable.", style = MaterialTheme.typography.bodySmall)
		} },
		confirmButton = { PrimaryButton("Create", { onEvent(StudioEvent.ConfirmCreateDevice) }, enabled = form.name.isNotBlank() && form.runtime.isNotBlank() && form.deviceType.isNotBlank()) },
		dismissButton = { ThemeTextButton("Cancel", { onEvent(StudioEvent.CancelCreateDevice) }) },
	)
}

@Composable private fun DeviceCard(device: StudioDevice, selected: Boolean, canStart: Boolean, onEvent: (StudioEvent) -> Unit) {
	SelectableCard(selected, { onEvent(StudioEvent.SelectDevice(device.id)) }, Modifier.fillMaxWidth()) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) { Text(device.name, style = MaterialTheme.typography.titleMedium); Text("${device.platform.label} ${device.osVersion}${if (device.physical) " · physical device" else ""}") }
			if (canStart) PrimaryButton("Start", { onEvent(StudioEvent.StartDevice(device.id)) }) else TonalBadge(if (device.status == DeviceStatus.Running) "Running" else "Available", tone = if (device.status == DeviceStatus.Running) StatusTone.SUCCESS else StatusTone.INFO)
		}
	}
}
