package dev.amoo.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/** Compose Desktop text fields don't traverse focus on Tab by default; move focus explicitly. */
internal fun Modifier.focusNextOnTab(): Modifier = composed {
	val focusManager = LocalFocusManager.current
	onPreviewKeyEvent { event ->
		if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
			focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
			true
		} else false
	}
}

@Composable internal fun FeatureCard(title: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) { Card(modifier.clickable(onClick = onClick)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
