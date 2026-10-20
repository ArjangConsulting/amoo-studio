package dev.amoo.studio

import io.github.maniramezan.processrpc.RpcNotification
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Advertised by Amoo builds that push `tests.progress` instead of requiring `tests.status` polling. */
internal const val TESTS_PROGRESS_CAPABILITY = "tests.progress"

@Serializable internal data class TestRunStatus(val runId: String, val state: TestRunState, val currentOperation: Int, val totalOperations: Int, val message: String, val sessionId: String? = null, val reportId: String? = null)
@Serializable internal enum class TestRunState { Running, Passed, Failed, Cancelled }

/** A notification pushed by `amoo studio serve`, decoded into Studio's boundary model. */
internal sealed interface AmooNotification {
	data class TestProgress(val status: TestRunStatus) : AmooNotification
}

/**
 * Decodes [notification], or returns null for methods this Studio does not know, so newer Amoo builds
 * can add notifications without breaking older clients. A known method with malformed params throws.
 */
internal fun Json.decodeAmooNotification(notification: RpcNotification): AmooNotification? = when (notification.method) {
	"tests.progress" -> AmooNotification.TestProgress(decodeFromJsonElement<TestRunStatus>(notification.params))
	else -> null
}
