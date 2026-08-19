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

	@Test
	fun `editing a test marks it dirty and preserves connection`() {
		val connection = ConnectionState.Ready("0.1.0", 1, emptyList())
		val state = StudioState(connection = connection)

		val updated = state.reduce(StudioEvent.ChangeTestName("Checkout flow"))

		assertEquals("Checkout flow", updated.test.name)
		assertEquals(true, updated.isTestDirty)
		assertEquals(connection, updated.connection)
	}

	@Test
	fun `adding and removing steps uses stable unique ids`() {
		val state = StudioState().reduce(StudioEvent.AddTestStep).reduce(StudioEvent.AddTestStep)

		assertEquals(listOf("step-1", "step-2", "step-3"), state.test.steps.map { it.id })
		assertEquals(listOf("step-1", "step-3"), state.reduce(StudioEvent.RemoveTestStep("step-2")).test.steps.map { it.id })
	}

	@Test
	fun `saving a provider selects it`() {
		val provider = ProviderProfile("openai", "Work OpenAI", ProviderKind.OpenAI, "https://api.openai.com/v1", "gpt-5", "OPENAI_API_KEY")

		val updated = StudioState().reduce(StudioEvent.SaveProvider(provider))

		assertEquals(provider, updated.providers.first { it.id == provider.id })
		assertEquals(provider.id, updated.selectedProviderId)
	}
}
