package dev.amoo.studio

import com.arjangconsulting.codeeditor.CompletionItemKind
import com.arjangconsulting.codeeditor.CompletionTrigger
import com.arjangconsulting.codeeditor.DiagnosticSeverity
import com.arjangconsulting.codeeditor.DocumentSnapshot
import com.arjangconsulting.codeeditor.TextPosition
import io.github.maniramezan.testcommons.ScriptedResponder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleLanguageServiceTest {
	private val catalog = ToolCatalog.Legacy

	private class ScriptedAssist(completion: ReplCompletion? = null, diagnostics: List<ReplDiagnostic>? = null, failure: Throwable? = null) : ReplAssist {
		val completions = ScriptedResponder<Pair<String, Int>, ReplCompletion?>(listOf(if (failure == null) Result.success(completion) else Result.failure(failure)))
		val validations = ScriptedResponder<String, List<ReplDiagnostic>?>(listOf(if (failure == null) Result.success(diagnostics) else Result.failure(failure)))
		override suspend fun complete(line: String, cursor: Int): ReplCompletion? = completions.respond(line to cursor)
		override suspend fun validate(line: String): List<ReplDiagnostic>? = validations.respond(line)
	}

	private fun service(assist: ReplAssist = ReplAssist.None) = ConsoleLanguageService({ catalog }, { emptyList() }, assist)

	@Test
	fun `Amoo's completion is used with its replacement range`() = runBlocking<Unit> {
		val amoo = ReplCompletion(15, 15, listOf(ReplCompletionItem("login", "login", "button · Sign in", kind = ReplCompletionKind.Element)))

		val assist = ScriptedAssist(completion = amoo)
		val list = service(assist).complete(DocumentSnapshot("tap_element id=", 1), TextPosition(0, 15), CompletionTrigger.character('='))!!

		val item = list.items.single()
		assertEquals("login", item.textEdit?.newText)
		assertEquals(TextPosition(0, 15), item.textEdit?.range?.start)
		assertEquals(CompletionItemKind.REFERENCE, item.kind)
		assertEquals(listOf("tap_element id=" to 15), assist.completions.requests)
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
