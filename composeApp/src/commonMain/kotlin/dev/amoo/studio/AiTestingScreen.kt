package dev.amoo.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maniramezan.kmpcomponents.codeeditor.CodeSnippet
import io.github.maniramezan.kmpcomponents.codeeditor.tokenRules

/** Highlights `name=value` arguments in the agent timeline. */
private val AmooArgumentRules = tokenRules("amoo-arguments") {
	attributeAssignments()
	string("\"")
	string("'")
	numbers()
	constants("true", "false")
}

@Composable internal fun AiTestingContent(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	Workbench(
		split = state.workbenchSplit,
		onSplitChange = { onEvent(StudioEvent.ChangeWorkbenchSplit(it)) },
		leading = { AgentPanel(state, onEvent) },
		trailing = { DevicePane(state.mirror, state.devices.firstOrNull { it.id == state.selectedDeviceId }, onEvent) },
	)
}

@Composable private fun AgentPanel(state: StudioState, onEvent: (StudioEvent) -> Unit) {
	val provider = state.providers.firstOrNull { it.id == state.selectedProviderId }
	val readiness = state.agentReadiness()
	val run = state.agentRun
	val canChat = state.connection.supports("chat.send")
	val listState = rememberLazyListState()
	val timeline = remember(run?.events) { run?.timeline().orEmpty() }
	LaunchedEffect(state.chat.messages.size, timeline.size, run?.streamingText?.length) {
		val last = listState.layoutInfo.totalItemsCount - 1
		if (last >= 0) listState.animateScrollToItem(last)
	}
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("Provider", fontWeight = FontWeight.Medium)
			state.providers.forEach { profile -> FilterChip(profile.id == state.selectedProviderId, { onEvent(StudioEvent.SelectProvider(profile.id)) }, { Text(profile.name) }) }
			Spacer(Modifier.weight(1f))
			TextButton({ onEvent(StudioEvent.ClearChat) }, enabled = state.chat.messages.isNotEmpty() && !state.agentRunActive) { Text("Clear") }
			TextButton({ onEvent(StudioEvent.SelectSection(StudioSection.Settings)) }) { Text("Provider settings") }
		}
		ReadinessBanner(readiness, canChat, onEvent)
		state.chat.lastError?.let { error ->
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
				Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
					TextButton({ onEvent(StudioEvent.RetryLastChat) }) { Text("Retry") }
				}
			}
		}
		LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
			if (state.chat.messages.isEmpty() && run == null) item(key = "empty") { EmptyAgentPrompt(readiness, onEvent) }
			items(state.chat.messages, key = { it.id }) { message -> ChatMessageCard(message) }
			if (run != null) {
				item(key = "run-${run.runId}") { AgentRunHeader(run, onEvent) }
				items(timeline, key = { it.key }) { item -> AgentTimelineCard(item, run, state.pendingApproval, onEvent) }
				if (run.streamingText.isNotEmpty()) item(key = "streaming") { StreamingBubble(run.streamingText) }
			}
			if (state.chat.operation == ChatOperation.Sending) item(key = "thinking") {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Amoo is thinking…") }
			}
		}
		FeatureCard("Active test: ${state.test.name}", "${state.test.platform.label} · ${state.test.steps.size} steps${if (state.isTestDirty) " · unsaved changes" else ""}", Modifier.fillMaxWidth()) { onEvent(StudioEvent.SelectSection(StudioSection.Tests)) }
		state.chat.proposedPlan?.let { plan -> ProposedPlanCard(plan, onEvent) }
		Composer(state, provider != null, readiness, canChat, onEvent)
	}
}

@Composable private fun ReadinessBanner(readiness: AgentReadiness, canChat: Boolean, onEvent: (StudioEvent) -> Unit) {
	val (message, action) = when (readiness) {
		is AgentReadiness.Ready -> return
		AgentReadiness.Unsupported -> if (canChat) return else "The connected Amoo version supports neither agent runs nor chat. Update Amoo." to null
		AgentReadiness.NoDevice -> "Select a running device in Devices to let the agent drive it. Until then, messages go to chat." to "Open Devices"
		is AgentReadiness.NoApp -> "Set the app's bundle or application ID in Devices so the agent can launch it on ${readiness.device.name}." to "Open Devices"
	}
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
		Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
			action?.let { TextButton({ onEvent(StudioEvent.SelectSection(StudioSection.Devices)) }) { Text(it) } }
		}
	}
}

@Composable private fun EmptyAgentPrompt(readiness: AgentReadiness, onEvent: (StudioEvent) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(
			if (readiness is AgentReadiness.Ready) "Tell the agent what to test. It drives ${readiness.device.name}, records a session, and can turn it into a native test."
			else "Ask Amoo to explore, explain, or turn an idea into test steps.",
			style = MaterialTheme.typography.titleMedium,
		)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			listOf("Sign in with the demo account and verify the home screen", "Explore the current screen", "Suggest edge cases").forEach { suggestion ->
				SuggestionChip(onClick = { onEvent(StudioEvent.ChangeChatInput(suggestion)) }, label = { Text(suggestion) })
			}
		}
	}
}

@Composable private fun Composer(state: StudioState, hasProvider: Boolean, readiness: AgentReadiness, canChat: Boolean, onEvent: (StudioEvent) -> Unit) {
	val active = state.agentRunActive
	OutlinedTextField(
		value = state.chat.input,
		onValueChange = { onEvent(StudioEvent.ChangeChatInput(it)) },
		label = { Text(if (active) "Guide the agent" else "Ask Amoo") },
		placeholder = { Text(if (active) "Add guidance; the agent reads it before its next step" else "Describe what you want to test…") },
		minLines = 2,
		modifier = Modifier.fillMaxWidth(),
	)
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
		val run = state.agentRun
		if (run != null && !active) TextButton({ onEvent(StudioEvent.DismissAgentRun) }) { Text("Dismiss run") }
		when {
			active -> {
				OutlinedButton({ onEvent(StudioEvent.CancelAgentRun) }) { Text("Stop") }
				Button({ onEvent(StudioEvent.SteerAgentRun) }, enabled = state.chat.input.isNotBlank()) { Text("Send guidance") }
			}
			state.chat.operation == ChatOperation.Sending -> OutlinedButton({ onEvent(StudioEvent.CancelChat) }) { Text("Cancel") }
			readiness is AgentReadiness.Ready -> Button({ onEvent(StudioEvent.SendChat) }, enabled = hasProvider && state.chat.input.isNotBlank()) { Text("Start agent run") }
			else -> Button({ onEvent(StudioEvent.SendChat) }, enabled = canChat && hasProvider && state.chat.input.isNotBlank() && readiness !is AgentReadiness.NoApp) { Text("Send") }
		}
	}
}

@Composable private fun AgentRunHeader(run: AgentRunState, onEvent: (StudioEvent) -> Unit) {
	val status = when (run.status) {
		AgentRunStatus.Running -> "Agent running"
		AgentRunStatus.AwaitingApproval -> "Waiting for your approval"
		AgentRunStatus.Completed -> "Agent finished"
		AgentRunStatus.Failed -> "Agent run failed"
		AgentRunStatus.Cancelled -> "Agent run stopped"
	}
	Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		if (!run.status.isTerminal) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
		Text(status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
		Spacer(Modifier.weight(1f))
		if (run.inputTokens + run.outputTokens > 0) {
			Text("${run.inputTokens} in · ${run.outputTokens} out tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable private fun AgentTimelineCard(item: AgentTimelineItem, run: AgentRunState, approval: PendingApproval?, onEvent: (StudioEvent) -> Unit) {
	when (item) {
		is AgentTimelineItem.Message -> ChatMessageCard(ChatMessage(item.key, ChatRole.Assistant, item.text))
		is AgentTimelineItem.Tool -> AgentToolCard(item, run, approval, onEvent)
		is AgentTimelineItem.Problem -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
			Text(item.text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
		}
		is AgentTimelineItem.Finished -> AgentFinishedCard(item, run, onEvent)
	}
}

@Composable private fun AgentToolCard(item: AgentTimelineItem.Tool, run: AgentRunState, approval: PendingApproval?, onEvent: (StudioEvent) -> Unit) {
	val result = item.result
	var expanded by remember(item.key) { mutableStateOf(false) }
	val container = when {
		result?.ok == false -> MaterialTheme.colorScheme.errorContainer
		item.approval == AgentTimelineItem.Tool.Approval.Pending -> MaterialTheme.colorScheme.tertiaryContainer
		else -> MaterialTheme.colorScheme.surfaceContainerLow
	}
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
		Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					when {
						result == null && item.approval != AgentTimelineItem.Tool.Approval.Pending && !run.status.isTerminal -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
						result?.ok == true -> Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
						result?.ok == false -> Text("✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
					}
					Text(item.tool, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
				}
				if (item.arguments.isNotEmpty()) {
					CodeSnippet(item.arguments.entries.joinToString("  ") { "${it.key}=${ReplGrammar.quoted(it.value)}" }, language = AmooArgumentRules, softWrap = true)
				}
				when (item.approval) {
					AgentTimelineItem.Tool.Approval.Pending -> {
						Text("This action can destroy device or app state.", style = MaterialTheme.typography.bodySmall)
						val waitingHere = (approval?.action as? ApprovedAction.AgentTool)?.callId == item.key.removePrefix("t-")
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							OutlinedButton({ onEvent(StudioEvent.ResolveApproval(false)) }, enabled = waitingHere) { Text("Decline") }
							Button({ onEvent(StudioEvent.ResolveApproval(true)) }, enabled = waitingHere) { Text("Allow") }
						}
					}
					AgentTimelineItem.Tool.Approval.Approved -> Text("Allowed by you", style = MaterialTheme.typography.labelSmall)
					AgentTimelineItem.Tool.Approval.Declined -> Text("Declined by you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
					AgentTimelineItem.Tool.Approval.NotNeeded -> Unit
				}
				result?.text?.takeIf(String::isNotBlank)?.let { output ->
					Text(
						output,
						style = MaterialTheme.typography.bodySmall,
						maxLines = if (expanded) Int.MAX_VALUE else 3,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.clickable(onClickLabel = if (expanded) "Collapse output" else "Expand output") { expanded = !expanded },
					)
				}
			}
			result?.screenshot?.let { screenshot ->
				ScreenshotThumbnail(
					screenshot,
					description = "Screen after ${item.tool}",
					onClick = { onEvent(StudioEvent.PinScreenshot(PinnedScreenshot(item.tool, screenshot, result.gesturePoint))) },
					modifier = Modifier.size(width = 64.dp, height = 116.dp),
				)
			}
		}
	}
}

@Composable private fun AgentFinishedCard(item: AgentTimelineItem.Finished, run: AgentRunState, onEvent: (StudioEvent) -> Unit) {
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
		Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(
				when (item.status) {
					AgentRunStatus.Completed -> "Run complete. The session was recorded."
					AgentRunStatus.Cancelled -> "Run stopped. What it did so far was recorded."
					else -> "Run failed. What it did so far was recorded."
				},
				fontWeight = FontWeight.SemiBold,
			)
			item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
			val sessionId = run.sessionId
			if (sessionId != null) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Button({ onEvent(StudioEvent.GenerateTest(GenerationSource.Session(sessionId, run.request.take(60)), run.stepScreenshots)) }) { Text("Generate test") }
				}
			}
		}
	}
}

@Composable private fun StreamingBubble(text: String) {
	Row(Modifier.fillMaxWidth()) {
		Card(Modifier.fillMaxWidth(0.82f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
			Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text("Amoo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
				Text(text)
			}
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
