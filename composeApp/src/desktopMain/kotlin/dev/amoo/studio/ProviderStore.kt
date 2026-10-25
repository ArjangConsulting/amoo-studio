package dev.amoo.studio

import com.maniramezan.credentialkeychain.CredentialKeychain
import com.maniramezan.credentialkeychain.KeychainOptions
import com.maniramezan.credentialkeychain.KeychainUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.time.Duration.Companion.seconds

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
				.onFailure {
					if (it is CancellationException) throw it
					failures[id] = it
				}
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

internal fun createProviderKeychain(): CredentialKeychain = CredentialKeychain.forCurrentPlatform(
	serviceName = "amoo-studio",
	accountName = "amoo-studio",
	options = KeychainOptions(desktopCommandTimeout = 60.seconds),
)

/** Maps platform failure categories to safe, actionable copy without exposing backend diagnostics. */
internal fun Throwable.credentialStorageMessage(): String = when ((this as? KeychainUnavailableException)?.reason) {
	KeychainUnavailableException.Reason.Unsupported -> "Secure storage is unavailable. On Linux, install secret-tool and start a Secret Service session, then retry."
	KeychainUnavailableException.Reason.Locked -> "The keychain is locked or access was denied. Unlock it or allow access, then retry."
	KeychainUnavailableException.Reason.Canceled -> "Keychain access was canceled. Retry when you are ready to allow access."
	KeychainUnavailableException.Reason.AuthenticationInvalidated -> "The stored key can no longer be unlocked. Remove this provider and add it again with a new API key."
	KeychainUnavailableException.Reason.Corrupted -> "The stored key could not be decoded. Remove this provider and add it again with a new API key."
	KeychainUnavailableException.Reason.Failed, null -> "Secure storage could not complete the operation. Check your OS keychain and retry."
}
