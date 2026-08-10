package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selectors and typed values are interpolated into injected JavaScript, so they have to arrive as
 * literals rather than as raw text.
 *
 * This is not hypothetical tidying. A generated automation plan's first selector is typically
 * `input[name='q']`, and pasting it in raw produced `document.querySelector('input[name='q']')` -
 * a syntax error that failed the action with no useful message. XPath is worse:
 * `//div[@role='tab']` is full of quotes. Every real plan hit this.
 */
class JsStringEscapingTest {
    @Test
    fun `a css selector containing quotes becomes a valid literal`() {
        // The exact shape a generated plan produces for a search box.
        assertEquals("""'input[name=\'q\']'""", "input[name='q']".asJsString())
    }

    @Test
    fun `an xpath selector containing quotes becomes a valid literal`() {
        val xpath = "//div[@role='tab' and contains(.,'Images')]"

        val literal = xpath.asJsString()

        // Every inner quote escaped, and the whole thing wrapped exactly once.
        assertTrue(literal.startsWith("'") && literal.endsWith("'"), "not wrapped: $literal")
        assertEquals(0, Regex("""(?<!\\)'""").findAll(literal.drop(1).dropLast(1)).count())
    }

    @Test
    fun `backslashes are escaped before the quotes they might have escaped`() {
        // Order matters: escaping quotes first would leave this backslash doubling the escape
        // this function itself added, and the literal would terminate early.
        assertEquals("""'a\\b'""", """a\b""".asJsString())
        assertEquals("""'\\\''""", """\'""".asJsString())
    }

    @Test
    fun `newlines cannot break out of the literal`() {
        // A single-quoted JS literal cannot span lines, so a raw newline is a syntax error.
        val literal = "line1\nline2".asJsString()

        assertTrue(!literal.contains('\n'), "raw newline survived: $literal")
        assertEquals("""'line1\nline2'""", literal)
    }

    @Test
    fun `an empty value is still a valid literal`() {
        assertEquals("''", "".asJsString())
    }
}
