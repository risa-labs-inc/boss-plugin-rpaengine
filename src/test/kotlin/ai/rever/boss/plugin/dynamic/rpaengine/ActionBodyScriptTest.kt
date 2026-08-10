package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bodies for `input`, `select` and `assert`.
 *
 * Each of these once reported success from the element merely existing: `el.value = x` on a
 * `contenteditable` div sets an expando property, assigning an unmatched value to a `<select>`
 * leaves it unchanged while `change` still fires, and an `assert` with an empty body passes on
 * existence while dropping the text it was asked to check.
 */
class ActionBodyScriptTest {

    @Test
    fun `typing verifies the value took`() {
        val js = typeValueScript("cat images")
        assertTrue(js.contains("el.value !=="), "does not compare the value back: $js")
        assertTrue(js.contains("return false"), "cannot report a failure: $js")
    }

    @Test
    fun `typing still fires input and change`() {
        val js = typeValueScript("x")
        assertTrue(js.contains("'input'") && js.contains("'change'"), "missing events: $js")
    }

    @Test
    fun `select refuses a value no option carries`() {
        val js = selectOptionScript("Blue")
        assertTrue(js.contains("if (!opt) { return false; }"), "assigns unconditionally: $js")
    }

    @Test
    fun `select matches an option by label as well as value`() {
        // A plan names what the user sees, so the visible text has to count.
        val js = selectOptionScript("Blue")
        assertTrue(js.contains("o.value === want"), "no value match: $js")
        assertTrue(js.contains("o.text"), "no label match: $js")
    }

    @Test
    fun `assert checks the expected text`() {
        val js = assertTextScript("Results")
        assertTrue(js.contains("indexOf(want) === -1"), "does not compare the text: $js")
        assertTrue(js.contains("return false"), "cannot fail: $js")
    }

    @Test
    fun `assert looks at a field value as well as text`() {
        val js = assertTextScript("Results")
        assertTrue(js.contains("el.value"), "an input's value is text the user sees too: $js")
    }

    @Test
    fun `every body escapes its value`() {
        listOf(typeValueScript("it's"), selectOptionScript("it's"), assertTextScript("it's"))
            .forEach { assertTrue(it.contains("\\'"), "unescaped value: $it") }
    }
}
