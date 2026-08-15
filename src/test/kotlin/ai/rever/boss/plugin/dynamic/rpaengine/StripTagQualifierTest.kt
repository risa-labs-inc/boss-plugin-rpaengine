package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [stripTagQualifier] is the fallback that made the cat-image plan run: the model emitted
 * `input[name='q']` for a box that is a `textarea`. The cases here are the ones where a
 * careless implementation changes what a selector *means* instead of widening it.
 */
class StripTagQualifierTest {

    @Test
    fun `drops a wrong tag guess in front of an attribute predicate`() {
        assertEquals("[name='q']", "input[name='q']".stripTagQualifier())
    }

    @Test
    fun `keeps a selector that has no attribute predicate`() {
        // Stripping here would leave an empty selector, or match the whole document.
        assertEquals("div", "div".stripTagQualifier())
        assertEquals(".islrtb img", ".islrtb img".stripTagQualifier())
    }

    @Test
    fun `keeps a class or id selector untouched`() {
        assertEquals("#search", "#search".stripTagQualifier())
        assertEquals(".result[data-id='1']", ".result[data-id='1']".stripTagQualifier())
    }

    @Test
    fun `refuses to touch a descendant expression`() {
        // Only the LAST tag is the element being targeted, so a naive strip would rewrite
        // the ancestor and silently select something else.
        assertEquals("form input[name='q']", "form input[name='q']".stripTagQualifier())
        assertEquals("form > input[name='q']", "form > input[name='q']".stripTagQualifier())
    }

    @Test
    fun `refuses to touch a selector list`() {
        assertEquals("input[name='q'],textarea", "input[name='q'],textarea".stripTagQualifier())
    }

    @Test
    fun `handles a tag with digits`() {
        assertEquals("[role='heading']", "h2[role='heading']".stripTagQualifier())
    }
}
