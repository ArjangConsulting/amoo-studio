package dev.amoo.studio

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import java.awt.Rectangle
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPresentationTest {
	@Test
	fun `report times follow the locale and zone and fall back to the raw text`() {
		val formatter = DesktopTimestampFormatter(Locale.US, ZoneId.of("UTC"))

		assertEquals("Nov 1, 2026, 10:30 AM", formatter.format("2026-11-01T10:30:00Z").replace(' ', ' '))
		assertEquals("Nov 1, 2026, 2:30 AM", DesktopTimestampFormatter(Locale.US, ZoneId.of("America/Los_Angeles")).format("2026-11-01T10:30:00Z").replace(' ', ' '))
		assertEquals("yesterday", formatter.format("yesterday"))
	}

	@Test
	fun `shortcuts use Command on macOS and Control elsewhere`() {
		val save = MenuShortcut('S', shift = true)

		assertEquals(KeyShortcut(Key.S, meta = true, shift = true), save.toKeyShortcut(macOS = true))
		assertEquals(KeyShortcut(Key.S, ctrl = true, shift = true), save.toKeyShortcut(macOS = false))
		assertEquals(KeyShortcut(Key.One, meta = true), MenuShortcut('1').toKeyShortcut(macOS = true))
	}

	@Test
	fun `a saved window position must leave the title bar reachable`() {
		val screens = listOf(Rectangle(0, 0, 1920, 1080))
		val size = WindowSize(960, 680)

		assertTrue(isReachable(WindowOrigin(100, 100), size, screens))
		assertTrue(isReachable(WindowOrigin(1800, 100), size, screens))
		assertFalse(isReachable(WindowOrigin(3000, 100), size, screens))
		assertFalse(isReachable(WindowOrigin(100, 2000), size, screens))
		assertFalse(isReachable(WindowOrigin(100, 100), size, emptyList()))
	}

	@Test
	fun `durations read as the two most significant units`() {
		assertEquals("0 ms", humanDuration(-5))
		assertEquals("850 ms", humanDuration(850))
		assertEquals("12.4 s", humanDuration(12_450))
		assertEquals("3 min 05 s", humanDuration(185_000))
		assertEquals("1 h 02 min", humanDuration(3_725_000))
	}

	@Test
	fun `the menu bar offers file, view, and help commands that emit existing events`() {
		val menus = studioMenus()

		assertEquals(listOf("File", "View", "Help"), menus.map { it.title })
		assertEquals(listOf(StudioEvent.NewTest, StudioEvent.OpenTest, StudioEvent.SaveTest, StudioEvent.SaveTestAs), menus[0].commands.map { it.event })
		assertEquals(StudioSection.entries.map { StudioEvent.SelectSection(it) }, menus[1].commands.map { it.event })
		assertEquals(listOf(StudioEvent.ShowAbout, StudioEvent.RevealLogs), menus[2].commands.map { it.event })
		val shortcuts = menus.flatMap { it.commands }.mapNotNull { it.shortcut }
		assertEquals(shortcuts.size, shortcuts.toSet().size, "shortcuts must be unique")
	}
}
