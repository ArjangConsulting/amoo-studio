package dev.amoo.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.maniramezan.kmpcomponents.EmptyState
import io.github.maniramezan.kmpcomponents.ErrorState
import io.github.maniramezan.kmpcomponents.LoadingState
import io.github.maniramezan.kmpcomponents.SectionHeader
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.ZoomableImage

/** The selected device: its live mirror, or a pinned step screenshot until the user returns to live. */
@Composable
internal fun DevicePane(mirror: DeviceMirrorState, device: StudioDevice?, onEvent: (StudioEvent) -> Unit, modifier: Modifier = Modifier) {
	ThemeCard(modifier.fillMaxSize()) {
		SectionHeader(
			title = device?.name ?: "No device selected",
			subtitle = mirrorStatusLabel(mirror, device),
			modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
			titleStyle = MaterialTheme.typography.titleMedium,
			actions = {
				if (mirror.pinned != null) ThemeTextButton("Back to live", { onEvent(StudioEvent.ReturnToLiveMirror) })
				if (device?.status == DeviceStatus.Running) ThemeTextButton(if (mirror.paused) "Resume" else "Pause", { onEvent(StudioEvent.ToggleMirrorPaused) })
			},
		)
		Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
			val pinned = mirror.pinned
			when {
				pinned != null -> StepScreenshotImage(pinned)
				mirror.frame != null -> ZoomableImage(mirror.frame.image, "Live view of ${device?.name ?: "the device"}")
				mirror.status == MirrorStatus.Starting -> LoadingState("Connecting to the device…")
				mirror.status is MirrorStatus.Unavailable -> ErrorState("Live view unavailable", mirror.status.reason)
				else -> EmptyState(mirrorPlaceholder(mirror, device))
			}
		}
		mirror.pinned?.let { Text(it.title, style = MaterialTheme.typography.labelMedium, maxLines = 2) }
	}
}

private fun mirrorStatusLabel(mirror: DeviceMirrorState, device: StudioDevice?): String = when {
	mirror.pinned != null -> "Showing a step screenshot"
	device == null -> "Choose a device in Devices"
	else -> when (val status = mirror.status) {
		MirrorStatus.Off -> if (device.status == DeviceStatus.Running) "Live view off" else "Device is not running"
		MirrorStatus.Starting -> "Connecting…"
		MirrorStatus.Live -> "Live · ${"%.1f".format(mirror.framesPerSecond)} fps"
		MirrorStatus.Paused -> "Paused"
		is MirrorStatus.Reconnecting -> "Reconnecting: ${status.reason}"
		is MirrorStatus.Unavailable -> status.reason
	}
}

private fun mirrorPlaceholder(mirror: DeviceMirrorState, device: StudioDevice?): String = when {
	device == null -> "Select a running simulator, emulator, or device to see it here."
	device.status != DeviceStatus.Running -> "Start ${device.name} in Devices to see it here."
	mirror.paused -> "The live view is paused."
	else -> "Waiting for the first frame…"
}

@Composable
internal fun StepScreenshotImage(pinned: PinnedScreenshot, description: String = pinned.title) {
	val loader = LocalScreenshotLoader.current
	val preview by produceState<ScreenshotPreview>(ScreenshotPreview.Loading, loader, pinned.screenshot.path) {
		value = ScreenshotPreview.Loading
		value = loader.load(pinned.screenshot.path, PINNED_MAX_DIMENSION)?.let { ScreenshotPreview.Ready(it) } ?: ScreenshotPreview.Unavailable
	}
	when (val current = preview) {
		ScreenshotPreview.Loading -> LoadingState("Loading screenshot…")
		ScreenshotPreview.Unavailable -> ErrorState("Screenshot unavailable", "The screenshot file could not be read.")
		is ScreenshotPreview.Ready -> ZoomableImage(current.image, description, marker = pinned.gesturePoint?.let { pointInImage(it, pinned.screenshot, current.image) })
	}
}

/** Converts a gesture point (points) to a pixel position in [bitmap], which may be downscaled from the original. */
internal fun pointInImage(point: GesturePoint, screenshot: ScreenshotRef, bitmap: ImageBitmap): Offset? {
	val widthPoints = screenshot.widthPoints ?: screenshot.widthPixels?.let { pixels -> screenshot.scale?.let { pixels / it } } ?: return null
	val heightPoints = screenshot.heightPoints ?: screenshot.heightPixels?.let { pixels -> screenshot.scale?.let { pixels / it } } ?: return null
	if (widthPoints <= 0 || heightPoints <= 0) return null
	return Offset((point.x / widthPoints * bitmap.width).toFloat(), (point.y / heightPoints * bitmap.height).toFloat())
}

/** A small preview of a step screenshot; clicking pins it in the device pane. */
@Composable
internal fun ScreenshotThumbnail(screenshot: ScreenshotRef, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	val loader = LocalScreenshotLoader.current
	val image by produceState<ImageBitmap?>(null, loader, screenshot.path) {
		value = null
		value = loader.load(screenshot.path, THUMBNAIL_MAX_DIMENSION)
	}
	ThemeCard(modifier.semantics { contentDescription = description }, onClick = onClick, contentPadding = PaddingValues(0.dp)) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			val bitmap = image
			if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Fit)
			else Text("Screenshot", style = MaterialTheme.typography.labelSmall)
		}
	}
}

private sealed interface ScreenshotPreview {
	data object Loading : ScreenshotPreview
	data object Unavailable : ScreenshotPreview
	data class Ready(val image: ImageBitmap) : ScreenshotPreview
}

private const val THUMBNAIL_MAX_DIMENSION = 320
private const val PINNED_MAX_DIMENSION = 1_600
