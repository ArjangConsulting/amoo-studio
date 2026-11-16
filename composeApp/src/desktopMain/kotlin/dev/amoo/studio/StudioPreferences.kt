package dev.amoo.studio

import java.util.prefs.Preferences

/** What Studio restores about the user's working context on the next launch. */
internal data class PersistedSession(
	val projectPath: String = "",
	val appId: String = "",
	val schemeOrModule: String = "",
	val selectedProviderId: String? = null,
	val selectedDeviceId: String? = null,
	val workbenchSplit: Float = DEFAULT_WORKBENCH_SPLIT,
)

internal data class WindowSize(val width: Int, val height: Int)

/**
 * Typed access to Studio's user preferences. Provider profiles and their keychain-backed API keys are
 * handled by [ProviderStore]; this holds everything else that survives a restart.
 */
internal class StudioPreferences(private val preferences: Preferences) {
	fun themeMode(): ThemeMode = preferences.get(THEME_MODE, null)
		?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
		?: ThemeMode.System

	fun saveThemeMode(themeMode: ThemeMode) = preferences.put(THEME_MODE, themeMode.name)

	fun loadSession(): PersistedSession = PersistedSession(
		projectPath = preferences.get(PROJECT_PATH, ""),
		appId = preferences.get(APP_ID, ""),
		schemeOrModule = preferences.get(SCHEME_OR_MODULE, ""),
		selectedProviderId = preferences.get(SELECTED_PROVIDER, null),
		selectedDeviceId = preferences.get(SELECTED_DEVICE, null),
		workbenchSplit = preferences.getFloat(WORKBENCH_SPLIT, DEFAULT_WORKBENCH_SPLIT),
	)

	/**
	 * A null device selection is not stored: discovery clears the selection while an emulator is
	 * stopped, and the last device should still be there when it comes back.
	 */
	fun saveSession(session: PersistedSession) {
		preferences.put(PROJECT_PATH, session.projectPath)
		preferences.put(APP_ID, session.appId)
		preferences.put(SCHEME_OR_MODULE, session.schemeOrModule)
		session.selectedProviderId?.let { preferences.put(SELECTED_PROVIDER, it) } ?: preferences.remove(SELECTED_PROVIDER)
		session.selectedDeviceId?.let { preferences.put(SELECTED_DEVICE, it) }
		preferences.putFloat(WORKBENCH_SPLIT, session.workbenchSplit)
	}

	fun windowSize(): WindowSize? {
		val width = preferences.getInt(WINDOW_WIDTH, 0)
		val height = preferences.getInt(WINDOW_HEIGHT, 0)
		return if (width >= MIN_WINDOW_WIDTH && height >= MIN_WINDOW_HEIGHT) WindowSize(width, height) else null
	}

	fun saveWindowSize(size: WindowSize) {
		preferences.putInt(WINDOW_WIDTH, size.width)
		preferences.putInt(WINDOW_HEIGHT, size.height)
	}

	companion object {
		const val MIN_WINDOW_WIDTH = 800
		const val MIN_WINDOW_HEIGHT = 560
		private const val THEME_MODE = "themeMode"
		private const val PROJECT_PATH = "projectPath"
		private const val APP_ID = "appId"
		private const val SCHEME_OR_MODULE = "schemeOrModule"
		private const val SELECTED_PROVIDER = "selectedProviderId"
		private const val SELECTED_DEVICE = "selectedDeviceId"
		private const val WORKBENCH_SPLIT = "workbenchSplit"
		private const val WINDOW_WIDTH = "windowWidth"
		private const val WINDOW_HEIGHT = "windowHeight"
	}
}
