package dev.amoo.studio

import io.github.maniramezan.testcommons.TemporaryDirectory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioPathsAndLogTest {
	private val home = File("/Users/test")

	@Test
	fun `macOS keeps data in Application Support`() {
		assertEquals(File(home, "Library/Application Support/Amoo Studio"), studioDataDirectory(HostPlatform.MacOS, emptyMap(), home))
	}

	@Test
	fun `Linux honours XDG_DATA_HOME and falls back to local share`() {
		assertEquals(File("/xdg/amoo-studio"), studioDataDirectory(HostPlatform.Linux, mapOf("XDG_DATA_HOME" to "/xdg"), home))
		assertEquals(File(home, ".local/share/amoo-studio"), studioDataDirectory(HostPlatform.Linux, mapOf("XDG_DATA_HOME" to " "), home))
	}

	@Test
	fun `the runtime lives below the data directory`() {
		assertEquals(File(studioDataDirectory(HostPlatform.MacOS), "runtime"), AmooRuntimeInstaller.defaultRuntimeRoot(HostPlatform.MacOS))
	}

	@Test
	fun `the log file records operations with their context and failures`() {
		val directory = TemporaryDirectory(prefix = "amoo-log")
		val logs = File(directory.file, "logs")
		StudioLog.install(logs)
		val installed = StudioLog.directory ?: logs

		val feature = StudioLog.forFeature("Test")
		feature.info("rpc.call", "method=tests.start runId=run-1")
		feature.debug("noise", "hidden")
		feature.error("rpc.failed", IllegalStateException("boom"), "method=tests.start")
		java.util.logging.Logger.getLogger("dev.amoo.studio").handlers.forEach { it.flush() }

		val text = installed.listFiles().orEmpty().filter { it.name.endsWith(".log") }.joinToString("\n") { it.readText() }
		assertTrue("INFO [Test] rpc.call method=tests.start runId=run-1" in text, text)
		assertTrue("ERROR [Test] rpc.failed method=tests.start error=IllegalStateException: boom" in text, text)
		assertFalse("hidden" in text)
	}
}
