package dev.amoo.studio

import io.github.maniramezan.testcommons.TemporaryDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtomicFileWriterTest {
	private val directory = TemporaryDirectory(prefix = "amoo-atomic")

	@AfterTest
	fun tearDown() = directory.remove()

	@Test
	fun `writes new files and replaces existing ones`() {
		val file = File(directory.file, "nested/test.amootest")

		writeAtomically(file, "first")
		assertEquals("first", file.readText())
		writeAtomically(file, "second")
		assertEquals("second", file.readText())
	}

	@Test
	fun `leaves no temporary files behind`() {
		val file = File(directory.file, "test.amootest")

		writeAtomically(file, "content")

		assertEquals(listOf("test.amootest"), directory.file.list().orEmpty().toList())
	}

	@Test
	fun `a failed write keeps the previous contents and cleans up`() {
		val file = directory.write("original", "test.amootest")
		// A directory in the way makes the final move fail after the temporary file is written.
		val blocked = File(directory.file, "blocked").apply { mkdirs() }
		File(blocked, "child").writeText("x")

		runCatching { writeAtomically(blocked, "new") }

		assertEquals("original", file.readText())
		assertTrue(blocked.isDirectory)
		assertEquals(setOf("test.amootest", "blocked"), directory.file.list().orEmpty().toSet())
	}

	@Test
	fun `existing permissions are preserved and symlinks are written through`() {
		val target = directory.write("old", "real.txt")
		Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-rw----"))
		val link = File(directory.file, "link.txt")
		Files.createSymbolicLink(link.toPath(), target.toPath())

		writeAtomically(link, "new")

		assertTrue(Files.isSymbolicLink(link.toPath()))
		assertEquals("new", target.readText())
		assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))
	}
}
