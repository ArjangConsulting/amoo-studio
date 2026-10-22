package dev.amoo.studio

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** File name of the Amoo release archive staged into packaged Studio builds. */
internal const val AMOO_RUNTIME_ARCHIVE = "amoo-runtime.tar.gz"

/** Its SHA-256, in `sha256sum` format, staged next to [AMOO_RUNTIME_ARCHIVE]. */
internal const val AMOO_RUNTIME_CHECKSUM = "$AMOO_RUNTIME_ARCHIVE.sha256"

/**
 * Unpacks the Amoo release embedded in packaged Studio builds.
 *
 * Releases embed amoo's own release tarball (the `amoo` executable plus `CompanionApps` with prebuilt
 * iOS test products and Android APKs) as one archive instead of loose resources: jpackage does not
 * preserve the symlinks and executable bits inside the iOS products, and an archive keeps those nested
 * iOS-simulator binaries out of the macOS notarization scan. The archive is extracted once per
 * checksum into [runtimeRoot], where amoo finds `CompanionApps` next to its executable and can launch
 * the prebuilt companions without compiling them.
 */
internal class AmooRuntimeInstaller(
	private val resourcesDirectory: File?,
	private val runtimeRoot: File,
	private val tarExecutable: String = "tar",
	private val retainedRuntimes: Int = 2,
) {
	/**
	 * Returns the extracted `amoo` executable, extracting it first if needed, or null when this build
	 * embeds no runtime (development builds). Throws when the embedded archive is corrupt or cannot be
	 * extracted, so the failure is shown instead of silently falling back to another amoo.
	 */
	fun install(): File? {
		val resources = resourcesDirectory ?: return null
		val archive = File(resources, AMOO_RUNTIME_ARCHIVE).takeIf(File::isFile) ?: return null
		val checksum = File(resources, AMOO_RUNTIME_CHECKSUM).takeIf(File::isFile)?.readText()?.trim()?.substringBefore(' ')?.lowercase()
		check(checksum != null && checksum.matches(SHA256)) { "The embedded Amoo runtime has no valid checksum file" }
		val target = File(runtimeRoot, checksum)
		if (!isComplete(target)) extract(archive, checksum, target)
		prune(keep = target)
		return File(target, "amoo")
	}

	private fun extract(archive: File, checksum: String, target: File) {
		val actual = sha256(archive)
		check(actual == checksum) { "The embedded Amoo runtime is corrupt (checksum $actual, expected $checksum). Reinstall Amoo Studio." }
		runtimeRoot.mkdirs()
		val staging = Files.createTempDirectory(runtimeRoot.toPath(), STAGING_PREFIX).toFile()
		try {
			runTar(archive, staging)
			check(File(staging, "amoo").canExecute()) { "The embedded Amoo runtime does not contain an executable amoo" }
			File(staging, COMPLETE_MARKER).writeText(checksum)
			// A leftover from an interrupted extraction has no marker; replace it.
			if (target.exists() && !isComplete(target)) target.deleteRecursively()
			try {
				Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
			} catch (raced: FileAlreadyExistsException) {
				// Another Studio instance finished first; its copy is identical.
				if (!isComplete(target)) throw raced
			} catch (raced: java.nio.file.DirectoryNotEmptyException) {
				if (!isComplete(target)) throw raced
			} catch (unsupported: AtomicMoveNotSupportedException) {
				throw IllegalStateException("Cannot install the Amoo runtime atomically in $runtimeRoot", unsupported)
			}
		} finally {
			if (staging.exists()) staging.deleteRecursively()
		}
	}

	private fun runTar(archive: File, destination: File) {
		val process = ProcessBuilder(tarExecutable, "-xzf", archive.absolutePath, "-C", destination.absolutePath)
			.redirectErrorStream(true)
			.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
			.start()
		val output = process.inputStream.bufferedReader().use { it.readText() }
		if (!process.waitFor(EXTRACT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
			process.destroyForcibly()
			error("Extracting the Amoo runtime timed out after $EXTRACT_TIMEOUT_MINUTES minutes")
		}
		check(process.exitValue() == 0) { "Extracting the Amoo runtime failed (exit ${process.exitValue()}): ${output.trim().takeLast(500)}" }
	}

	/** Keeps the [retainedRuntimes] most recent runtimes, so an older Studio still running keeps its files. */
	private fun prune(keep: File) {
		val entries = runtimeRoot.listFiles().orEmpty()
		val staleStaging = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
		entries.filter { it.name.startsWith(STAGING_PREFIX) && it.lastModified() < staleStaging }.forEach { it.deleteRecursively() }
		entries.filter { it.isDirectory && it.name.matches(SHA256) && it != keep }
			.sortedByDescending { File(it, COMPLETE_MARKER).lastModified() }
			.drop((retainedRuntimes - 1).coerceAtLeast(0))
			.forEach { it.deleteRecursively() }
	}

	private fun isComplete(directory: File) = File(directory, COMPLETE_MARKER).isFile && File(directory, "amoo").canExecute()

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().buffered().use { input ->
			val buffer = ByteArray(1 shl 16)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	companion object {
		private val SHA256 = Regex("[0-9a-f]{64}")
		private const val COMPLETE_MARKER = ".amoo-runtime-complete"
		private const val STAGING_PREFIX = ".staging-"
		private const val EXTRACT_TIMEOUT_MINUTES = 10L

		/** Where packaged Studio keeps extracted runtimes: Application Support on macOS, XDG data on Linux. */
		fun defaultRuntimeRoot(hostPlatform: HostPlatform): File {
			val home = File(System.getProperty("user.home"))
			return when (hostPlatform) {
				HostPlatform.MacOS -> File(home, "Library/Application Support/Amoo Studio/runtime")
				else -> File(System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: File(home, ".local/share").path, "amoo-studio/runtime")
			}
		}
	}
}
