package dev.amoo.studio

import androidx.compose.runtime.Immutable

const val REPL_RECORDING_CAPABILITY = "repl.recording"

enum class ConsoleRecordingStatus { Starting, Recording, Stopping, Stopped }

/**
 * A console recording: while [status] is [ConsoleRecordingStatus.Recording], Amoo runs console commands on
 * [deviceId] in session [sessionId], so they can be generated into a test once it stops.
 */
@Immutable
data class ConsoleRecording(
	val status: ConsoleRecordingStatus,
	val device: StudioDevice,
	val appId: String,
	val sessionId: String? = null,
	val actionCount: Int = 0,
	val planPath: String? = null,
	/** Each recorded step's screenshot by plan operation id (`step-<n>`), kept apart from the console history so clearing it keeps them. */
	val stepScreenshots: Map<String, PinnedScreenshot> = emptyMap(),
	val message: String? = null,
) {
	val isActive: Boolean get() = status != ConsoleRecordingStatus.Stopped

	/** Counts a console result Amoo recorded into this session. */
	fun recording(entry: ConsoleEntry): ConsoleRecording {
		val index = entry.actionIndex ?: return this
		if (sessionId == null || entry.sessionId != sessionId) return this
		val screenshot = entry.screenshot?.let { "step-$index" to PinnedScreenshot(entry.command, it, entry.gesturePoint) }
		return copy(actionCount = maxOf(actionCount, index + 1), stepScreenshots = screenshot?.let { stepScreenshots + it } ?: stepScreenshots)
	}
}

internal fun StudioState.reduceConsoleRecording(event: StudioEvent): StudioState = when (event) {
	StudioEvent.StartConsoleRecording -> when (val readiness = sessionReadiness(REPL_RECORDING_CAPABILITY)) {
		is SessionReadiness.Ready -> if (console.recording?.isActive == true) this
			else copy(console = console.copy(recording = ConsoleRecording(ConsoleRecordingStatus.Starting, readiness.device, readiness.appId)), notice = null)
		SessionReadiness.Unsupported -> copy(notice = "The connected Amoo version cannot record console commands. Update Amoo.")
		SessionReadiness.NoDevice -> copy(notice = "Select a running device in Devices before recording")
		is SessionReadiness.NoApp -> copy(notice = "Set the app's bundle or application ID in Devices so the recording can launch it on ${readiness.device.name}")
	}
	is StudioEvent.ConsoleRecordingStarted -> console.recording?.takeIf { it.status == ConsoleRecordingStatus.Starting }?.let { recording ->
		copy(console = console.copy(recording = recording.copy(status = ConsoleRecordingStatus.Recording, sessionId = event.sessionId, actionCount = event.actionCount)))
	} ?: this
	StudioEvent.StopConsoleRecording -> console.recording?.takeIf { it.status == ConsoleRecordingStatus.Recording }?.let { recording ->
		copy(console = console.copy(recording = recording.copy(status = ConsoleRecordingStatus.Stopping)))
	} ?: this
	is StudioEvent.ConsoleRecordingStopped -> console.recording?.let { recording ->
		copy(console = console.copy(recording = recording.copy(
			status = ConsoleRecordingStatus.Stopped,
			actionCount = event.actionCount ?: recording.actionCount,
			planPath = event.planPath,
			message = event.message,
		)))
	} ?: this
	is StudioEvent.ConsoleRecordingFailed -> {
		val recording = console.recording
		val next = when (recording?.status) {
			ConsoleRecordingStatus.Stopping -> recording.copy(status = ConsoleRecordingStatus.Recording)
			ConsoleRecordingStatus.Starting, null -> null
			else -> recording
		}
		copy(console = console.copy(recording = next), notice = event.message)
	}
	StudioEvent.ConsoleRecordingInterrupted -> {
		val recording = console.recording
		when (recording?.status) {
			ConsoleRecordingStatus.Starting -> copy(console = console.copy(recording = null))
			ConsoleRecordingStatus.Recording, ConsoleRecordingStatus.Stopping -> copy(console = console.copy(recording = recording.copy(
				status = ConsoleRecordingStatus.Stopped,
				message = "Amoo stopped, which ended the recording. Steps recorded until then were saved.",
			)))
			else -> this
		}
	}
	StudioEvent.DismissConsoleRecording -> if (console.recording?.isActive == false) copy(console = console.copy(recording = null)) else this
	StudioEvent.GenerateConsoleTest -> {
		val recording = console.recording?.takeIf { it.status == ConsoleRecordingStatus.Stopped }
		val sessionId = recording?.sessionId
		if (recording == null || sessionId == null) this
		else reduce(StudioEvent.GenerateTest(GenerationSource.Session(sessionId, "Console recording on ${recording.device.name}"), recording.stepScreenshots))
	}
	else -> this
}
