package dev.amoo.studio

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmooTestFileStoreTest {
	private val store = AmooTestFileStore()

	@Test
	fun `saved tests load without losing the executable plan`() {
		val path = Files.createTempFile("amoo-studio-", ".amootest")
		try {
			val expected = AmooTest(
				name = "Sign in",
				steps = listOf(TestStep("step-1", "Tap Sign in", "Home is visible")),
				compiledPlan = CompiledToolPlan("studio-console", "1", listOf("devices list")),
			)

			store.save(expected, path.toFile())

			assertEquals(expected, store.load(path.toFile()))
		} finally {
			path.deleteIfExists()
		}
	}

	@Test
	fun `unsupported file versions are rejected`() {
		val path = Files.createTempFile("amoo-studio-", ".amootest")
		try {
			path.toFile().writeText("""{"formatVersion":2,"name":"Future","description":"","platform":"Ios","steps":[]}""")

			assertFailsWith<IllegalArgumentException> { store.load(path.toFile()) }
		} finally {
			path.deleteIfExists()
		}
	}
}
