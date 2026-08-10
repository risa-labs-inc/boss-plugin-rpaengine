package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [matchByName] backs the `rpa_load` MCP tool. The cases that matter are the ones where a
 * naive implementation runs the wrong plan rather than failing: the generated names share a
 * long prefix, so precedence is the whole contract.
 */
class ConfigMatchTest {

    private fun config(name: String) = ConfigFileInfo(name = name, path = "/tmp/$name.json", lastModified = 0L)

    @Test
    fun `exact name wins over an earlier substring match`() {
        // The earlier entry must actually CONTAIN the query, otherwise a substring-first
        // implementation finds nothing on its first pass and returns the exact match anyway -
        // passing the mutation this test exists to catch.
        val configs = listOf(config("cats-and-dogs"), config("cats"))
        assertEquals("cats", configs.matchByName("cats")?.name)
    }

    @Test
    fun `exact wins even when a substring candidate comes first`() {
        val configs = listOf(config("plan-v2-draft"), config("plan-v2"))
        assertEquals("plan-v2", configs.matchByName("plan-v2")?.name)
    }

    @Test
    fun `substring matches when no name is exact`() {
        val configs = listOf(config("llm-rpa-open-google-search-for-cat-images"))
        assertEquals(
            "llm-rpa-open-google-search-for-cat-images",
            configs.matchByName("cat-images")?.name,
        )
    }

    @Test
    fun `substring match ignores case`() {
        assertEquals("Cat-Images", listOf(config("Cat-Images")).matchByName("cat-images")?.name)
    }

    @Test
    fun `no match returns null rather than the first entry`() {
        val configs = listOf(config("alpha"), config("beta"))
        assertNull(configs.matchByName("gamma"))
    }

    @Test
    fun `empty list returns null`() {
        assertNull(emptyList<ConfigFileInfo>().matchByName("anything"))
    }
}

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

/**
 * The browser bridge returns `true` for a boolean and `"true"` for a stringified one, so a call
 * site testing only `== true` reads every success as a failure.
 */
class IsJsTrueTest {

    @Test
    fun `accepts both shapes the bridge returns`() {
        assertTrue(true.isJsTrue())
        assertTrue("true".isJsTrue())
    }

    @Test
    fun `rejects falsehood, null and anything else`() {
        assertFalse(false.isJsTrue())
        assertFalse("false".isJsTrue())
        assertFalse(null.isJsTrue())
        assertFalse("".isJsTrue())
        assertFalse(1.isJsTrue())
    }
}
