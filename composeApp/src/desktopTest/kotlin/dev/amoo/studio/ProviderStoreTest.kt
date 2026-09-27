package dev.amoo.studio

import com.maniramezan.credentialkeychain.CredentialKeychain
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderStoreTest {
	private val preferences = Preferences.userRoot().node("dev/amoo/studio-test/${UUID.randomUUID()}")
	private val keychain = FakeKeychain()
	private val store = ProviderStore(preferences, keychain, Json { ignoreUnknownKeys = true })

	@AfterTest
	fun removePreferences() = preferences.removeNode()

	@Test
	fun `profiles round-trip without their API keys`() {
		val profile = ProviderProfile("a", "A", ProviderKind.OpenAI, "https://api.openai.com/v1", "gpt-5", apiKey = Secret("sk-secret"))

		store.saveProfiles(listOf(profile))

		assertTrue("sk-secret" !in preferences.get("providers", "").orEmpty())
		assertEquals(listOf(profile.copy(apiKey = Secret.Empty)), store.loadProfiles())
	}

	@Test
	fun `missing or corrupt preferences fall back to default providers`() {
		assertEquals(defaultProviders(), store.loadProfiles())
		preferences.put("providers", "not json")
		assertEquals(defaultProviders(), store.loadProfiles())
	}

	@Test
	fun `blank API keys delete the keychain entry instead of writing it`() {
		store.saveApiKey("a", Secret("sk-secret"))
		assertEquals("sk-secret", keychain.entries["a"])

		store.saveApiKey("a", Secret(""))

		assertEquals(null, keychain.entries["a"])
	}

	@Test
	fun `failed keychain reads are reported instead of looking like missing keys`() {
		keychain.entries["a"] = "sk-a"
		keychain.failingReads += "b"

		val result = store.loadApiKeys(listOf("a", "b", "c"))

		assertEquals(mapOf("a" to Secret("sk-a")), result.keys)
		assertEquals(setOf("b"), result.failures.keys)
	}
}

private class FakeKeychain : CredentialKeychain {
	val entries = mutableMapOf<String, String>()
	val failingReads = mutableSetOf<String>()

	override fun read(key: String): String? {
		if (key in failingReads) throw IllegalStateException("locked")
		return entries[key]
	}

	override fun write(key: String, value: String) {
		require(value.isNotBlank()) { "blank values are rejected" }
		entries[key] = value
	}

	override fun delete(key: String) { entries.remove(key) }

	override fun clear() = entries.clear()
}
