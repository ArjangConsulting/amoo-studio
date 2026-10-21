package dev.amoo.studio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AgentRunStatus {
	@SerialName("running") Running,
	@SerialName("awaitingApproval") AwaitingApproval,
	@SerialName("completed") Completed,
	@SerialName("failed") Failed,
	@SerialName("cancelled") Cancelled;

	val isTerminal: Boolean get() = this == Completed || this == Failed || this == Cancelled
}

@Serializable
enum class AgentEventKind {
	@SerialName("sessionStarted") SessionStarted,
	@SerialName("assistantDelta") AssistantDelta,
	@SerialName("assistantMessage") AssistantMessage,
	@SerialName("toolCall") ToolCall,
	@SerialName("toolResult") ToolResult,
	@SerialName("approvalRequired") ApprovalRequired,
	@SerialName("approvalResolved") ApprovalResolved,
	@SerialName("usage") Usage,
	@SerialName("error") Error,
	@SerialName("finished") Finished,
}

/** One step of an agent run as Amoo pushes it (`agent.event`); fields beyond [kind] depend on the kind. */
@Immutable
@Serializable
data class AgentEvent(
	val runId: String,
	val seq: Int,
	val kind: AgentEventKind,
	val text: String? = null,
	val callId: String? = null,
	val tool: String? = null,
	val arguments: Map<String, String>? = null,
	val ok: Boolean? = null,
	val approved: Boolean? = null,
	val structuredContent: String? = null,
	val screenshot: ScreenshotRef? = null,
	val gesturePoint: GesturePoint? = null,
	val actionIndex: Int? = null,
	val inputTokens: Int? = null,
	val outputTokens: Int? = null,
	val state: AgentRunStatus? = null,
	val sessionId: String? = null,
	val planPath: String? = null,
	val flowPath: String? = null,
)

/** The agent run shown in AI testing. [gap] means a pushed event was missed and `agent.events` should replay. */
@Immutable
data class AgentRunState(
	val runId: String,
	val request: String,
	val status: AgentRunStatus = AgentRunStatus.Running,
	val events: List<AgentEvent> = emptyList(),
	val nextSeq: Int = 0,
	val streamingText: String = "",
	val sessionId: String? = null,
	val planPath: String? = null,
	val pendingApprovalCallId: String? = null,
	val inputTokens: Int = 0,
	val outputTokens: Int = 0,
	val gap: Boolean = false,
) {
	/** Applies a pushed event. Streamed text is appended directly; numbered events must arrive in order. */
	fun applying(event: AgentEvent): AgentRunState {
		if (event.runId != runId) return this
		if (event.kind == AgentEventKind.AssistantDelta) {
			return if (status.isTerminal) this else copy(streamingText = streamingText + event.text.orEmpty())
		}
		return when {
			event.seq < nextSeq -> this
			event.seq > nextSeq -> copy(gap = true)
			else -> recording(event)
		}
	}

	/**
	 * Applies `agent.events` replay. Events Amoo no longer retains are skipped over, so a run that outlived
	 * the replay window still converges on its current state.
	 */
	fun replaying(events: List<AgentEvent>, status: AgentRunStatus, nextSeq: Int): AgentRunState {
		var state = copy(gap = false)
		for (event in events.filter { it.runId == runId && it.kind != AgentEventKind.AssistantDelta }.sortedBy { it.seq }) {
			if (event.seq < state.nextSeq) continue
			state = state.copy(nextSeq = event.seq).recording(event)
		}
		return state.copy(status = status, nextSeq = maxOf(state.nextSeq, nextSeq), gap = false)
	}

	private fun recording(event: AgentEvent): AgentRunState {
		val next = copy(events = events + event, nextSeq = event.seq + 1)
		return when (event.kind) {
			AgentEventKind.SessionStarted -> next.copy(sessionId = event.sessionId)
			AgentEventKind.AssistantMessage -> next.copy(streamingText = "")
			AgentEventKind.ApprovalRequired -> next.copy(status = AgentRunStatus.AwaitingApproval, pendingApprovalCallId = event.callId)
			AgentEventKind.ApprovalResolved -> next.copy(status = AgentRunStatus.Running, pendingApprovalCallId = null)
			AgentEventKind.Usage -> next.copy(inputTokens = inputTokens + (event.inputTokens ?: 0), outputTokens = outputTokens + (event.outputTokens ?: 0))
			AgentEventKind.Finished -> next.copy(
				status = event.state ?: AgentRunStatus.Completed,
				planPath = event.planPath,
				sessionId = event.sessionId ?: sessionId,
				streamingText = "",
				pendingApprovalCallId = null,
			)
			else -> next
		}
	}

	/** Screenshots of the recorded steps by plan operation id (`step-<n>`), for the generated-test view. */
	val stepScreenshots: Map<String, PinnedScreenshot>
		get() = events.filter { it.kind == AgentEventKind.ToolResult && it.screenshot != null && it.actionIndex != null }
			.associate { "step-${it.actionIndex}" to PinnedScreenshot(it.tool.orEmpty(), it.screenshot!!, it.gesturePoint) }

	/** The final assistant message, kept in the conversation once the run ends. */
	val summary: String? get() = events.lastOrNull { it.kind == AgentEventKind.AssistantMessage }?.text
}

/** What the AI testing timeline shows: a tool call is one item from request through approval to result. */
sealed interface AgentTimelineItem {
	val key: String

	data class Message(override val key: String, val text: String) : AgentTimelineItem
	data class Tool(
		override val key: String,
		val tool: String,
		val arguments: Map<String, String>,
		val approval: Approval = Approval.NotNeeded,
		val result: AgentEvent? = null,
	) : AgentTimelineItem {
		enum class Approval { NotNeeded, Pending, Approved, Declined }
	}
	data class Problem(override val key: String, val text: String) : AgentTimelineItem
	data class Finished(override val key: String, val status: AgentRunStatus, val planPath: String?, val note: String?) : AgentTimelineItem
}

fun AgentRunState.timeline(): List<AgentTimelineItem> {
	val items = mutableListOf<AgentTimelineItem>()
	val toolIndex = mutableMapOf<String, Int>()
	fun updateTool(callId: String?, change: (AgentTimelineItem.Tool) -> AgentTimelineItem.Tool) {
		val index = callId?.let(toolIndex::get) ?: return
		items[index] = change(items[index] as AgentTimelineItem.Tool)
	}
	for (event in events) {
		when (event.kind) {
			AgentEventKind.AssistantMessage -> items += AgentTimelineItem.Message("m-${event.seq}", event.text.orEmpty())
			AgentEventKind.ToolCall -> {
				toolIndex[event.callId.orEmpty()] = items.size
				items += AgentTimelineItem.Tool("t-${event.callId ?: event.seq}", event.tool.orEmpty(), event.arguments.orEmpty())
			}
			AgentEventKind.ApprovalRequired -> updateTool(event.callId) { it.copy(approval = AgentTimelineItem.Tool.Approval.Pending) }
			AgentEventKind.ApprovalResolved -> updateTool(event.callId) {
				it.copy(approval = if (event.approved == true) AgentTimelineItem.Tool.Approval.Approved else AgentTimelineItem.Tool.Approval.Declined)
			}
			AgentEventKind.ToolResult -> if (event.callId in toolIndex) updateTool(event.callId) { it.copy(result = event) }
				else items += AgentTimelineItem.Problem("r-${event.seq}", event.text.orEmpty())
			AgentEventKind.Error -> items += AgentTimelineItem.Problem("e-${event.seq}", event.text.orEmpty())
			AgentEventKind.Finished -> items += AgentTimelineItem.Finished("f-${event.seq}", event.state ?: status, event.planPath, event.text)
			AgentEventKind.SessionStarted, AgentEventKind.Usage, AgentEventKind.AssistantDelta -> Unit
		}
	}
	return items
}

/** Whether a message in AI testing can start an agent run, and if not, why. */
sealed interface AgentReadiness {
	data class Ready(val device: StudioDevice, val appId: String) : AgentReadiness
	/** The connected Amoo has no `agent.runs`; messages go to plain chat. */
	data object Unsupported : AgentReadiness
	/** No running device is selected; messages go to plain chat. */
	data object NoDevice : AgentReadiness
	/** A device is selected but the app to launch is unknown. */
	data class NoApp(val device: StudioDevice) : AgentReadiness
}

const val AGENT_RUNS_CAPABILITY = "agent.runs"

fun StudioState.agentReadiness(): AgentReadiness {
	if (!connection.supports(AGENT_RUNS_CAPABILITY)) return AgentReadiness.Unsupported
	val device = devices.firstOrNull { it.id == selectedDeviceId && it.status == DeviceStatus.Running } ?: return AgentReadiness.NoDevice
	val appId = appId.trim().ifEmpty { test.requirements?.appId?.trim().orEmpty() }
	return if (appId.isEmpty()) AgentReadiness.NoApp(device) else AgentReadiness.Ready(device, appId)
}

/** True while a run is active, so the composer steers it instead of starting another. */
val StudioState.agentRunActive: Boolean get() = agentRun?.status?.isTerminal == false
