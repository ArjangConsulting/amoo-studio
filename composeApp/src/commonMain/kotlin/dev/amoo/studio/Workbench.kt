package dev.amoo.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Two panes side by side: a workflow (console or agent feed) and the device. [split] is the leading
 * pane's share of the width; the divider drags, and with focus it moves by 5% per arrow key.
 */
@Composable
internal fun Workbench(
	split: Float,
	onSplitChange: (Float) -> Unit,
	modifier: Modifier = Modifier,
	leading: @Composable () -> Unit,
	trailing: @Composable () -> Unit,
) {
	val currentSplit by rememberUpdatedState(split)
	BoxWithConstraints(modifier.fillMaxSize()) {
		val totalWidth = maxWidth
		val totalPx = with(LocalDensity.current) { totalWidth.toPx() }.coerceAtLeast(1f)
		val dragState = rememberDraggableState { delta -> onSplitChange(currentSplit + delta / totalPx) }
		Row(Modifier.fillMaxSize()) {
			Box(Modifier.width(totalWidth * split).fillMaxHeight()) { leading() }
			Box(
				Modifier
					.width(12.dp)
					.fillMaxHeight()
					.pointerHoverIcon(PointerIcon.Hand)
					.draggable(dragState, Orientation.Horizontal)
					.focusable()
					.onKeyEvent { event ->
						if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
						when (event.key) {
							Key.DirectionLeft -> { onSplitChange(currentSplit - 0.05f); true }
							Key.DirectionRight -> { onSplitChange(currentSplit + 0.05f); true }
							else -> false
						}
					}
					.semantics {
						contentDescription = "Resize panes"
						stateDescription = "${(split * 100).toInt()}% workflow"
					},
				contentAlignment = Alignment.Center,
			) {
				Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(MaterialTheme.colorScheme.outlineVariant))
			}
			Box(Modifier.weight(1f).fillMaxHeight()) { trailing() }
		}
	}
}
