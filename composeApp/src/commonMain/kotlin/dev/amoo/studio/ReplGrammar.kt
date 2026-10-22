package dev.amoo.studio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReplSeverity { @SerialName("error") Error, @SerialName("warning") Warning }

/** A problem in a console line over the character range [start] until [end]; also `repl.validate`'s wire shape. */
@Immutable
@Serializable
data class ReplDiagnostic(val start: Int, val end: Int, val severity: ReplSeverity, val message: String)

@Serializable
enum class ReplCompletionKind {
	@SerialName("tool") Tool,
	@SerialName("command") Command,
	@SerialName("argument") Argument,
	@SerialName("value") Value,
	@SerialName("element") Element,
	@SerialName("device") Device,
}

@Immutable
@Serializable
data class ReplCompletionItem(
	val label: String,
	val insertText: String,
	val detail: String? = null,
	val documentation: String? = null,
	val kind: ReplCompletionKind,
	val sortText: String = label,
)

/** Items replace the characters [replaceStart] until [replaceEnd] of the line; also `repl.complete`'s wire shape. */
@Immutable
@Serializable
data class ReplCompletion(val replaceStart: Int, val replaceEnd: Int, val items: List<ReplCompletionItem>, val isIncomplete: Boolean = false) {
	companion object {
		fun empty(at: Int) = ReplCompletion(at, at, emptyList())
	}
}

sealed interface ReplCommand {
	data object Empty : ReplCommand
	data class Builtin(val text: String) : ReplCommand
	data class Tool(val name: String, val arguments: Map<String, String>) : ReplCommand
}

data class ReplParse(val command: ReplCommand, val diagnostics: List<ReplDiagnostic>) {
	val isValid: Boolean get() = diagnostics.none { it.severity == ReplSeverity.Error }
}

/** One word of a console line. Positions are character (UTF-16) offsets and [end] is exclusive. */
internal data class ReplToken(val raw: String, val value: String, val start: Int, val end: Int, val isTerminated: Boolean, val name: String?, val valueStart: Int)

/**
 * The console language, ported from Amoo's `StudioReplGrammar` so Studio can complete and validate on
 * its own against Amoo builds without `repl.complete`. Whitespace separates words outside quotes, `"`
 * and `'` quote anywhere in a word, and the first `=` after a name starts its value.
 */
class ReplGrammar(private val catalog: ToolCatalog) {
	fun parse(line: String): ReplParse {
		val tokens = tokenize(line)
		val first = tokens.firstOrNull() ?: return ReplParse(ReplCommand.Empty, emptyList())
		matchingBuiltin(tokens)?.let { return ReplParse(ReplCommand.Builtin(it), emptyList()) }
		partialBuiltin(tokens)?.let { return ReplParse(ReplCommand.Empty, listOf(it)) }
		val tool = catalog.tool(first.value) ?: run {
			val hint = closest(first.value, catalog.tools.map { it.name } + catalog.builtins.map { it.command })?.let { " Did you mean '$it'?" } ?: " Choose a suggestion or run help."
			return ReplParse(ReplCommand.Empty, listOf(error(first, "Unknown command '${first.value}'.$hint")))
		}
		val diagnostics = mutableListOf<ReplDiagnostic>()
		when {
			!tool.replSupported -> diagnostics += error(first, "${tool.name} is managed by Studio and cannot run in the console.")
			tool.destructive -> diagnostics += error(first, "${tool.name} can destroy device or app state. Use Devices so Studio can ask for approval.")
		}
		val arguments = linkedMapOf<String, String>()
		tokens.drop(1).forEach { diagnostics += apply(it, arguments, tool) }
		val missing = tool.arguments.filter { it.required && it.name !in arguments }.map { it.name }
		if (missing.isNotEmpty()) diagnostics += error(first, "Missing required ${if (missing.size == 1) "argument" else "arguments"}: ${missing.joinToString()}.")
		if (tool.category == "legacy" && tool.name in ToolCatalog.LEGACY_SELECTOR_TOOLS && listOf("id", "label", "contains_text").none { it in arguments }) {
			diagnostics += error(first, "Provide an accessibility ID, label, or text selector.")
		}
		return ReplParse(ReplCommand.Tool(tool.name, arguments), diagnostics)
	}

	private fun apply(token: ReplToken, arguments: MutableMap<String, String>, tool: ToolSchema): List<ReplDiagnostic> {
		val nextPositional = tool.arguments.firstOrNull { it.required && it.name !in arguments }
		if (!token.isTerminated) {
			(token.name ?: nextPositional?.name)?.let { arguments[it] = token.value }
			return listOf(error(token, "Close the quote that starts this value."))
		}
		val name = token.name ?: run {
			nextPositional ?: return listOf(error(token, "Unexpected value '${token.value}'. Write it as name=value."))
			arguments[nextPositional.name] = token.value
			return listOfNotNull(valueProblem(token.value, nextPositional)?.let { error(token, it) })
		}
		val argument = tool.arguments.firstOrNull { it.name == name } ?: run {
			val hint = closest(name, tool.arguments.map { it.name })?.let { " Did you mean '$it'?" }.orEmpty()
			return listOf(ReplDiagnostic(token.start, token.valueStart - 1, ReplSeverity.Error, "${tool.name} has no argument '$name'.$hint"))
		}
		val diagnostics = mutableListOf<ReplDiagnostic>()
		if (name in arguments) diagnostics += ReplDiagnostic(token.start, token.end, ReplSeverity.Warning, "$name is given more than once; the last value is used.")
		if (token.value.isEmpty()) diagnostics += error(token, "Give a value for $name.")
		else valueProblem(token.value, argument)?.let { diagnostics += ReplDiagnostic(token.valueStart, token.end, ReplSeverity.Error, it) }
		arguments[name] = token.value
		return diagnostics
	}

	private fun valueProblem(value: String, argument: ToolArgumentSchema): String? {
		val values = argument.values?.takeIf { argument.valuesAreExhaustive && it.isNotEmpty() } ?: return null
		return if (values.any { it.equals(value, ignoreCase = true) }) null else "${argument.name} must be one of: ${values.joinToString()}."
	}

	/** Completes the word under [cursor]; [devices] fill `<device>` slots. */
	fun complete(line: String, cursor: Int, devices: List<StudioDevice> = emptyList()): ReplCompletion {
		val tokens = tokenize(line)
		val index = tokens.indexOfFirst { cursor in it.start..it.end }
		val tokenIndex = if (index >= 0) index else tokens.count { it.end <= cursor }
		val token = tokens.getOrNull(index)
		val replaceStart = token?.start ?: cursor
		val replaceEnd = token?.end ?: cursor
		if (tokenIndex == 0) return ReplCompletion(replaceStart, replaceEnd, commandItems())
		val tool = catalog.tool(tokens[0].value)
		if (tool != null) {
			if (token?.name != null && cursor >= token.valueStart) {
				val argument = tool.arguments.firstOrNull { it.name == token.name }
				val items = argument?.values.orEmpty().map { ReplCompletionItem(it, quoted(it), argument?.name, kind = ReplCompletionKind.Value) }
				return ReplCompletion(token.valueStart, token.end, items)
			}
			val used = tokens.filterIndexed { i, _ -> i != index }.mapNotNull { it.name }.toSet()
			val items = tool.arguments.filter { it.name !in used }.map {
				ReplCompletionItem(it.name, "${it.name}=", "${it.type} · ${if (it.required) "required" else "optional"}", it.description, ReplCompletionKind.Argument, "${if (it.required) 0 else 1}${it.name}")
			}
			return ReplCompletion(replaceStart, replaceEnd, items)
		}
		val typed = tokens.take(tokenIndex).map { it.value.lowercase() }
		val family = catalog.builtins.map { it.words }.filter { words ->
			words.size > tokenIndex && words.zip(typed).all { (expected, actual) -> isSlot(expected) || expected.lowercase() == actual }
		}
		val literals = family.map { it[tokenIndex] }.filterNot(::isSlot).distinct().sorted().map { ReplCompletionItem(it, it, kind = ReplCompletionKind.Command) }
		val deviceItems = if (family.any { it[tokenIndex] == "<device>" }) devices.map {
			ReplCompletionItem(it.id, it.id, "${it.name} · ${it.platform.label} ${it.osVersion}", kind = ReplCompletionKind.Device)
		} else emptyList()
		return ReplCompletion(replaceStart, replaceEnd, literals + deviceItems)
	}

	private fun commandItems(): List<ReplCompletionItem> {
		val builtins = catalog.builtins.map { builtin ->
			val fixed = builtin.words.takeWhile { !isSlot(it) }.joinToString(" ")
			ReplCompletionItem(builtin.command, if (fixed.length < builtin.command.length) "$fixed " else fixed, "Studio command", builtin.description, ReplCompletionKind.Command, "0${builtin.command}")
		}
		val tools = catalog.tools.filter { it.replSupported && !it.destructive }.map {
			ReplCompletionItem(it.name, it.name, it.title ?: it.category, it.description, ReplCompletionKind.Tool, "1${it.name}")
		}
		return builtins + tools
	}

	private fun matchingBuiltin(tokens: List<ReplToken>): String? {
		val words = tokens.map { it.value }
		val match = catalog.builtins.any { builtin ->
			builtin.words.size == words.size && builtin.words.zip(words).all { (expected, actual) -> isSlot(expected) || expected.equals(actual, ignoreCase = true) }
		}
		return if (match) words.joinToString(" ") else null
	}

	private fun partialBuiltin(tokens: List<ReplToken>): ReplDiagnostic? {
		val first = tokens[0].value.lowercase()
		val family = catalog.builtins.filter { it.words.size > 1 && it.words.first().lowercase() == first }
		if (family.isEmpty() || catalog.tool(tokens[0].value) != null) return null
		return ReplDiagnostic(tokens[0].start, tokens.last().end, ReplSeverity.Error, "Incomplete or unknown $first command. Try: ${family.joinToString { it.command }}.")
	}

	private fun error(token: ReplToken, message: String) = ReplDiagnostic(token.start, token.end, ReplSeverity.Error, message)

	companion object {
		internal fun tokenize(line: String): List<ReplToken> {
			val tokens = mutableListOf<ReplToken>()
			var start = -1
			val raw = StringBuilder()
			val value = StringBuilder()
			var quote: Char? = null
			var name: String? = null
			var valueStart = 0
			fun finish(end: Int) {
				tokens += ReplToken(raw.toString(), value.toString(), start, end, quote == null, name, if (name == null) start else valueStart)
				start = -1; raw.clear(); value.clear(); quote = null; name = null
			}
			line.forEachIndexed { offset, character ->
				if (quote == null && character.isWhitespace()) {
					if (start >= 0) finish(offset)
					return@forEachIndexed
				}
				if (start < 0) start = offset
				raw.append(character)
				when {
					character == '"' || character == '\'' -> when (quote) {
						character -> quote = null
						null -> quote = character
						else -> value.append(character)
					}
					character == '=' && quote == null && name == null && value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '_' } -> {
						name = value.toString(); value.clear(); valueStart = offset + 1
					}
					else -> value.append(character)
				}
			}
			if (start >= 0) finish(line.length)
			return tokens
		}

		internal fun isSlot(word: String) = word.startsWith('<') && word.endsWith('>')

		/** Quotes a value that would otherwise split or change meaning, preferring double quotes. */
		fun quoted(value: String): String {
			if (value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' || it == '\'' }) return value
			return if ('"' in value) "'$value'" else "\"$value\""
		}

		internal fun closest(word: String, candidates: List<String>): String? {
			val lowered = word.lowercase()
			return candidates.map { it to editDistance(lowered, it.lowercase()) }
				.filter { (_, distance) -> distance <= 2 && distance < maxOf(lowered.length, 1) }
				.minByOrNull { it.second }?.first
		}

		private fun editDistance(left: String, right: String): Int {
			if (left.isEmpty()) return right.length
			if (right.isEmpty()) return left.length
			var previous = IntArray(right.length + 1) { it }
			left.forEachIndexed { row, leftCharacter ->
				val current = IntArray(right.length + 1)
				current[0] = row + 1
				right.forEachIndexed { column, rightCharacter ->
					current[column + 1] = minOf(previous[column + 1] + 1, current[column] + 1, previous[column] + if (leftCharacter == rightCharacter) 0 else 1)
				}
				previous = current
			}
			return previous[right.length]
		}
	}
}
