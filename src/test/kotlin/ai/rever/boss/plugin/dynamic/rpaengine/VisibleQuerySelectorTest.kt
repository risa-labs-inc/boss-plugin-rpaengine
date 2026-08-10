package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [visibleQuerySelector] backs the tag-stripped fallback only.
 *
 * Dropping the tag widens the match, and `[name='q']` also matches `<input type=hidden name=q>`
 * or an off-screen duplicate. Setting a value on one of those makes the probe true and the action
 * reports ok while nothing visible happened - reintroducing, through the fallback, the failure
 * this engine's changes exist to remove.
 */
class VisibleQuerySelectorTest {

    @Test
    fun `requires the element to be rendered`() {
        val js = visibleQuerySelector("[name='q']")
        assertTrue(js.contains("offsetParent !== null"), "no visibility constraint: $js")
    }

    @Test
    fun `considers every match, not just the first`() {
        // querySelector would stop at the hidden duplicate and find nothing usable.
        val js = visibleQuerySelector("[name='q']")
        assertTrue(js.contains("querySelectorAll"), "only checks the first match: $js")
        assertTrue(js.contains(".find("), "does not pick the first visible one: $js")
    }

    @Test
    fun `escapes the selector`() {
        assertTrue(visibleQuerySelector("[name='q']").contains("\\'"))
    }
}
