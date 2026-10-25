package dev.amoo.studio

import io.github.maniramezan.testcommons.TemporaryDirectory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmooTestFileStoreTest {
	private val store = AmooTestFileStore()
	private val directory = TemporaryDirectory(prefix = "amoo-studio")

	@AfterTest
	fun tearDown() = directory.remove()

	@Test
	fun `saved tests load without losing the executable plan`() {
		val file = File(directory.file, "sign-in.amootest")
		val expected = AmooTest(
			name = "Sign in",
			steps = listOf(TestStep("step-1", "Tap Sign in", "Home is visible")),
			compiledPlan = CompiledToolPlan("studio-console", "1", listOf("devices list")),
		)

		store.save(expected, file)

		assertEquals(expected, store.load(file))
	}

	@Test
	fun `unsupported file versions are rejected`() {
		val file = directory.write("""{"formatVersion":2,"name":"Future","description":"","platform":"Ios","steps":[]}""", "future.amootest")

		assertFailsWith<IllegalArgumentException> { store.load(file) }
	}
}
