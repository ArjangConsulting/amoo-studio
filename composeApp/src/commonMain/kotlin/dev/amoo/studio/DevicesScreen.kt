package dev.amoo.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

@Composable internal fun DevicesContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }
	val canListDevices = state.connection.supports("devices.list")
	val canStartDevices = state.connection.supports("devices.start")
	val canBuild = state.connection.supports("apps.buildInstallRun")
	val canReinstall = state.connection.supports("apps.reinstallRun")
	val canResetData = state.connection.supports("apps.resetData")
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Button({ onEvent(StudioEvent.RefreshDevices) }, enabled = canListDevices && state.deviceOperation is DeviceOperation.Idle) { Text("Refresh devices") }
			OutlinedButton({ onEvent(StudioEvent.RequestCreateDevice) }, enabled = state.connection.supports("devices.create") && state.deviceOperation is DeviceOperation.Idle) { Text("Create device") }
			if (state.deviceOperation is DeviceOperation.Working) { CircularProgressIndicator(Modifier.size(20.dp)); Text(state.deviceOperation.message) }
		}
		if (!canListDevices) Text("Device discovery is unavailable in the connected Amoo version.", color = MaterialTheme.colorScheme.error)
		Text("Running", style = MaterialTheme.typography.titleLarge)
		val running = state.devices.filter { it.status == DeviceStatus.Running }
		if (running.isEmpty()) Text("No running simulators, emulators, or connected devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		running.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, false, onEvent) } }
		Text("Available to start", style = MaterialTheme.typography.titleLarge)
		val available = state.devices.filter { it.status == DeviceStatus.Available }
		if (available.isEmpty()) Text(if (state.hostPlatform == HostPlatform.MacOS) "No stopped simulators or emulators found. Install a runtime in Xcode or create an Android Virtual Device." else "No stopped Android emulators found. Create an Android Virtual Device and refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
		available.forEach { device -> key(device.id) { DeviceCard(device, device.id == state.selectedDeviceId, canStartDevices, onEvent) } }
		HorizontalDivider()
		Text("App project", style = MaterialTheme.typography.titleLarge)
		OutlinedTextField(state.projectPath, { onEvent(StudioEvent.ChangeProjectPath(it)) }, label = { Text(if (state.hostPlatform == HostPlatform.MacOS) "Xcode or Gradle project path" else "Gradle project path") }, trailingIcon = { TextButton({ onEvent(StudioEvent.ChooseProjectPath) }) { Text("Choose…") } }, modifier = Modifier.fillMaxWidth().focusNextOnTab())
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			OutlinedTextField(state.appId, { onEvent(StudioEvent.ChangeAppId(it)) }, label = { Text("Bundle ID / application ID") }, modifier = Modifier.weight(1f).focusNextOnTab())
			OutlinedTextField(state.schemeOrModule, { onEvent(StudioEvent.ChangeSchemeOrModule(it)) }, label = { Text(if (selected?.platform == TestPlatform.Android) "Gradle module" else "Xcode scheme") }, modifier = Modifier.weight(1f).focusNextOnTab())
		}
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Button({ onEvent(StudioEvent.BuildInstallAndRun) }, enabled = canBuild && selected != null && state.hostPlatform.supports(selected.platform) && state.projectPath.isNotBlank() && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle) { Text("Build, install & run") }
			OutlinedButton({ onEvent(StudioEvent.ReinstallAndRun) }, enabled = canReinstall && selected != null && state.hostPlatform.supports(selected.platform) && state.lastBuildArtifact != null && state.deviceOperation is DeviceOperation.Idle) { Text("Reinstall without building") }
			OutlinedButton({ onEvent(StudioEvent.RequestResetAppData) }, enabled = canResetData && selected != null && state.hostPlatform.supports(selected.platform) && state.appId.isNotBlank() && state.deviceOperation is DeviceOperation.Idle, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Erase app data") }
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
		text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				TestPlatform.entries.filter(state.hostPlatform::supports).forEach { platform -> FilterChip(form.platform == platform, { onEvent(StudioEvent.ChangeCreateDevicePlatform(platform)) }, { Text(platform.label) }) }
			}
			OutlinedTextField(form.name, { onEvent(StudioEvent.ChangeCreateDeviceName(it)) }, label = { Text("Name") }, placeholder = { Text(if (form.platform == TestPlatform.Ios) "Amoo iPhone" else "Amoo Pixel") }, singleLine = true, modifier = Modifier.focusNextOnTab())
			OutlinedTextField(form.runtime, { onEvent(StudioEvent.ChangeCreateDeviceRuntime(it)) }, label = { Text("Runtime / system image") }, placeholder = { Text(if (form.platform == TestPlatform.Ios) "iOS 26.0" else "android-36") }, singleLine = true, modifier = Modifier.focusNextOnTab())
			OutlinedTextField(form.deviceType, { onEvent(StudioEvent.ChangeCreateDeviceType(it)) }, label = { Text("Device type") }, placeholder = { Text(if (form.platform == TestPlatform.Ios) "iPhone 17" else "pixel_9") }, singleLine = true, modifier = Modifier.focusNextOnTab())
			Text("Amoo validates installed runtimes and returns remediation when an image or device type is unavailable.", style = MaterialTheme.typography.bodySmall)
		} },
		confirmButton = { Button({ onEvent(StudioEvent.ConfirmCreateDevice) }, enabled = form.name.isNotBlank() && form.runtime.isNotBlank() && form.deviceType.isNotBlank()) { Text("Create") } },
		dismissButton = { TextButton({ onEvent(StudioEvent.CancelCreateDevice) }) { Text("Cancel") } },
	)
}

@Composable private fun DeviceCard(device: StudioDevice, selected: Boolean, canStart: Boolean, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth().clickable { onEvent(StudioEvent.SelectDevice(device.id)) }, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) { Text(device.name, style = MaterialTheme.typography.titleMedium); Text("${device.platform.label} ${device.osVersion}${if (device.physical) " · physical device" else ""}") }
			if (canStart) Button({ onEvent(StudioEvent.StartDevice(device.id)) }) { Text("Start") } else Text("Running", color = MaterialTheme.colorScheme.primary)
		}
	}
}
