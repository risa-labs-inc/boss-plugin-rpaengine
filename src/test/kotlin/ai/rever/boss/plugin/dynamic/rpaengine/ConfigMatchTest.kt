package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [matchByName] backs the `rpa_load` MCP tool. The cases that matter are the ones where a
 * naive implementation runs the wrong plan rather than failing: the generated names share a
 * long prefix, so precedence is the whole contract.
 */
class ConfigMatchTest {

    private fun config(name: String) = ConfigFileInfo(name = name, path = "/tmp/$name.json", lastModified = 0L)

    @Test
    fun `exact name wins over an earlier substring match`() {
        val configs = listOf(config("llm-rpa-open-google-and-search"), config("cats"))
        // "cats" is a substring of nothing here, but it IS positioned after a longer entry:
        // a substring-first implementation returns the first element instead.
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
