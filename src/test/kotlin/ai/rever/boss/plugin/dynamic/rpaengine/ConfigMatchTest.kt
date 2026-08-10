package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Decoding a configuration written by another plugin.
 *
 * LLM RPA writes these files with `explicitNulls = false`, which omits a null key entirely - and
 * kotlinx treats a field with no default as required. A missing `selector` therefore made the whole
 * configuration unreadable, and the user saw an empty config rather than an error. This pins the
 * contract from the side that can enforce it.
 */
class ConfigurationDecodingTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun decode(actions: String) =
        json.decodeFromString<RpaConfiguration>(
            """{"name":"P","description":"d","actions":[$actions]}""",
        )

    @Test
    fun `an action with no selector decodes`() {
        // What a generated navigate looks like on disk.
        val config = decode("""{"name":"Go","actionType":"default","type":"navigate","value":"https://a.example"}""")

        assertEquals(1, config.actions.size)
        assertEquals(SelectorTypes.NONE, config.actions.first().selector.type)
    }

    @Test
    fun `an action with no meta or value decodes`() {
        val config = decode("""{"name":"Submit","actionType":"default","type":"submit","selector":{"type":"css","value":"form"}}""")

        assertEquals(1, config.actions.size)
        assertNull(config.actions.first().value)
    }

    @Test
    fun `a selector with only a type decodes`() {
        val config = decode("""{"name":"Wait","actionType":"default","type":"wait","selector":{"type":"none"},"value":"100"}""")

        assertEquals(1, config.actions.size)
    }

    @Test
    fun `a configuration with no actions key is refused`() {
        // Required on purpose: it is what tells a configuration apart from settings.json, which
        // lives in the same directory and is scanned by the same code.
        val threw = runCatching { json.decodeFromString<RpaConfiguration>("""{"name":"P"}""") }
        assertTrue(threw.isFailure, "settings.json would parse as an empty configuration")
    }
}
