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
