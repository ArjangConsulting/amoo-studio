package dev.amoo.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arjangconsulting.codeeditor.CodeEditor
import com.arjangconsulting.codeeditor.CodeEditorLabels
import com.arjangconsulting.codeeditor.CodeEditorOptions
import com.arjangconsulting.codeeditor.CodeSnippet
import com.arjangconsulting.codeeditor.JsonRules
import com.arjangconsulting.codeeditor.TokenRules
import io.github.maniramezan.kmpcomponents.DisclosureCard
import io.github.maniramezan.kmpcomponents.EmptyState
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeSurface
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.TonalBadge
import io.github.maniramezan.kmpcomponents.kmpSpacing

@Composable internal fun ConsoleContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Workbench(
		split = state.workbenchSplit,
		onSplitChange = { onEvent(StudioEvent.ChangeWorkbenchSplit(it)) },
		leading = { ConsolePanel(state, onEvent) },
		trailing = { DevicePane(state.mirror, state.devices.firstOrNull { it.id == state.selectedDeviceId }, onEvent) },
	)
}

@Composable private fun ConsolePanel(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val rules = remember(state.toolCatalog) { amooCommandRules(state.toolCatalog) }
	val canExecute = state.connection.supports("repl.execute")
	val parse = state.consoleParse()
	val validationError = parse.diagnostics.firstOrNull { it.severity == ReplSeverity.Error }?.message
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
		SectionHeader("Amoo command console", subtitle = "Run structured Amoo operations without leaving Studio.", titleStyle = MaterialTheme.typography.titleLarge, actions = {
			ThemeTextButton("Clear history", { onEvent(StudioEvent.ClearConsole) }, enabled = state.console.entries.isNotEmpty())
		})
		RecordingBar(state, onEvent)
		if (!canExecute) StatusMessage("Update Amoo to enable command execution.", tone = StatusTone.WARNING)
		LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
			if (state.console.entries.isEmpty()) item(key = "empty") { EmptyState("No commands yet", message = "Type a command below or choose a suggestion. Destructive app operations remain in Devices so Studio can request explicit approval.") }
			items(state.console.entries, key = { it.id }) { entry -> ConsoleEntryCard(entry, rules, onEvent) }
			if (state.console.operation == ConsoleOperation.Running) item(key = "running") { InlineProgressIndicator("Running command…") }
		}
		ConsoleInput(state, canExecute && validationError == null, onEvent)
		Text(
			validationError ?: "Tab or Ctrl+Space completes · ↑/↓ history · Enter runs",
			style = MaterialTheme.typography.bodySmall,
			color = if (validationError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
		)
		FlowLayout(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
			SecondaryButton("Add to test plan", { onEvent(StudioEvent.AddTestPlanOperation(state.console.input)) }, enabled = parseToolCommand(state.console.input, state.toolCatalog) != null && state.console.operation == ConsoleOperation.Idle)
			if (state.console.operation == ConsoleOperation.Running) SecondaryButton("Cancel", { onEvent(StudioEvent.CancelConsoleCommand) })
			else PrimaryButton("Run", { onEvent(StudioEvent.ExecuteConsoleCommand) }, enabled = canExecute && validationError == null && state.console.input.isNotBlank())
		}
	}
}

/** Record, the live recording with Stop, or the finished recording with Generate test. */
@Composable private fun RecordingBar(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val recording = state.console.recording
	if (recording == null) {
		val readiness = state.sessionReadiness(REPL_RECORDING_CAPABILITY)
		if (readiness == SessionReadiness.Unsupported) return
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
			Text(
				when (readiness) {
					is SessionReadiness.Ready -> "Record to turn the commands you run on ${readiness.device.name} into a test. Recording relaunches ${readiness.appId}."
					SessionReadiness.NoDevice -> "Select a running device in Devices to record commands into a test."
					is SessionReadiness.NoApp -> "Set the app's bundle or application ID in Devices to record commands on ${readiness.device.name}."
					SessionReadiness.Unsupported -> ""
				},
				Modifier.weight(1f),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			SecondaryButton("Record",
				{ onEvent(StudioEvent.StartConsoleRecording) },
				enabled = readiness is SessionReadiness.Ready && state.console.operation == ConsoleOperation.Idle,
			)
		}
		return
	}
	ThemeCard {
		if (recording.status == ConsoleRecordingStatus.Starting || recording.status == ConsoleRecordingStatus.Stopping) InlineProgressIndicator(recording.headline)
		else Text(recording.headline, style = MaterialTheme.typography.titleMedium)
		recording.detail(state.selectedDeviceId)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
		FlowLayout {
			when (recording.status) {
				ConsoleRecordingStatus.Recording -> PrimaryButton("Stop recording", { onEvent(StudioEvent.StopConsoleRecording) }, enabled = state.console.operation == ConsoleOperation.Idle)
				ConsoleRecordingStatus.Stopped -> {
					ThemeTextButton("Dismiss", { onEvent(StudioEvent.DismissConsoleRecording) })
					PrimaryButton("Generate test", { onEvent(StudioEvent.GenerateConsoleTest) }, enabled = recording.sessionId != null && recording.actionCount > 0 && state.connection.supports(TESTS_GENERATE_CAPABILITY))
				}
				ConsoleRecordingStatus.Starting, ConsoleRecordingStatus.Stopping -> Unit
			}
		}
	}
}

private val ConsoleRecording.headline: String
	get() = when (status) {
		ConsoleRecordingStatus.Starting -> "Starting a recording on ${device.name}…"
		ConsoleRecordingStatus.Recording -> "Recording on ${device.name} · ${stepCount(actionCount)}"
		ConsoleRecordingStatus.Stopping -> "Ending the recording…"
		ConsoleRecordingStatus.Stopped -> "Recorded ${stepCount(actionCount)} on ${device.name}"
	}

private fun ConsoleRecording.detail(selectedDeviceId: String?): String? = when {
	status == ConsoleRecordingStatus.Starting -> "Amoo launches $appId fresh, and may first boot the device or build its companion."
	status == ConsoleRecordingStatus.Recording && selectedDeviceId != device.id -> "Commands only record on ${device.name}. Select it again to keep recording."
	status == ConsoleRecordingStatus.Recording -> "Tool commands become test steps; built-in commands are not recorded."
	status == ConsoleRecordingStatus.Stopped && actionCount == 0 -> message ?: "No commands were recorded, so there is nothing to generate."
	status == ConsoleRecordingStatus.Stopped -> message
	else -> null
}

private fun stepCount(count: Int) = if (count == 1) "1 step" else "$count steps"

@Composable private fun ConsoleEntryCard(entry: ConsoleEntry, rules: TokenRules, onEvent: (StudioEvent) -> Unit) {
	var showDetails by remember(entry.id) { mutableStateOf(false) }
	val details = remember(entry.structuredContent) { entry.structuredContent?.let(::prettyJson) }
	val summary: @Composable RowScope.() -> Unit = {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.extraSmall)) {
			CodeSnippet(entry.command, language = rules, maxLines = 1, overflow = TextOverflow.Ellipsis)
			entry.actionIndex?.let { TonalBadge("Recorded as step ${it + 1}") }
			if (entry.failed) StatusMessage(entry.output, tone = StatusTone.ERROR) else Text(entry.output)
			if (details != null) Text(if (showDetails) "Hide details" else "Show details", style = MaterialTheme.typography.labelLarge)
		}
		entry.screenshot?.let { screenshot ->
			ScreenshotThumbnail(screenshot, "Screen after ${entry.command}", { onEvent(StudioEvent.PinScreenshot(PinnedScreenshot(entry.command, screenshot, entry.gesturePoint))) }, Modifier.size(width = 72.dp, height = 132.dp))
		}
	}
	if (details != null) {
		DisclosureCard(showDetails, { showDetails = it }, summary = summary, detail = { CodeSnippet(details, language = JsonRules, softWrap = true) })
	} else ThemeCard { Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small), content = summary) }
}

/**
 * The console line: a single-line code editor with the console language's highlighting, completion
 * (including elements on the device's screen when Amoo supports it), and inline diagnostics.
 */
@Composable private fun ConsoleInput(state: StudioState, canRun: Boolean, onEvent: (StudioEvent) -> Unit) {
	val assist = LocalReplAssist.current
	val current by rememberUpdatedState(state)
	val service = remember(assist) { ConsoleLanguageService({ current.toolCatalog }, { current.devices }, assist) }
	val rules = remember(state.toolCatalog) { amooCommandRules(state.toolCatalog) }
	val running = state.console.operation == ConsoleOperation.Running
	ThemeSurface(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.medium), contentPadding = PaddingValues(0.dp)) {
		CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
			CodeEditor(
				value = state.console.input,
				onValueChange = { onEvent(StudioEvent.ChangeConsoleInput(it)) },
				modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
				language = rules,
				languageService = service,
				readOnly = running,
				singleLine = true,
				onSubmit = { if (canRun && it.isNotBlank() && !running) onEvent(StudioEvent.ExecuteConsoleCommand) },
				onHistory = { onEvent(StudioEvent.NavigateConsoleHistory(it)) },
				labels = CodeEditorLabels(editorDescription = "Amoo command"),
				options = CodeEditorOptions(highlightCurrentLine = false),
			)
		}
	}
}
