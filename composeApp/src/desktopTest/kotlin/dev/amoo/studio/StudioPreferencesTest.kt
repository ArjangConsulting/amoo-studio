package dev.amoo.studio

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioPreferencesTest {
	private val node = Preferences.userRoot().node("dev/amoo/studio-test/${UUID.randomUUID()}")
	private val preferences = StudioPreferences(node)

	@AfterTest
	fun tearDown() = node.removeNode()

	@Test
	fun `a fresh install has default session and no window size`() {
		assertEquals(PersistedSession(), preferences.loadSession())
		assertNull(preferences.windowSize())
		assertEquals(ThemeMode.System, preferences.themeMode())
	}

	@Test
	fun `session and window size round-trip`() {
		val session = PersistedSession("/work/app.xcodeproj", "dev.app", "App", "ollama", "sim-1", 0.4f)

		preferences.saveSession(session)
		preferences.saveWindowSize(WindowSize(1200, 800))
		preferences.saveThemeMode(ThemeMode.Dark)

		assertEquals(session, preferences.loadSession())
		assertEquals(WindowSize(1200, 800), preferences.windowSize())
		assertEquals(ThemeMode.Dark, preferences.themeMode())
	}

	@Test
	fun `a cleared device selection keeps the last device but a cleared provider is forgotten`() {
		preferences.saveSession(PersistedSession(selectedProviderId = "ollama", selectedDeviceId = "sim-1"))

		preferences.saveSession(PersistedSession(selectedProviderId = null, selectedDeviceId = null))

		assertNull(preferences.loadSession().selectedProviderId)
		assertEquals("sim-1", preferences.loadSession().selectedDeviceId)
	}

	@Test
	fun `implausibly small stored windows are ignored`() {
		preferences.saveWindowSize(WindowSize(10, 10))

		assertNull(preferences.windowSize())
	}

	@Test
	fun `the window origin round-trips and is absent until saved`() {
		assertNull(preferences.windowOrigin())

		preferences.saveWindowOrigin(WindowOrigin(-1200, 40))

		assertEquals(WindowOrigin(-1200, 40), preferences.windowOrigin())
	}
}
