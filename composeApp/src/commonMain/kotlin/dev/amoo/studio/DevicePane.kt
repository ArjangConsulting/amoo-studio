package dev.amoo.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The selected device: its live mirror, or a pinned step screenshot until the user returns to live. */
@Composable
internal fun DevicePane(mirror: DeviceMirrorState, device: StudioDevice?, onEvent: (StudioEvent) -> Unit, modifier: Modifier = Modifier) {
	Card(modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
		Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Column(Modifier.weight(1f)) {
					Text(device?.name ?: "No device selected", style = MaterialTheme.typography.titleMedium, maxLines = 1)
					Text(
						mirrorStatusLabel(mirror, device),
						style = MaterialTheme.typography.bodySmall,
						color = if (mirror.status is MirrorStatus.Unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
					)
				}
				if (mirror.pinned != null) {
					LabeledAction("Back to live", "Show the live device again") { onEvent(StudioEvent.ReturnToLiveMirror) }
				}
				if (device != null && device.status == DeviceStatus.Running) {
					LabeledAction(if (mirror.paused) "Resume" else "Pause", if (mirror.paused) "Resume the live view" else "Pause the live view to save resources") {
						onEvent(StudioEvent.ToggleMirrorPaused)
					}
				}
			}
			Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				val pinned = mirror.pinned
				when {
					pinned != null -> PinnedScreenshotView(pinned)
					mirror.frame != null -> ZoomableBitmap(mirror.frame.image, "Live view of ${device?.name ?: "the device"}")
					mirror.status == MirrorStatus.Starting -> CircularProgressIndicator()
					else -> Text(mirrorPlaceholder(mirror, device), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
			mirror.pinned?.let { Text(it.title, style = MaterialTheme.typography.labelMedium, maxLines = 2) }
		}
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
	mirror.status is MirrorStatus.Unavailable -> mirror.status.reason
	mirror.paused -> "The live view is paused."
	else -> "Waiting for the first frame…"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledAction(label: String, description: String, onClick: () -> Unit) {
	TooltipBox(
		positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
		tooltip = { PlainTooltip { Text(description) } },
		state = rememberTooltipState(),
	) {
		TextButton(onClick, Modifier.semantics { contentDescription = description }) { Text(label) }
	}
}

@Composable
private fun PinnedScreenshotView(pinned: PinnedScreenshot) {
	val loader = LocalScreenshotLoader.current
	val image by produceState<ImageBitmap?>(null, pinned.screenshot.path) { value = loader.load(pinned.screenshot.path, PINNED_MAX_DIMENSION) }
	val bitmap = image
	if (bitmap == null) {
		CircularProgressIndicator()
		return
	}
	ZoomableBitmap(bitmap, pinned.title, overlay = pinned.gesturePoint?.let { point -> pointInImage(point, pinned.screenshot, bitmap) })
}

/** Converts a gesture point (points) to a pixel position in [bitmap], which may be downscaled from the original. */
internal fun pointInImage(point: GesturePoint, screenshot: ScreenshotRef, bitmap: ImageBitmap): Offset? {
	val widthPoints = screenshot.widthPoints ?: screenshot.widthPixels?.let { pixels -> screenshot.scale?.let { pixels / it } } ?: return null
	val heightPoints = screenshot.heightPoints ?: screenshot.heightPixels?.let { pixels -> screenshot.scale?.let { pixels / it } } ?: return null
	if (widthPoints <= 0 || heightPoints <= 0) return null
	return Offset((point.x / widthPoints * bitmap.width).toFloat(), (point.y / heightPoints * bitmap.height).toFloat())
}

/**
 * Shows [image] fitted to the space, with mouse-wheel zoom, drag to pan while zoomed, and double-click
 * to reset. [overlay], in image pixels, marks where a gesture landed.
 */
@Composable
internal fun ZoomableBitmap(image: ImageBitmap, description: String, overlay: Offset? = null) {
	var zoom by remember { mutableFloatStateOf(1f) }
	var pan by remember { mutableStateOf(Offset.Zero) }
	LaunchedEffect(image.width, image.height) { if (zoom == 1f) pan = Offset.Zero }
	val ringColor = MaterialTheme.colorScheme.primary
	BoxWithConstraints(
		Modifier
			.fillMaxSize()
			.clip(RoundedCornerShape(12.dp))
			.pointerInput(Unit) {
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent()
						if (event.type == PointerEventType.Scroll) {
							val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
							zoom = (zoom * if (delta < 0) 1.1f else 1 / 1.1f).coerceIn(1f, 6f)
							if (zoom == 1f) pan = Offset.Zero
							event.changes.forEach { it.consume() }
						}
					}
				}
			}
			.pointerInput(Unit) { detectDragGestures { change, drag -> if (zoom > 1f) { change.consume(); pan += drag } } }
			.pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) },
		contentAlignment = Alignment.Center,
	) {
		val fit = minOf(constraints.maxWidth.toFloat() / image.width, constraints.maxHeight.toFloat() / image.height)
		val shownSize = with(LocalDensity.current) { DpSize((image.width * fit).toDp(), (image.height * fit).toDp()) }
		Box(Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentAlignment = Alignment.Center) {
			Image(image, description, Modifier.size(shownSize), contentScale = ContentScale.Fit)
			if (overlay != null) {
				Canvas(Modifier.matchParentSize()) {
					val center = Offset(overlay.x * fit, overlay.y * fit)
					drawCircle(ringColor, radius = 14.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
					drawCircle(ringColor.copy(alpha = 0.35f), radius = 6.dp.toPx(), center = center)
				}
			}
		}
	}
}

/** A small preview of a step screenshot; clicking pins it in the device pane. */
@Composable
internal fun ScreenshotThumbnail(screenshot: ScreenshotRef, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	val loader = LocalScreenshotLoader.current
	val image by produceState<ImageBitmap?>(null, screenshot.path) { value = loader.load(screenshot.path, THUMBNAIL_MAX_DIMENSION) }
	Box(
		modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Show in device pane", onClick = onClick).semantics { contentDescription = description },
		contentAlignment = Alignment.Center,
	) {
		val bitmap = image
		if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Fit)
		else Text("Screenshot", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

private const val THUMBNAIL_MAX_DIMENSION = 320
private const val PINNED_MAX_DIMENSION = 1_600
