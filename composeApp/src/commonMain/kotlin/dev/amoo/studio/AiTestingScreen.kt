package dev.amoo.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.arjangconsulting.codeeditor.CodeSnippet
import com.arjangconsulting.codeeditor.tokenRules
import io.github.maniramezan.kmpcomponents.AssistantContextBody
import io.github.maniramezan.kmpcomponents.AssistantContextCard
import io.github.maniramezan.kmpcomponents.AssistantQuickActionChips
import io.github.maniramezan.kmpcomponents.AssistantQuickActionState
import io.github.maniramezan.kmpcomponents.ChatComposer
import io.github.maniramezan.kmpcomponents.ChatComposerLabels
import io.github.maniramezan.kmpcomponents.ChatErrorAction
import io.github.maniramezan.kmpcomponents.ChatLog
import io.github.maniramezan.kmpcomponents.ChatMessage as UiChatMessage
import io.github.maniramezan.kmpcomponents.ChatMessageSender
import io.github.maniramezan.kmpcomponents.ChatMessageState
import io.github.maniramezan.kmpcomponents.ContentRow
import io.github.maniramezan.kmpcomponents.DisclosureCard
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.MenuPicker
import io.github.maniramezan.kmpcomponents.NoticeCard
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.SegmentedContent
import io.github.maniramezan.kmpcomponents.SegmentedItem
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.TonalBadge
import io.github.maniramezan.kmpcomponents.kmpSpacing
import io.github.maniramezan.kmpcomponents.rememberReduceMotionEnabled
import io.github.maniramezan.kmpcomponents.rememberTypewriterReveal

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
	val canChat = state.connection.supports("chat.send")
	Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
		FlowLayout(Modifier.fillMaxWidth()) {
			MenuPicker<ProviderProfile?>(state.providers, provider, { selected -> selected?.let { onEvent(StudioEvent.SelectProvider(it.id)) } }, "Provider", { it?.name ?: "Select a provider" }, Modifier.widthIn(min = 160.dp, max = 280.dp), enabled = state.providers.isNotEmpty())
			ThemeTextButton("Clear", { onEvent(StudioEvent.ClearChat) }, enabled = state.chat.messages.isNotEmpty() && !state.agentRunActive)
			ThemeTextButton("Provider settings", { onEvent(StudioEvent.SelectSection(StudioSection.Settings)) })
		}
		ReadinessBanner(readiness, canChat, onEvent)
		val run = state.agentRun
		if (run == null) {
			if (state.chat.messages.isEmpty()) EmptyAgentPrompt(readiness, onEvent)
			Conversation(state, onEvent, Modifier.weight(1f))
		} else key(run.runId) {
			SegmentedContent(
				items = listOf(SegmentedItem("Conversation"), SegmentedItem("Run activity")),
				modifier = Modifier.weight(1f).fillMaxWidth(),
				initialSelectedIndex = 1,
			) { index, _ ->
				if (index == 0) Conversation(state, onEvent, Modifier.fillMaxSize())
				else AgentActivity(state, run, onEvent)
			}
		}
		ContentRow("Active test: ${state.test.name}", supportingText = "${state.test.platform.label} · ${state.test.steps.size} steps${if (state.isTestDirty) " · unsaved changes" else ""}", onClick = { onEvent(StudioEvent.SelectSection(StudioSection.Tests)) })
		state.chat.proposedPlan?.let { plan -> ProposedPlanCard(plan, onEvent) }
		Composer(state, provider != null, readiness, canChat, onEvent)
	}
}

@Composable private fun Conversation(state: StudioState, onEvent: (StudioEvent) -> Unit, modifier: Modifier) {
	val messages = remember(state.chat.messages, state.chat.operation, state.chat.lastError) {
		buildList {
			state.chat.messages.forEach { message ->
				add(UiChatMessage("message-${message.id}", if (message.role == ChatRole.User) ChatMessageSender.USER else ChatMessageSender.ASSISTANT, ChatMessageState.Content(message.content)))
			}
			if (state.chat.operation == ChatOperation.Sending) add(UiChatMessage("pending-reply", ChatMessageSender.ASSISTANT, ChatMessageState.Typing))
			if (state.chat.lastError != null) add(UiChatMessage("failed-reply", ChatMessageSender.ASSISTANT, ChatMessageState.Error))
		}
	}
	ChatLog(
		messages = messages,
		idleHint = "Ask Amoo to explore, explain, or turn an idea into test steps.",
		errorAction = ChatErrorAction(state.chat.lastError.orEmpty(), "Retry", { onEvent(StudioEvent.RetryLastChat) }),
		modifier = modifier,
		typingDescription = "Amoo is thinking…",
	)
}

@Composable private fun AgentActivity(state: StudioState, run: AgentRunState, onEvent: (StudioEvent) -> Unit) {
	val listState = rememberLazyListState()
	val timeline = remember(run.events) { run.timeline() }
	val followLatest = remember(timeline, run.streamingText) { !listState.canScrollForward }
	val reduceMotion = rememberReduceMotionEnabled()
	LaunchedEffect(timeline.size, run.streamingText, followLatest) {
		val last = listState.layoutInfo.totalItemsCount - 1
		if (followLatest && last >= 0) {
			if (reduceMotion) listState.scrollToItem(last) else listState.animateScrollToItem(last)
		}
	}
	LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
		item(key = "run-${run.runId}") { AgentRunHeader(run) }
		items(timeline, key = { it.key }) { item -> AgentTimelineCard(item, run, state.pendingApproval, onEvent) }
		if (run.streamingText.isNotEmpty()) item(key = "streaming") {
			val revealed by rememberTypewriterReveal(run.streamingText)
			AssistantContextCard("Amoo", body = AssistantContextBody(revealed))
		}
	}
}

@Composable private fun ReadinessBanner(readiness: SessionReadiness, canChat: Boolean, onEvent: (StudioEvent) -> Unit) {
	val (message, action) = when (readiness) {
		is SessionReadiness.Ready -> return
		SessionReadiness.Unsupported -> if (canChat) return else "The connected Amoo version supports neither agent runs nor chat. Update Amoo." to null
		SessionReadiness.NoDevice -> "Select a running device in Devices to let the agent drive it. Until then, messages go to chat." to "Open Devices"
		is SessionReadiness.NoApp -> "Set the app's bundle or application ID in Devices so the agent can launch it on ${readiness.device.name}." to "Open Devices"
	}
	NoticeCard(message, actionLabel = action, onAction = action?.let { { onEvent(StudioEvent.SelectSection(StudioSection.Devices)) } })
}

@Composable private fun EmptyAgentPrompt(readiness: SessionReadiness, onEvent: (StudioEvent) -> Unit) {
	if (readiness is SessionReadiness.Ready) AssistantContextCard("Ready to test", highlight = readiness.device.name, body = AssistantContextBody("Tell the agent what to test. It drives the device, records a session, and can turn it into a native test."))
	AssistantQuickActionChips(
		actions = listOf("Sign in with the demo account and verify the home screen", "Explore the current screen", "Suggest edge cases"),
		actionState = { AssistantQuickActionState(isSelected = false, isEnabled = true) },
		label = { it },
		onAction = { onEvent(StudioEvent.ChangeChatInput(it)) },
	)
}

@Composable private fun Composer(state: StudioState, hasProvider: Boolean, readiness: SessionReadiness, canChat: Boolean, onEvent: (StudioEvent) -> Unit) {
	val active = state.agentRunActive
	val sending = state.chat.operation == ChatOperation.Sending
	val sendLabel = when {
		active -> "Send guidance"
		readiness is SessionReadiness.Ready -> "Start agent run"
		else -> "Send"
	}
	ChatComposer(
		value = state.chat.input,
		onValueChange = { onEvent(StudioEvent.ChangeChatInput(it)) },
		onSend = { onEvent(if (active) StudioEvent.SteerAgentRun else StudioEvent.SendChat) },
		modifier = Modifier.fillMaxWidth(),
		sendEnabled = !sending && (active || hasProvider && (readiness is SessionReadiness.Ready || canChat && readiness !is SessionReadiness.NoApp)),
		labels = ChatComposerLabels(placeholder = if (active) "Guide the agent" else "Ask Amoo", send = sendLabel),
	)
	FlowLayout {
		if (state.agentRun != null && !active) ThemeTextButton("Dismiss run", { onEvent(StudioEvent.DismissAgentRun) })
		if (active) SecondaryButton("Stop", { onEvent(StudioEvent.CancelAgentRun) })
		if (sending) SecondaryButton("Cancel", { onEvent(StudioEvent.CancelChat) })
	}
}

@Composable private fun AgentRunHeader(run: AgentRunState) {
	val status = when (run.status) {
		AgentRunStatus.Running -> "Agent running"
		AgentRunStatus.AwaitingApproval -> "Waiting for your approval"
		AgentRunStatus.Completed -> "Agent finished"
		AgentRunStatus.Failed -> "Agent run failed"
		AgentRunStatus.Cancelled -> "Agent run stopped"
	}
	Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
		if (!run.status.isTerminal) InlineProgressIndicator(status) else TonalBadge(status, tone = if (run.status == AgentRunStatus.Completed) StatusTone.SUCCESS else StatusTone.WARNING)
		Spacer(Modifier.weight(1f))
		if (run.inputTokens + run.outputTokens > 0) {
			Text("${run.inputTokens} in · ${run.outputTokens} out tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable private fun AgentTimelineCard(item: AgentTimelineItem, run: AgentRunState, approval: PendingApproval?, onEvent: (StudioEvent) -> Unit) {
	when (item) {
		is AgentTimelineItem.Message -> AssistantContextCard("Amoo", body = AssistantContextBody(item.text))
		is AgentTimelineItem.Tool -> AgentToolCard(item, run, approval, onEvent)
		is AgentTimelineItem.Problem -> StatusMessage(item.text, tone = StatusTone.ERROR)
		is AgentTimelineItem.Finished -> AgentFinishedCard(item, run, onEvent)
	}
}

@Composable private fun AgentToolCard(item: AgentTimelineItem.Tool, run: AgentRunState, approval: PendingApproval?, onEvent: (StudioEvent) -> Unit) {
	val result = item.result
	var expanded by remember(item.key) { mutableStateOf(false) }

	ThemeCard {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.medium)) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.extraSmall)) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
					when {
						result == null && item.approval != AgentTimelineItem.Tool.Approval.Pending && !run.status.isTerminal -> InlineProgressIndicator("Running")
						result?.ok == true -> TonalBadge("Complete", tone = StatusTone.SUCCESS)
						result?.ok == false -> TonalBadge("Failed", tone = StatusTone.ERROR)
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
						Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.kmpSpacing.small)) {
							SecondaryButton("Decline", { onEvent(StudioEvent.ResolveApproval(false)) }, enabled = waitingHere)
							PrimaryButton("Allow", { onEvent(StudioEvent.ResolveApproval(true)) }, enabled = waitingHere)
						}
					}
					AgentTimelineItem.Tool.Approval.Approved -> Text("Allowed by you", style = MaterialTheme.typography.labelSmall)
					AgentTimelineItem.Tool.Approval.Declined -> Text("Declined by you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
					AgentTimelineItem.Tool.Approval.NotNeeded -> Unit
				}
				result?.text?.takeIf(String::isNotBlank)?.let { output ->
					DisclosureCard(expanded, { expanded = it }, summary = { Text(output, maxLines = 3, overflow = TextOverflow.Ellipsis) }, detail = { Text(output) })
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
	val title = when (item.status) {
		AgentRunStatus.Completed -> "Run complete. The session was recorded."
		AgentRunStatus.Cancelled -> "Run stopped. What it did so far was recorded."
		else -> "Run failed. What it did so far was recorded."
	}
	val sessionId = run.sessionId
	NoticeCard(
		message = item.note.orEmpty(),
		title = title,
		tone = when (item.status) {
			AgentRunStatus.Completed -> StatusTone.SUCCESS
			AgentRunStatus.Cancelled -> StatusTone.WARNING
			else -> StatusTone.ERROR
		},
		actionLabel = sessionId?.let { "Generate test" },
		onAction = sessionId?.let { { onEvent(StudioEvent.GenerateTest(GenerationSource.Session(it, run.request.take(60)), run.stepScreenshots)) } },
	)
}

@Composable
private fun ProposedPlanCard(plan: CompiledToolPlan, onEvent: (StudioEvent) -> Unit) {
	SectionCard("Review AI-proposed plan", Modifier.fillMaxWidth(), description = "Nothing changes until you apply this plan.") {
		plan.toolOperations.forEachIndexed { index, operation -> key(operation.id) {
			Text("${index + 1}. ${operation.tool}${if (operation.arguments.isEmpty()) "" else " · " + operation.arguments.entries.joinToString { "${it.key}=${it.value}" }}")
		} }
		FlowLayout {
			ThemeTextButton("Discard", { onEvent(StudioEvent.RejectProposedPlan) })
			PrimaryButton("Apply to test", { onEvent(StudioEvent.ApplyProposedPlan) }, enabled = plan.toolOperations.isNotEmpty())
		}
	}
}
