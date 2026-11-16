package dev.amoo.studio

/** One step of getting started; [action] opens the place that completes it. */
data class ChecklistItem(val title: String, val done: Boolean, val actionLabel: String? = null, val action: StudioEvent? = null)

/** What a new user still has to do before Studio is useful, derived from state so it disappears by itself. */
fun StudioState.firstRunChecklist(): List<ChecklistItem> {
	val provider = providers.firstOrNull { it.id == selectedProviderId }
	return listOf(
		ChecklistItem("Amoo is connected", connection is ConnectionState.Ready),
		ChecklistItem(
			"Select a device",
			devices.any { it.id == selectedDeviceId },
			"Open Devices",
			StudioEvent.SelectSection(StudioSection.Devices),
		),
		ChecklistItem(
			"Configure an AI provider",
			provider != null && connection.providerProblem(provider) == null,
			"Provider settings",
			StudioEvent.SelectSection(StudioSection.Settings),
		),
	)
}
