package dev.amoo.studio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** One argument of a catalog tool, mirroring Amoo's `tools.catalog`. */
@Immutable
@Serializable
data class ToolArgumentSchema(
	val name: String,
	val type: String = "string",
	val description: String = "",
	val required: Boolean = false,
	/** Known values in canonical spelling; only an exhaustive set rejects other input. */
	val values: List<String>? = null,
	val valuesAreExhaustive: Boolean = true,
)

/** A device tool as Amoo describes it. [planTool] is the name a compiled test plan records it under. */
@Immutable
@Serializable
data class ToolSchema(
	val name: String,
	val title: String? = null,
	val description: String = "",
	val category: String = "other",
	val readOnly: Boolean = false,
	val destructive: Boolean = false,
	val replSupported: Boolean = true,
	val planTool: String? = null,
	val arguments: List<ToolArgumentSchema> = emptyList(),
) {
	val label: String get() = title ?: name
}

/** A console command that is not a tool, such as `devices inspect <device>`; angle brackets mark slots. */
@Immutable
@Serializable
data class ReplBuiltin(val command: String, val description: String = "") {
	val words: List<String> get() = command.split(' ').filter(String::isNotBlank)
}

/**
 * The console and plan-editor vocabulary. Loaded from Amoo's `tools.catalog`; [Legacy] stands in for
 * Amoo builds that predate it, with the fixed command set those builds understand.
 */
@Immutable
@Serializable
data class ToolCatalog(val tools: List<ToolSchema>, val builtins: List<ReplBuiltin> = DEFAULT_BUILTINS) {
	fun tool(name: String): ToolSchema? = tools.firstOrNull { it.name == name }

	/** Tools a test plan can contain, as the plan editor offers them. */
	val planTools: List<ToolSchema> get() = tools.filter { it.planTool != null }

	companion object {
		val DEFAULT_BUILTINS = listOf(
			ReplBuiltin("help", "List console commands"),
			ReplBuiltin("devices list", "Discover simulators, emulators, and devices"),
			ReplBuiltin("devices inspect <device>", "Show one device's details"),
			ReplBuiltin("tests validate", "Validate the active test"),
			ReplBuiltin("tests run", "Run the active test on the selected device"),
			ReplBuiltin("sessions list", "List recent Studio runs"),
			ReplBuiltin("reports list", "List Studio reports"),
			ReplBuiltin("providers inspect <provider>", "Show the selected provider profile"),
		)

		private val SELECTOR = listOf(
			ToolArgumentSchema("id", description = "Accessibility identifier"),
			ToolArgumentSchema("label", description = "Accessibility label"),
			ToolArgumentSchema("contains_text", description = "Partial label or text"),
		)

		/** The fixed vocabulary of Amoo builds without `tools.catalog`. */
		val Legacy = ToolCatalog(
			listOf(
				legacy("tap_element", "Tap a control by accessibility identity", SELECTOR),
				legacy("set_text", "Focus, clear, and fill a text field", SELECTOR + ToolArgumentSchema("value", description = "Value", required = true)),
				legacy("type_text", "Type into the focused field", listOf(ToolArgumentSchema("text", description = "Text", required = true))),
				legacy(
					"swipe_in_direction",
					"Swipe from the screen center",
					listOf(
						ToolArgumentSchema("direction", description = "Direction", required = true, values = listOf("up", "down", "left", "right")),
						ToolArgumentSchema("distance", description = "Distance in points"),
					),
				),
				legacy("wait_for_element", "Wait until an element appears", SELECTOR.take(2) + ToolArgumentSchema("timeout_ms", description = "Timeout in milliseconds")),
				legacy("assert_visible", "Require an element to be visible", SELECTOR, readOnly = true),
				legacy("assert_not_visible", "Require an element to be absent", SELECTOR, readOnly = true),
				legacy("assert_text", "Require matching text", listOf(ToolArgumentSchema("expected", description = "Expected text", required = true)) + SELECTOR.take(2), readOnly = true),
				legacy("take_screenshot", "Capture a report artifact", emptyList(), readOnly = true),
				legacy("press_back", "Navigate back", emptyList()),
			),
		)

		private fun legacy(name: String, description: String, arguments: List<ToolArgumentSchema>, readOnly: Boolean = false) =
			ToolSchema(name = name, description = description, category = "legacy", readOnly = readOnly, planTool = name, arguments = arguments)

		/** Selector tools in the legacy set need at least one of these, which their schema cannot express. */
		internal val LEGACY_SELECTOR_TOOLS = setOf("tap_element", "wait_for_element", "assert_visible", "assert_not_visible")
	}
}
