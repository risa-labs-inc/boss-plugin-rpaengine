package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Argument parsing and the refusal list for rpa_observe / rpa_step. */
class TabActionArgsTest {

    private fun code(block: () -> Unit): String = assertFailsWith<TabToolException> { block() }.code

    @Test
    fun `observe defaults max_elements to 80`() {
        assertEquals(ObserveArgs("t1", 80), parseObserveArgs("""{"tab_id":"t1"}"""))
    }

    @Test
    fun `observe accepts the bounds and rejects outside them`() {
        assertEquals(1, parseObserveArgs("""{"tab_id":"t","max_elements":1}""").maxElements)
        assertEquals(200, parseObserveArgs("""{"tab_id":"t","max_elements":200}""").maxElements)
        listOf("0", "201", "1.5", "\"abc\"", "{}").forEach {
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseObserveArgs("""{"tab_id":"t","max_elements":$it}""") }, it)
        }
    }

    @Test
    fun `tab_id is required and must be a non-empty string`() {
        listOf("{}", """{"tab_id":""}""", """{"tab_id":"  "}""", """{"tab_id":5}""", """{"tab_id":null}""", "[]", "not json")
            .forEach { assertEquals(TabErrorCodes.INVALID_INPUT, code { parseObserveArgs(it) }, it) }
    }

    @Test
    fun `every refused verb is UNSUPPORTED_ACTION, not INVALID_INPUT`() {
        listOf("run_script", "screenshot", "switch_frame", "assert").forEach { t ->
            assertEquals(
                TabErrorCodes.UNSUPPORTED_ACTION,
                code { parseStepArgs("""{"tab_id":"t","action":{"type":"$t","selector":{"type":"css","value":"a"},"value":"x"}}""") },
                t,
            )
        }
    }

    @Test
    fun `an unknown verb is UNSUPPORTED_ACTION`() {
        assertEquals(TabErrorCodes.UNSUPPORTED_ACTION, code { parseStepArgs("""{"tab_id":"t","action":{"type":"hover"}}""") })
    }

    @Test
    fun `refused and allowed sets do not overlap`() {
        assertEquals(emptySet(), STEP_ALLOWED_TYPES intersect STEP_REFUSED_TYPES)
    }

    @Test
    fun `click parses selector and default highlight`() {
        val a = parseStepArgs("""{"tab_id":"t","action":{"type":"click","selector":{"type":"xpath","value":"//a[@x='1']"}}}""")
        assertEquals("t", a.tabId)
        assertEquals(600, a.highlightMs)
        assertEquals(ActionTypes.CLICK, a.action.type)
        assertEquals(SelectorInfo(type = "xpath", value = "//a[@x='1']"), a.action.selector)
        assertNull(a.action.value)
    }

    @Test
    fun `element verbs require a selector`() {
        listOf("click", "input", "select", "submit").forEach { t ->
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs("""{"tab_id":"t","action":{"type":"$t","value":"x"}}""") }, t)
        }
    }

    @Test
    fun `keypress without a selector targets the focused element`() {
        val a = parseStepArgs("""{"tab_id":"t","action":{"type":"keypress","value":"Enter"}}""")
        assertEquals(SelectorTypes.NONE, a.action.selector.type)
    }

    @Test
    fun `selector type and value are validated`() {
        listOf(
            """{"type":"none","value":"x"}""",
            """{"type":"css","value":""}""",
            """{"type":"css"}""",
            """{"value":"x"}""",
            "\"#x\"",
        ).forEach { s ->
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs("""{"tab_id":"t","action":{"type":"click","selector":$s}}""") }, s)
        }
    }

    @Test
    fun `highlight_ms is bounded to 0-2000`() {
        assertEquals(0, parseStepArgs("""{"tab_id":"t","action":{"type":"wait"},"highlight_ms":0}""").highlightMs)
        assertEquals(2000, parseStepArgs("""{"tab_id":"t","action":{"type":"wait"},"highlight_ms":2000}""").highlightMs)
        listOf("-1", "2001").forEach {
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs("""{"tab_id":"t","action":{"type":"wait"},"highlight_ms":$it}""") })
        }
    }

    @Test
    fun `wait is capped at 10 seconds`() {
        assertEquals("10000", parseStepArgs("""{"tab_id":"t","action":{"type":"wait","value":"10000"}}""").action.value)
        listOf("10001", "3s", "-5").forEach {
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs("""{"tab_id":"t","action":{"type":"wait","value":"$it"}}""") }, it)
        }
    }

    @Test
    fun `navigate requires a url`() {
        assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs("""{"tab_id":"t","action":{"type":"navigate"}}""") })
    }

    @Test
    fun `action must be an object with a type`() {
        listOf("""{"tab_id":"t"}""", """{"tab_id":"t","action":"click"}""", """{"tab_id":"t","action":{}}""").forEach {
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(it) }, it)
        }
    }

    @Test
    fun `error json has the contract shape`() {
        assertEquals(
            """{"error":{"code":"TAB_NOT_FOUND","message":"No tab \"x\""}}""",
            toolErrorJson(TabErrorCodes.TAB_NOT_FOUND, "No tab \"x\""),
        )
    }
}
