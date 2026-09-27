package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun AiTestingContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val provider = state.providers.firstOrNull { it.id == state.selectedProviderId }
	val canChat = state.connection.supports("chat.send")
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("Provider", fontWeight = FontWeight.Medium)
			state.providers.forEach { profile -> FilterChip(profile.id == state.selectedProviderId, { onEvent(StudioEvent.SelectProvider(profile.id)) }, { Text(profile.name) }) }
			Spacer(Modifier.weight(1f))
			TextButton({ onEvent(StudioEvent.ClearChat) }, enabled = state.chat.messages.isNotEmpty()) { Text("Clear") }
			TextButton({ onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }) { Text("Provider settings") }
		}
		if (!canChat) Text("The connected Amoo version does not advertise AI chat. Update Amoo to enable chat.send.", color = MaterialTheme.colorScheme.error)
		state.chat.lastError?.let { error ->
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
				Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
					TextButton({ onEvent(StudioEvent.RetryLastChat) }) { Text("Retry") }
				}
			}
		}
		LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			if (state.chat.messages.isEmpty()) item {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Text("Ask Amoo to explore, explain, or turn an idea into test steps.", style = MaterialTheme.typography.titleLarge)
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						listOf("Explore the current screen", "Suggest edge cases", "Turn this test into a robust flow").forEach { suggestion ->
							SuggestionChip(onClick = { onEvent(StudioEvent.ChangeChatInput(suggestion)) }, label = { Text(suggestion) })
						}
					}
				}
			}
			items(state.chat.messages, key = { it.id }) { message -> ChatMessageCard(message) }
			if (state.chat.operation == ChatOperation.Sending) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Amoo is thinking…") } }
		}
		FeatureCard("Active test: ${state.test.name}", "${state.test.platform.label} · ${state.test.steps.size} steps${if (state.isTestDirty) " · unsaved changes" else ""}", Modifier.fillMaxWidth()) { onEvent(StudioEvent.SelectSection(StudioSection.Tests)) }
		state.chat.proposedPlan?.let { plan -> ProposedPlanCard(plan, onEvent) }
		OutlinedTextField(
			value = state.chat.input,
			onValueChange = { onEvent(StudioEvent.ChangeChatInput(it)) },
			label = { Text("Ask Amoo") },
			placeholder = { Text("Describe what you want to test…") },
			minLines = 2,
			modifier = Modifier.fillMaxWidth(),
		)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			if (state.chat.operation == ChatOperation.Sending) OutlinedButton({ onEvent(StudioEvent.CancelChat) }) { Text("Cancel") }
			else Button({ onEvent(StudioEvent.SendChat) }, enabled = canChat && provider != null && state.chat.input.isNotBlank()) { Text("Send") }
		}
	}
}

@Composable
private fun ProposedPlanCard(plan: CompiledToolPlan, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("Review AI-proposed plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
			Text("Nothing changes until you apply this plan.", color = MaterialTheme.colorScheme.onTertiaryContainer)
			plan.toolOperations.forEachIndexed { index, operation ->
				Text("${index + 1}. ${operation.tool}${if (operation.arguments.isEmpty()) "" else " · " + operation.arguments.entries.joinToString { "${it.key}=${it.value}" }}")
			}
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				TextButton({ onEvent(StudioEvent.RejectProposedPlan) }) { Text("Discard") }
				Button({ onEvent(StudioEvent.ApplyProposedPlan) }, enabled = plan.toolOperations.isNotEmpty()) { Text("Apply to test") }
			}
		}
	}
}

@Composable private fun ChatMessageCard(message: ChatMessage) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == ChatRole.User) Arrangement.End else Arrangement.Start) {
		Card(
			modifier = Modifier.fillMaxWidth(0.82f),
			colors = CardDefaults.cardColors(containerColor = if (message.role == ChatRole.User) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
		) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Text(if (message.role == ChatRole.User) "You" else "Amoo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
			Text(message.content)
		} }
	}
}
