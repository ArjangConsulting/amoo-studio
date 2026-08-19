package dev.amoo.studio

import androidx.compose.runtime.Immutable

@Immutable
data class StudioState(
	val connection: ConnectionState = ConnectionState.Starting,
	val section: StudioSection = StudioSection.Overview,
)

enum class StudioSection(val title: String) {
	Overview("Overview"),
	Devices("Devices"),
	Chat("AI testing"),
	Reports("Reports"),
	Settings("Settings"),
}

sealed interface StudioEvent {
	data class SelectSection(val section: StudioSection) : StudioEvent
	data object RetryConnection : StudioEvent
}

fun StudioState.reduce(event: StudioEvent): StudioState = when (event) {
	is StudioEvent.SelectSection -> copy(section = event.section)
	StudioEvent.RetryConnection -> copy(connection = ConnectionState.Starting)
}

sealed interface ConnectionState {
	data object Starting : ConnectionState
	data class Ready(
		val version: String,
		val protocolVersion: Int,
		val capabilities: List<String>,
	) : ConnectionState
	data class Unavailable(val reason: String) : ConnectionState
}
