package dev.amoo.studio

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmooRuntimeInstallerTest {
	private val scratch = createTempDirectory("amoo-runtime-test").toFile()
	private val resources = File(scratch, "resources").apply { mkdirs() }
	private val runtimeRoot = File(scratch, "runtime")

	@AfterTest
	fun tearDown() {
		scratch.deleteRecursively()
	}

	@Test
	fun `development builds without an embedded archive install nothing`() {
		assertNull(AmooRuntimeInstaller(resources, runtimeRoot).install())
		assertNull(AmooRuntimeInstaller(null, runtimeRoot).install())
	}

	@Test
	fun `extracts the executable and companions with symlinks and permissions intact`() {
		val checksum = stageArchive()

		val amoo = AmooRuntimeInstaller(resources, runtimeRoot).install()!!

		assertEquals(File(File(runtimeRoot, checksum), "amoo"), amoo)
		assertTrue(amoo.canExecute())
		val companion = File(amoo.parentFile, "CompanionApps/iOS")
		assertTrue(File(companion, "project.yml").isFile)
		val link = File(companion, "prebuilt/Current").toPath()
		assertTrue(Files.isSymbolicLink(link))
		assertEquals("Products", Files.readSymbolicLink(link).toString())
	}

	@Test
	fun `a completed runtime is reused without extracting again`() {
		stageArchive()
		val first = AmooRuntimeInstaller(resources, runtimeRoot).install()!!
		val sentinel = File(first.parentFile, "sentinel").apply { writeText("kept") }

		val second = AmooRuntimeInstaller(resources, runtimeRoot, tarExecutable = "/nonexistent/tar").install()!!

		assertEquals(first, second)
		assertTrue(sentinel.isFile)
	}

	@Test
	fun `a corrupt archive fails without leaving a runtime behind`() {
		stageArchive()
		File(resources, AMOO_RUNTIME_ARCHIVE).appendBytes(byteArrayOf(1, 2, 3))

		val error = assertFailsWith<IllegalStateException> { AmooRuntimeInstaller(resources, runtimeRoot).install() }

		assertTrue(error.message!!.contains("corrupt"))
		assertTrue(runtimeRoot.listFiles().orEmpty().none { !it.name.startsWith(".") })
	}

	@Test
	fun `an interrupted extraction is replaced`() {
		val checksum = stageArchive()
		File(runtimeRoot, "$checksum/CompanionApps").mkdirs()

		val amoo = AmooRuntimeInstaller(resources, runtimeRoot).install()!!

		assertTrue(amoo.canExecute())
	}

	@Test
	fun `older runtimes beyond the retention count are pruned`() {
		val old = listOf("a", "b", "c").map { File(runtimeRoot, it.repeat(64)).apply { mkdirs(); File(this, ".amoo-runtime-complete").writeText(name) } }
		old.forEachIndexed { index, directory -> File(directory, ".amoo-runtime-complete").setLastModified(1_000L * (index + 1)) }
		val checksum = stageArchive()

		AmooRuntimeInstaller(resources, runtimeRoot, retainedRuntimes = 2).install()

		val remaining = runtimeRoot.listFiles().orEmpty().map { it.name }.toSet()
		assertEquals(setOf(checksum, "c".repeat(64)), remaining)
	}

	@Test
	fun `a missing checksum file is reported`() {
		stageArchive()
		File(resources, AMOO_RUNTIME_CHECKSUM).delete()

		assertFailsWith<IllegalStateException> { AmooRuntimeInstaller(resources, runtimeRoot).install() }
		assertFalse(runtimeRoot.exists())
	}

	/** Builds a miniature amoo release archive with the system tar, as the release workflow stages it. */
	private fun stageArchive(): String {
		val tree = File(scratch, "tree").apply { deleteRecursively(); mkdirs() }
		File(tree, "amoo").apply { writeText("#!/bin/sh\necho amoo\n"); setExecutable(true) }
		File(tree, "CompanionApps/iOS/prebuilt/Products").mkdirs()
		File(tree, "CompanionApps/iOS/project.yml").writeText("name: AmooCompanion\n")
		Files.createSymbolicLink(File(tree, "CompanionApps/iOS/prebuilt/Current").toPath(), File("Products").toPath())
		val archive = File(resources, AMOO_RUNTIME_ARCHIVE)
		val process = ProcessBuilder("tar", "-czf", archive.absolutePath, "-C", tree.absolutePath, "amoo", "CompanionApps").redirectErrorStream(true).start()
		check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
		val checksum = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
		File(resources, AMOO_RUNTIME_CHECKSUM).writeText("$checksum  $AMOO_RUNTIME_ARCHIVE\n")
		return checksum
	}
}
