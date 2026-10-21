package dev.amoo.studio

import io.github.maniramezan.kmpcomponents.codeeditor.CompletionItemKind
import io.github.maniramezan.kmpcomponents.codeeditor.CompletionTrigger
import io.github.maniramezan.kmpcomponents.codeeditor.DiagnosticSeverity
import io.github.maniramezan.kmpcomponents.codeeditor.DocumentSnapshot
import io.github.maniramezan.kmpcomponents.codeeditor.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleLanguageServiceTest {
	private val catalog = ToolCatalog.Legacy

	private class ScriptedAssist(val completion: ReplCompletion? = null, val diagnostics: List<ReplDiagnostic>? = null, val failure: Throwable? = null) : ReplAssist {
		override suspend fun complete(line: String, cursor: Int): ReplCompletion? { failure?.let { throw it }; return completion }
		override suspend fun validate(line: String): List<ReplDiagnostic>? { failure?.let { throw it }; return diagnostics }
	}

	private fun service(assist: ReplAssist = ReplAssist.None) = ConsoleLanguageService({ catalog }, { emptyList() }, assist)

	@Test
	fun `Amoo's completion is used with its replacement range`() = runBlocking<Unit> {
		val amoo = ReplCompletion(15, 15, listOf(ReplCompletionItem("login", "login", "button · Sign in", kind = ReplCompletionKind.Element)))

		val list = service(ScriptedAssist(completion = amoo)).complete(DocumentSnapshot("tap_element id=", 1), TextPosition(0, 15), CompletionTrigger.character('='))!!

		val item = list.items.single()
		assertEquals("login", item.textEdit?.newText)
		assertEquals(TextPosition(0, 15), item.textEdit?.range?.start)
		assertEquals(CompletionItemKind.REFERENCE, item.kind)
	}

	@Test
	fun `without Amoo the local grammar completes and validates`() = runBlocking<Unit> {
		val list = service().complete(DocumentSnapshot("ta", 1), TextPosition(0, 2), CompletionTrigger.Invoked)!!
		val diagnostics = service().diagnostics(DocumentSnapshot("swipe_in_direction direction=sideways", 1))

		assertTrue(list.items.any { it.label == "tap_element" })
		assertEquals(TextPosition(0, 0), list.items.first().textEdit?.range?.start)
		assertEquals(DiagnosticSeverity.ERROR, diagnostics.single().severity)
		assertEquals(TextPosition(0, 29), diagnostics.single().range.start)
	}

	@Test
	fun `nothing to offer yields no list`() = runBlocking<Unit> {
		assertNull(service().complete(DocumentSnapshot("zzz qqq", 1), TextPosition(0, 7), CompletionTrigger.Invoked))
	}

	@Test
	fun `diagnostics are clamped to the line`() {
		val diagnostic = ReplDiagnostic(5, 99, ReplSeverity.Warning, "m").toEditorDiagnostic(10)

		assertEquals(TextPosition(0, 10), diagnostic.range.end)
		assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
	}

	@Test
	fun `cancellation reaches the editor instead of falling back`() {
		assertFailsWith<CancellationException> {
			runBlocking { service(ScriptedAssist(failure = CancellationException("stale"))).diagnostics(DocumentSnapshot("x", 1)) }
		}
	}

	@Test
	fun `highlighting knows tools, arguments, and values from the catalog`() {
		val rules = amooCommandRules(catalog)

		assertEquals("amoo-console", rules.name)
	}
}
