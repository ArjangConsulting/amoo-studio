package dev.amoo.studio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors Amoo's StudioReplGrammarTests so the local fallback and the backend agree. */
class ReplGrammarTest {
	private val catalog = ToolCatalog(
		listOf(
			ToolSchema("tap_element", planTool = "tap_element", arguments = listOf(ToolArgumentSchema("contains_text"), ToolArgumentSchema("id"), ToolArgumentSchema("label"))),
			ToolSchema(
				"swipe_in_direction",
				planTool = "swipe_in_direction",
				arguments = listOf(ToolArgumentSchema("direction", required = true, values = listOf("up", "down", "left", "right")), ToolArgumentSchema("distance")),
			),
			ToolSchema("press_key", arguments = listOf(ToolArgumentSchema("key", required = true, values = listOf("return", "tab"), valuesAreExhaustive = false))),
			ToolSchema("device_uninstall_app", destructive = true, arguments = listOf(ToolArgumentSchema("app_id", required = true))),
			ToolSchema("start_session", replSupported = false),
		),
	)
	private val grammar = ReplGrammar(catalog)

	@Test
	fun `tokens report character positions after an emoji`() {
		val tokens = ReplGrammar.tokenize("tap_element label=\"👋 Hi\" id=x")

		assertEquals(listOf("tap_element", "👋 Hi", "x"), tokens.map { it.value })
		assertEquals(12, tokens[1].start)
		assertEquals(18, tokens[1].valueStart)
		assertEquals(25, tokens[1].end)
		assertEquals("id", tokens[2].name)
	}

	@Test
	fun `named and positional arguments parse`() {
		assertEquals(ReplCommand.Tool("swipe_in_direction", mapOf("direction" to "up", "distance" to "200")), grammar.parse("swipe_in_direction direction=up distance=200").command)
		assertEquals(ReplCommand.Tool("swipe_in_direction", mapOf("direction" to "left")), grammar.parse("swipe_in_direction left").command)
		assertEquals(ReplCommand.Builtin("devices inspect sim-1"), grammar.parse("devices inspect sim-1").command)
	}

	@Test
	fun `diagnostics match the backend`() {
		assertTrue(grammar.parse("tap_elemnt id=x").diagnostics.single().message.contains("Did you mean 'tap_element'"))
		assertEquals(ReplDiagnostic(29, 37, ReplSeverity.Error, "direction must be one of: up, down, left, right."), grammar.parse("swipe_in_direction direction=sideways").diagnostics.single())
		assertTrue(grammar.parse("swipe_in_direction direction=UP").isValid)
		assertTrue(grammar.parse("press_key key=a").isValid)
		assertEquals(listOf("Close the quote that starts this value."), grammar.parse("swipe_in_direction direction=\"up").diagnostics.map { it.message })
		assertEquals(listOf(ReplSeverity.Warning), grammar.parse("tap_element id=a id=b").diagnostics.map { it.severity })
		assertTrue(grammar.parse("device_uninstall_app app_id=x").diagnostics.single().message.contains("approval"))
		assertTrue(grammar.parse("start_session").diagnostics.single().message.contains("managed by Studio"))
		assertTrue(grammar.parse("tap_element label=\"delete account\"").isValid)
	}

	@Test
	fun `completion offers commands, unused arguments, and values`() {
		val commands = grammar.complete("ta", 2)
		assertEquals(0 to 2, commands.replaceStart to commands.replaceEnd)
		assertTrue("tap_element" in commands.items.map { it.label })
		assertFalse("device_uninstall_app" in commands.items.map { it.label })
		assertEquals("devices inspect ", commands.items.first { it.label == "devices inspect <device>" }.insertText)

		assertEquals(listOf("direction"), grammar.complete("swipe_in_direction distance=10 ", 31).items.map { it.label })
		val values = grammar.complete("swipe_in_direction direction=u", 30)
		assertEquals(29, values.replaceStart)
		assertEquals(listOf("up", "down", "left", "right"), values.items.map { it.label })

		val device = StudioDevice("sim-1", "iPhone", TestPlatform.Ios, "26", DeviceStatus.Running)
		assertEquals(listOf("sim-1"), grammar.complete("devices inspect ", 16, listOf(device)).items.map { it.label })
		assertEquals(listOf("inspect", "list"), grammar.complete("devices ", 8).items.map { it.label })
	}

	@Test
	fun `values that would split are quoted`() {
		assertEquals("\"Sign in\"", ReplGrammar.quoted("Sign in"))
		assertEquals("'Say \"hi\"'", ReplGrammar.quoted("Say \"hi\""))
		assertEquals("login", ReplGrammar.quoted("login"))
	}

	@Test
	fun `wire shapes decode from Amoo's JSON`() {
		val json = Json { ignoreUnknownKeys = true }
		val completion = json.decodeFromString<ReplCompletion>("""{"replaceStart":0,"replaceEnd":2,"items":[{"label":"tap","insertText":"tap","kind":"tool","sortText":"1tap"}],"isIncomplete":false}""")
		val diagnostic = json.decodeFromString<ReplDiagnostic>("""{"start":1,"end":3,"severity":"warning","message":"m"}""")

		assertEquals(ReplCompletionKind.Tool, completion.items.single().kind)
		assertEquals(ReplSeverity.Warning, diagnostic.severity)
	}

	@Test
	fun `the legacy catalog keeps the selector requirement older Amoo builds enforced`() {
		val legacy = ReplGrammar(ToolCatalog.Legacy)

		assertEquals("Provide an accessibility ID, label, or text selector.", legacy.parse("tap_element").diagnostics.single().message)
		assertTrue(legacy.parse("tap_element label=\"Sign in\"").isValid)
	}
}
