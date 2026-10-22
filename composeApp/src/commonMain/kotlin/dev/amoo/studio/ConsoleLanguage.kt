package dev.amoo.studio

import androidx.compose.runtime.staticCompositionLocalOf
import com.arjangconsulting.codeeditor.CompletionItem
import com.arjangconsulting.codeeditor.CompletionItemKind
import com.arjangconsulting.codeeditor.CompletionList
import com.arjangconsulting.codeeditor.CompletionTrigger
import com.arjangconsulting.codeeditor.Diagnostic
import com.arjangconsulting.codeeditor.DiagnosticSeverity
import com.arjangconsulting.codeeditor.DocumentSnapshot
import com.arjangconsulting.codeeditor.LanguageService
import com.arjangconsulting.codeeditor.MarkupContent
import com.arjangconsulting.codeeditor.TextEdit
import com.arjangconsulting.codeeditor.TextPosition
import com.arjangconsulting.codeeditor.TextRange
import com.arjangconsulting.codeeditor.TokenKind
import com.arjangconsulting.codeeditor.TokenRules
import com.arjangconsulting.codeeditor.tokenRules

private val prettyPrinter = kotlinx.serialization.json.Json { prettyPrint = true }

/** Re-indents JSON text for display, or returns it unchanged when it is not valid JSON. */
fun prettyJson(text: String): String = runCatching {
	prettyPrinter.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), prettyPrinter.parseToJsonElement(text))
}.getOrDefault(text)

/** Highlighting for the console language: tools and command words, `name=` arguments, values, and literals. */
fun amooCommandRules(catalog: ToolCatalog): TokenRules = tokenRules("amoo-console") {
	val commands = (catalog.tools.map { it.name } + catalog.builtins.flatMap { builtin -> builtin.words.filterNot(ReplGrammar::isSlot) }).distinct()
	keywords(*commands.toTypedArray(), kind = TokenKind.FUNCTION)
	val values = catalog.tools.flatMap { tool -> tool.arguments.flatMap { it.values.orEmpty() } }.distinct()
	constants(*(listOf("true", "false") + values).distinct().toTypedArray())
	attributeAssignments()
	string("\"")
	string("'")
	numbers()
}

/**
 * Asks Amoo to complete or validate a console line. Implemented on the desktop over `repl.complete` and
 * `repl.validate`; a null result means "not available here", and the local grammar answers instead.
 */
interface ReplAssist {
	suspend fun complete(line: String, cursor: Int): ReplCompletion?
	suspend fun validate(line: String): List<ReplDiagnostic>?

	companion object {
		val None: ReplAssist = object : ReplAssist {
			override suspend fun complete(line: String, cursor: Int): ReplCompletion? = null
			override suspend fun validate(line: String): List<ReplDiagnostic>? = null
		}
	}
}

val LocalReplAssist = staticCompositionLocalOf { ReplAssist.None }

/**
 * The console's [LanguageService]: Amoo's answers when it has them, which include elements on the device's
 * screen, otherwise the local [ReplGrammar] over [catalog]. Results map onto the single console line.
 */
class ConsoleLanguageService(
	private val catalog: () -> ToolCatalog,
	private val devices: () -> List<StudioDevice>,
	private val assist: ReplAssist,
) : LanguageService {
	override val triggerCharacters: Set<Char> = setOf('=', ' ')

	override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList? {
		val line = doc.text
		val cursor = position.character.coerceIn(0, line.length)
		val completion = assist.complete(line, cursor) ?: ReplGrammar(catalog()).complete(line, cursor, devices())
		if (completion.items.isEmpty()) return null
		val range = TextRange(TextPosition(0, completion.replaceStart), TextPosition(0, completion.replaceEnd))
		return CompletionList(
			completion.items.map { item ->
				CompletionItem(
					label = item.label,
					kind = item.kind.editorKind,
					detail = item.detail,
					documentation = item.documentation?.let(::MarkupContent),
					textEdit = TextEdit(range, item.insertText),
					sortText = item.sortText,
				)
			},
			isIncomplete = completion.isIncomplete,
		)
	}

	override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> {
		val diagnostics = assist.validate(doc.text) ?: ReplGrammar(catalog()).parse(doc.text).diagnostics
		return diagnostics.map { it.toEditorDiagnostic(doc.text.length) }
	}
}

internal fun ReplDiagnostic.toEditorDiagnostic(lineLength: Int): Diagnostic {
	val start = start.coerceIn(0, lineLength)
	val end = end.coerceIn(start, lineLength)
	return Diagnostic(
		range = TextRange(TextPosition(0, start), TextPosition(0, end)),
		message = message,
		severity = if (severity == ReplSeverity.Error) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
		source = "amoo",
	)
}

internal val ReplCompletionKind.editorKind: CompletionItemKind
	get() = when (this) {
		ReplCompletionKind.Tool -> CompletionItemKind.FUNCTION
		ReplCompletionKind.Command -> CompletionItemKind.KEYWORD
		ReplCompletionKind.Argument -> CompletionItemKind.PROPERTY
		ReplCompletionKind.Value -> CompletionItemKind.ENUM_MEMBER
		ReplCompletionKind.Element -> CompletionItemKind.REFERENCE
		ReplCompletionKind.Device -> CompletionItemKind.MODULE
	}
