package dev.amoo.studio

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.prefs.Preferences

/**
 * Keeps the AI-testing conversation in a JSON file in the per-user data directory. It used to live in
 * a preferences value, which the JDK caps at 8 KB, so long conversations silently stopped saving.
 * [load] migrates that legacy value once and then removes it.
 */
internal class ChatHistoryStore(
	private val file: File,
	private val json: Json,
	private val legacyPreferences: Preferences? = null,
	private val retainedMessages: Int = MAX_MESSAGES,
) {
	private val log = StudioLog.forFeature("ChatHistory")

	fun load(): List<ChatMessage> {
		if (file.isFile) return readFile()
		val legacy = legacyPreferences?.get(LEGACY_KEY, null) ?: return emptyList()
		val messages = runCatching { json.decodeFromString<List<ChatMessage>>(legacy) }
			.onFailure { log.error("migrate", it, "legacy value is unreadable and is dropped") }
			.getOrDefault(emptyList())
		runCatching { save(messages) }
			.onSuccess {
				runCatching { legacyPreferences?.remove(LEGACY_KEY) }
				log.info("migrate", "moved ${messages.size} messages from preferences")
			}
			.onFailure { log.error("migrate", it, "kept the legacy value because the file could not be written") }
		return messages
	}

	fun save(messages: List<ChatMessage>) {
		writeAtomically(file, json.encodeToString(messages.takeLast(retainedMessages)))
	}

	private fun readFile(): List<ChatMessage> = runCatching { json.decodeFromString<List<ChatMessage>>(file.readText()) }
		.getOrElse { failure ->
			// Keep the unreadable file for a bug report rather than overwriting it on the next save.
			val quarantined = File(file.parentFile, "${file.name}.corrupt")
			runCatching { file.renameTo(quarantined) }
			log.error("load", failure, "history file is unreadable; moved to ${quarantined.name}")
			emptyList()
		}

	companion object {
		const val FILE_NAME = "chat-history.json"
		const val MAX_MESSAGES = 100
		private const val LEGACY_KEY = "chatMessages"
	}
}
