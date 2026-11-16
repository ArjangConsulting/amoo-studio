package dev.amoo.studio

import androidx.compose.runtime.compositionLocalOf

/** Renders a report's ISO-8601 start time for the user's locale and time zone; implemented on the desktop. */
fun interface TimestampFormatter {
	/** Returns [iso] formatted for display, or [iso] itself when it cannot be parsed. */
	fun format(iso: String): String

	companion object {
		val Raw = TimestampFormatter { it }
	}
}

val LocalTimestampFormatter = compositionLocalOf { TimestampFormatter.Raw }

/** "850 ms", "12.4 s", "3 min 05 s" or "1 h 02 min": the two most significant units, for people rather than logs. */
fun humanDuration(millis: Long): String {
	val value = millis.coerceAtLeast(0)
	return when {
		value < 1_000 -> "$value ms"
		value < 60_000 -> "${value / 100 / 10.0} s"
		value < 3_600_000 -> "${value / 60_000} min ${(value / 1_000 % 60).toString().padStart(2, '0')} s"
		else -> "${value / 3_600_000} h ${(value / 60_000 % 60).toString().padStart(2, '0')} min"
	}
}
