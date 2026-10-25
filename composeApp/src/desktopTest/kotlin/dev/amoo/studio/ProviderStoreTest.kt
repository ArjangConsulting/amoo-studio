package dev.amoo.studio

import com.maniramezan.credentialkeychain.CredentialKeychain
import com.maniramezan.credentialkeychain.KeychainUnavailableException
import io.github.maniramezan.testcommons.CallRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
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
		keychain.readFailures["b"] = KeychainUnavailableException(KeychainUnavailableException.Reason.Locked, "locked")

		val result = store.loadApiKeys(listOf("a", "b", "c"))

		assertEquals(mapOf("a" to Secret("sk-a")), result.keys)
		assertEquals(setOf("b"), result.failures.keys)
	}
	@Test
	fun `keychain values retain their exact whitespace and deletes affect only one profile`() {
		store.saveApiKey("a", Secret("  exact key  "))
		store.saveApiKey("b", Secret("another key"))

		assertEquals(Secret("  exact key  "), store.loadApiKeys(listOf("a")).keys["a"])
		store.deleteApiKey("a")

		assertEquals(mapOf("b" to "another key"), keychain.entries)
		assertEquals(listOf("a"), keychain.deletions.values())
		assertEquals(0, keychain.clears.count)
	}

	@Test
	fun `unavailable writes and deletes propagate instead of falling back to preferences`() {
		val locked = KeychainUnavailableException(KeychainUnavailableException.Reason.Locked, "private diagnostic")
		keychain.mutationFailure = locked

		assertSame(locked, assertFailsWith<KeychainUnavailableException> { store.saveApiKey("a", Secret("test-secret")) })
		assertSame(locked, assertFailsWith<KeychainUnavailableException> { store.deleteApiKey("a") })
		assertTrue(preferences.keys().isEmpty())
		assertTrue(keychain.entries.isEmpty())
	}

	@Test
	fun `read cancellation stops loading instead of becoming a credential failure`() {
		keychain.readFailures["a"] = CancellationException("canceled")

		assertFailsWith<CancellationException> { store.loadApiKeys(listOf("a", "b")) }
		assertEquals(listOf("a"), keychain.reads.values())
	}

	@Test
	fun `every keychain failure reason has safe remediation without backend diagnostics`() {
		val messages = KeychainUnavailableException.Reason.entries.associateWith { reason ->
			KeychainUnavailableException(reason, "sensitive backend diagnostic").credentialStorageMessage()
		}

		assertTrue(messages.values.all { "sensitive" !in it && it.isNotBlank() })
		assertTrue(messages.getValue(KeychainUnavailableException.Reason.Locked).contains("Unlock"))
		assertTrue(messages.getValue(KeychainUnavailableException.Reason.Canceled).contains("Retry when you are ready"))
		assertTrue(messages.getValue(KeychainUnavailableException.Reason.Corrupted).contains("Remove this provider"))
		assertTrue(messages.getValue(KeychainUnavailableException.Reason.AuthenticationInvalidated).contains("new API key"))
		assertTrue(messages.getValue(KeychainUnavailableException.Reason.Unsupported).contains("Secret Service"))
		assertTrue("sensitive" !in IllegalStateException("sensitive fallback diagnostic").credentialStorageMessage())
	}

}

private class FakeKeychain : CredentialKeychain {
	val entries = mutableMapOf<String, String>()
	val readFailures = mutableMapOf<String, Throwable>()
	var mutationFailure: Throwable? = null
	val reads = CallRecorder<String>()
	val deletions = CallRecorder<String>()
	val clears = CallRecorder<Unit>()

	override fun read(key: String): String? {
		reads.record(key)
		readFailures[key]?.let { throw it }
		return entries[key]
	}

	override fun write(key: String, value: String) {
		mutationFailure?.let { throw it }
		require(value.isNotBlank()) { "blank values are rejected" }
		entries[key] = value
	}

	override fun delete(key: String) {
		mutationFailure?.let { throw it }
		deletions.record(key)
		entries.remove(key)
	}

	override fun clear() {
		clears.record(Unit)
		entries.clear()
	}
}
