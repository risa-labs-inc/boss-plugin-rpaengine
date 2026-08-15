package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [keyPressScript] is what made the Google search actually submit. The reason it needs a test
 * is that the broken version *looked* correct and reported success: dispatching a synthetic
 * Enter runs every page handler, so nothing errors, and the form just never submits.
 */
class KeyPressScriptTest {

    @Test
    fun `Enter submits the enclosing form`() {
        val js = keyPressScript("Enter")
        assertTrue(js.contains("requestSubmit"), "Enter must supply the default action itself: $js")
        assertTrue(js.contains("el.form"), "submission is scoped to the field's own form: $js")
    }

    @Test
    fun `Enter does not submit when the page handled the key`() {
        assertTrue(
            keyPressScript("Enter").contains("!ev.defaultPrevented"),
            "a page that calls preventDefault implements Enter itself",
        )
    }

    @Test
    fun `an ordinary key never submits`() {
        val js = keyPressScript("Tab")
        assertFalse(js.contains("requestSubmit()"), "only Enter has a submit default action: $js")
    }

    @Test
    fun `all three key events are dispatched`() {
        val js = keyPressScript("Escape")
        listOf("keydown", "keypress", "keyup").forEach {
            assertTrue(js.contains("'$it'"), "missing $it in: $js")
        }
    }

    @Test
    fun `the key name is escaped, not interpolated raw`() {
        // A key value carrying a quote would otherwise close the string and inject script.
        val js = keyPressScript("a'; window.x = 1; '")
        assertTrue(js.contains("\\'"), "quote must be escaped: $js")
        assertFalse(js.contains("'; window.x = 1; '"), "raw injection survived: $js")
    }
}
