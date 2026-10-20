package dev.amoo.studio

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.Serializable

/** A screenshot Amoo saved, with its geometry. Pixels divided by [scale] give points, the unit of gestures. */
@Immutable
@Serializable
data class ScreenshotRef(
	val path: String,
	val widthPixels: Int? = null,
	val heightPixels: Int? = null,
	val widthPoints: Double? = null,
	val heightPoints: Double? = null,
	val scale: Double? = null,
)

/** Where a gesture landed, in points. */
@Immutable
@Serializable
data class GesturePoint(val x: Double, val y: Double)

/** One decoded frame of the live mirror. [image] is decoded off the UI thread by the desktop stream. */
@Immutable
data class DeviceFrame(val sequence: Long, val image: ImageBitmap, val widthPixels: Int, val heightPixels: Int)

sealed interface MirrorStatus {
	/** Not wanted: no running device is selected or the device view is not on screen. */
	data object Off : MirrorStatus
	data object Starting : MirrorStatus
	data object Live : MirrorStatus
	data object Paused : MirrorStatus
	/** The connected Amoo cannot stream; retrying will not help until it is updated. */
	data class Unavailable(val reason: String) : MirrorStatus
	/** The stream failed and is being restarted. */
	data class Reconnecting(val reason: String) : MirrorStatus
}

/** A step screenshot shown in place of the live mirror until the user returns to live. */
@Immutable
data class PinnedScreenshot(val title: String, val screenshot: ScreenshotRef, val gesturePoint: GesturePoint? = null)

@Immutable
data class DeviceMirrorState(
	val status: MirrorStatus = MirrorStatus.Off,
	val deviceId: String? = null,
	val frame: DeviceFrame? = null,
	val framesPerSecond: Double = 0.0,
	val paused: Boolean = false,
	val pinned: PinnedScreenshot? = null,
)

/** Sections that show the device next to their workflow, so the mirror runs only while one is visible. */
val MIRRORED_SECTIONS = setOf(StudioSection.Console, StudioSection.Chat)

/** The device the mirror should stream right now, or null when it should be stopped. */
fun StudioState.desiredMirrorDevice(): StudioDevice? {
	if (connection !is ConnectionState.Ready || section !in MIRRORED_SECTIONS || mirror.paused) return null
	return devices.firstOrNull { it.id == selectedDeviceId && it.status == DeviceStatus.Running }
}

/** Loads saved screenshots for display. Implemented on the desktop so composables stay free of file access. */
interface ScreenshotLoader {
	/** Decodes [path], downscaled so its longest side is at most [maxDimension] pixels; null when it cannot be read. */
	suspend fun load(path: String, maxDimension: Int): ImageBitmap?

	companion object {
		val None: ScreenshotLoader = object : ScreenshotLoader {
			override suspend fun load(path: String, maxDimension: Int): ImageBitmap? = null
		}
	}
}

val LocalScreenshotLoader = staticCompositionLocalOf { ScreenshotLoader.None }
