package dev.amoo.studio

import io.github.maniramezan.testcommons.TemporaryDirectory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHistoryStoreTest {
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
	private val directory = TemporaryDirectory(prefix = "amoo-chat")
	private val preferences = Preferences.userRoot().node("dev/amoo/studio-test/${UUID.randomUUID()}")
	private val file = File(directory.file, "chat-history.json")

	@AfterTest
	fun tearDown() {
		directory.remove()
		preferences.removeNode()
	}

	private fun messages(count: Int) = List(count) { ChatMessage("m-$it", if (it % 2 == 0) ChatRole.User else ChatRole.Assistant, "message $it ".repeat(20)) }

	@Test
	fun `history larger than the preferences value limit round-trips`() {
		val store = ChatHistoryStore(file, json)
		val history = messages(100)
		assertTrue(json.encodeToString(history).length > Preferences.MAX_VALUE_LENGTH)

		store.save(history)

		assertEquals(history, ChatHistoryStore(file, json).load())
	}

	@Test
	fun `only the most recent messages are kept`() {
		val store = ChatHistoryStore(file, json, retainedMessages = 3)

		store.save(messages(5))

		assertEquals(messages(5).takeLast(3), store.load())
	}

	@Test
	fun `the legacy preferences value is migrated once and removed`() {
		val legacy = messages(4)
		preferences.put("chatMessages", json.encodeToString(legacy))
		val store = ChatHistoryStore(file, json, preferences)

		assertEquals(legacy, store.load())

		assertTrue(file.isFile)
		assertNull(preferences.get("chatMessages", null))
		assertEquals(legacy, store.load())
	}

	@Test
	fun `a corrupt history file is quarantined instead of overwritten`() {
		directory.write("not json", "chat-history.json")
		val store = ChatHistoryStore(file, json)

		assertEquals(emptyList(), store.load())

		assertTrue(File(directory.file, "chat-history.json.corrupt").isFile)
		assertEquals(false, file.exists())
	}

	@Test
	fun `a missing history is empty`() {
		assertEquals(emptyList(), ChatHistoryStore(file, json, preferences).load())
	}
}
