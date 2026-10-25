package dev.amoo.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.maniramezan.kmpcomponents.HorizontalSplitPane
import io.github.maniramezan.kmpcomponents.SplitPaneLabels

/** Applies Studio's persisted workflow/device split limits to the shared accessible splitter. */
@Composable
internal fun Workbench(
	split: Float,
	onSplitChange: (Float) -> Unit,
	modifier: Modifier = Modifier,
	leading: @Composable () -> Unit,
	trailing: @Composable () -> Unit,
) {
	HorizontalSplitPane(
		split = split,
		onSplitChange = onSplitChange,
		modifier = modifier.fillMaxSize(),
		minSplit = MIN_WORKBENCH_SPLIT,
		maxSplit = MAX_WORKBENCH_SPLIT,
		labels = SplitPaneLabels(splitDescription = { "${(it * 100).toInt()}% workflow" }),
		leading = leading,
		trailing = trailing,
	)
}
