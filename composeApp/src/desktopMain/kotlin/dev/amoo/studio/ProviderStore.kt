package dev.amoo.studio

import com.maniramezan.credentialkeychain.CredentialKeychain
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences

/**
 * Persists provider profiles in user preferences and their API keys in the OS keychain, keyed by
 * profile id. [ProviderProfile.apiKey] is transient, so preferences never contain a key value.
 * Keychain calls may block or prompt the user; call them off the main thread.
 */
internal class ProviderStore(
	private val preferences: Preferences,
	private val keychain: CredentialKeychain,
	private val json: Json,
) {
	/** Stored profiles without API keys, or [defaultProviders] when nothing valid is stored. */
	fun loadProfiles(): List<ProviderProfile> = runCatching {
		preferences.get(PROFILES_KEY, null)?.let { json.decodeFromString<List<ProviderProfile>>(it) }
	}.getOrNull() ?: defaultProviders()

	fun saveProfiles(profiles: List<ProviderProfile>) {
		preferences.put(PROFILES_KEY, json.encodeToString(profiles))
	}

	/** Reads the key for each id; a failed read is reported per id instead of looking like a missing key. */
	fun loadApiKeys(ids: List<String>): ApiKeyLoadResult {
		val keys = mutableMapOf<String, Secret>()
		val failures = mutableMapOf<String, Throwable>()
		ids.forEach { id ->
			runCatching { keychain.read(id) }
				.onSuccess { value -> if (!value.isNullOrEmpty()) keys[id] = Secret(value) }
				.onFailure { failures[id] = it }
		}
		return ApiKeyLoadResult(keys, failures)
	}

	/** Stores [apiKey] for [id], or deletes the entry when it is blank because the keychain rejects blank values. */
	fun saveApiKey(id: String, apiKey: Secret) {
		if (apiKey.isBlank) keychain.delete(id) else keychain.write(id, apiKey.value)
	}

	fun deleteApiKey(id: String) = keychain.delete(id)

	private companion object {
		const val PROFILES_KEY = "providers"
	}
}

internal data class ApiKeyLoadResult(val keys: Map<String, Secret>, val failures: Map<String, Throwable>)
