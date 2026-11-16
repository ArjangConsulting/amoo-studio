package dev.amoo.studio

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formats report times in the user's locale and time zone, falling back to the raw text for anything that is not ISO-8601. */
internal class DesktopTimestampFormatter(
	locale: Locale = Locale.getDefault(),
	zone: ZoneId = ZoneId.systemDefault(),
) : TimestampFormatter {
	private val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)

	override fun format(iso: String): String = runCatching { formatter.format(OffsetDateTime.parse(iso)) }
		.recoverCatching { formatter.format(Instant.parse(iso)) }
		.getOrDefault(iso)
}

/** Cmd on macOS and Ctrl elsewhere, the convention for application shortcuts on each. */
internal fun MenuShortcut.toKeyShortcut(macOS: Boolean) = KeyShortcut(
	key = Key(KeyEvent.getExtendedKeyCodeForChar(key.code)),
	ctrl = !macOS,
	meta = macOS,
	shift = shift,
)

internal data class WindowOrigin(val x: Int, val y: Int)

/**
 * A saved window position is only reused when enough of the title bar is on a connected screen, so
 * unplugging a monitor never leaves the window somewhere it cannot be grabbed.
 */
internal fun isReachable(origin: WindowOrigin, size: WindowSize, screens: List<Rectangle>): Boolean {
	val titleBar = Rectangle(origin.x, origin.y, size.width, MIN_VISIBLE_HEIGHT)
	return screens.any { screen ->
		val visible = screen.intersection(titleBar)
		!visible.isEmpty && visible.width >= MIN_VISIBLE_WIDTH && visible.height >= MIN_VISIBLE_HEIGHT
	}
}

private const val MIN_VISIBLE_WIDTH = 100
private const val MIN_VISIBLE_HEIGHT = 24
