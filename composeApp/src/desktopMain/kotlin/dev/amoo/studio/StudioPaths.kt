package dev.amoo.studio

import java.io.File

/**
 * Where packaged Studio keeps per-user files: Application Support on macOS, XDG data on Linux.
 * Everything Studio writes outside the user's own project files lives below this directory.
 */
internal fun studioDataDirectory(hostPlatform: HostPlatform, environment: Map<String, String> = System.getenv(), home: File = File(System.getProperty("user.home"))): File =
	when (hostPlatform) {
		HostPlatform.MacOS -> File(home, "Library/Application Support/Amoo Studio")
		else -> File(environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank) ?: File(home, ".local/share").path, "amoo-studio")
	}
