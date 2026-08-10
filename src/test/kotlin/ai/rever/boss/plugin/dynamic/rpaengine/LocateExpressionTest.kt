package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `null` vs `false` split that AGENTS.md names as the thing a future change is most likely to
 * collapse. [locateExpression] is the pure half: null means "this selector cannot be resolved at
 * all", and everything else is a probe that may or may not match.
 */
class LocateExpressionTest {

    private fun selector(type: String, value: String?) = SelectorInfo(type = type, value = value)

    @Test
    fun `an unknown selector type is unresolvable`() {
        assertNull(locateExpression(selector("magic", "anything")))
    }

    @Test
    fun `a none selector is unresolvable`() {
        assertNull(locateExpression(selector(SelectorTypes.NONE, "ignored")))
    }

    @Test
    fun `a blank or missing value is unresolvable, whatever the type`() {
        assertNull(locateExpression(selector(SelectorTypes.CSS, null)))
        assertNull(locateExpression(selector(SelectorTypes.CSS, "   ")))
        assertNull(locateExpression(selector(SelectorTypes.ID, "")))
    }

    @Test
    fun `each supported type resolves to its own DOM call`() {
        assertTrue(locateExpression(selector(SelectorTypes.ID, "q"))!!.contains("getElementById"))
        assertTrue(locateExpression(selector(SelectorTypes.CSS, "[name='q']"))!!.contains("querySelector"))
        assertTrue(locateExpression(selector(SelectorTypes.XPATH, "//a"))!!.contains("document.evaluate"))
        assertTrue(locateExpression(selector(SelectorTypes.TEXT, "Images"))!!.contains("innerText"))
    }

    @Test
    fun `the value is escaped into the expression`() {
        val js = locateExpression(selector(SelectorTypes.CSS, "[name='q']"))!!
        assertTrue(js.contains("\\'"), "quotes must be escaped: $js")
    }
}
