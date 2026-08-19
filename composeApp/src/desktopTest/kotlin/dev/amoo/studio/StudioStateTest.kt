package dev.amoo.studio

import kotlin.test.Test
import kotlin.test.assertEquals

class StudioStateTest {
	@Test
	fun `selecting a section preserves connection state`() {
		val connection = ConnectionState.Ready("0.1.0", 1, listOf("health"))
		val state = StudioState(connection = connection)

		val updated = state.reduce(StudioEvent.SelectSection(StudioSection.Devices))

		assertEquals(StudioSection.Devices, updated.section)
		assertEquals(connection, updated.connection)
	}

	@Test
	fun `retry returns connection to starting`() {
		val state = StudioState(connection = ConnectionState.Unavailable("offline"))

		assertEquals(ConnectionState.Starting, state.reduce(StudioEvent.RetryConnection).connection)
	}
}
