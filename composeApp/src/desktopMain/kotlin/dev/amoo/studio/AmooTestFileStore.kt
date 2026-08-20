package dev.amoo.studio

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal class AmooTestFileStore(
	private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
	fun load(file: File): AmooTest = json.decodeFromString<AmooTest>(file.readText()).also {
		require(it.formatVersion == 1) { "Unsupported .amootest format version ${it.formatVersion}" }
	}

	fun save(test: AmooTest, file: File) {
		file.writeText(json.encodeToString(test))
	}
}
