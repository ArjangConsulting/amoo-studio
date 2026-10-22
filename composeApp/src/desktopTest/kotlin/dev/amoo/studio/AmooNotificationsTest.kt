package dev.amoo.studio

import io.github.maniramezan.processrpc.RpcNotification
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AmooNotificationsTest {
	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun `test progress decodes into a run status`() {
		val params = buildJsonObject {
			put("runId", "run-1"); put("state", "Running"); put("currentOperation", 2); put("totalOperations", 5); put("message", "Running tap_element…")
		}

		val decoded = json.decodeAmooNotification(RpcNotification("tests.progress", params))

		assertEquals(AmooNotification.TestProgress(TestRunStatus("run-1", TestRunState.Running, 2, 5, "Running tap_element…")), decoded)
	}

	@Test
	fun `unknown methods are ignored for forward compatibility`() {
		assertNull(json.decodeAmooNotification(RpcNotification("agent.future", JsonObject(emptyMap()))))
	}

	@Test
	fun `malformed params for a known method are reported`() {
		assertFailsWith<SerializationException> { json.decodeAmooNotification(RpcNotification("tests.progress", buildJsonObject { put("runId", "run-1") })) }
	}
}
