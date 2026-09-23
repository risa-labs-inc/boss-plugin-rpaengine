package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Result shapes, label normalisation, and the tools end to end against a fake tab. */
class TabActionsTest {

    // ---- pure helpers ------------------------------------------------------------------------

    @Test
    fun `labels are collapsed, capped at 80, and never empty`() {
        assertEquals("Sign in", normalizeLabel("  Sign \n in "))
        assertNull(normalizeLabel("   "))
        assertNull(normalizeLabel(null))
        assertEquals(80, normalizeLabel("x".repeat(200))!!.length)
        // A surrogate pair straddling the cut is dropped rather than split.
        val s = "a".repeat(79) + "😀"
        assertEquals("a".repeat(79), normalizeLabel(s))
    }

    @Test
    fun `observe result has the contract shape and key order`() {
        val snapshot = PageSnapshot(
            url = "https://x.test/",
            title = "  X \n Test ",
            total = 3,
            items = listOf(
                RawElement(
                    tag = "button", role = "button", label = " Sign\n in ", type = "submit",
                    inViewport = true, selector = ObservedSelector("id", "go"),
                ),
                RawElement(
                    tag = "select", role = "combobox", label = "", options = listOf(" One ", "  "),
                    selector = ObservedSelector("css", "select[name=\"s\"]"),
                ),
            ),
        )
        val json = TabJson.encodeToString(ObserveResult.serializer(), buildObserveResult("t1", snapshot))
        assertEquals(
            """{"tab_id":"t1","url":"https://x.test/","title":"X Test","truncated":true,"elements":[""" +
                """{"id":"e1","role":"button","tag":"button","label":"Sign in","type":"submit","placeholder":null,"href":null,"options":null,"checked":null,"sensitive":false,"in_viewport":true,"selector":{"type":"id","value":"go"}},""" +
                """{"id":"e2","role":"combobox","tag":"select","label":null,"type":null,"placeholder":null,"href":null,"options":["One"],"checked":null,"sensitive":false,"in_viewport":false,"selector":{"type":"css","value":"select[name=\"s\"]"}}]}""",
            json,
        )
    }

    @Test
    fun `not truncated when everything fits`() {
        val r = buildObserveResult("t", PageSnapshot(total = 0, items = emptyList()))
        assertFalse(r.truncated)
    }

    @Test
    fun `step result has the contract shape`() {
        assertEquals(
            """{"ok":false,"error":"No element matched #x","url_before":"a","url_after":"a","navigated":false,"duration_ms":12}""",
            TabJson.encodeToString(StepResult.serializer(), StepResult(false, "No element matched #x", "a", "a", false, 12)),
        )
    }

    // ---- end to end against fakes -----------------------------------------------------------

    private class FakeBrowser(var url: String = "https://a.test/") : BrowserIntegration {
        val scripts = mutableListOf<String>()
        var respond: (String) -> Any? = { true }
        override suspend fun executeJavaScript(script: String): Any? {
            scripts += script
            return respond(script)
        }
        override fun isBrowserAvailable() = true
        override suspend fun getCurrentUrl() = url
    }

    private class FakeTabs(private val browsers: Map<String, BrowserIntegration>, otherTabs: List<String> = emptyList()) :
        ActiveTabsProvider {
        override val activeTabs: StateFlow<List<ActiveTabData>> =
            MutableStateFlow((browsers.keys + otherTabs).map { ActiveTabData(it, "t", it, "w", "w", "p", "win") })
        override suspend fun refreshTabs() {}
        override fun selectTab(tabId: String, panelId: String) {}
        override fun getTabUrl(tabId: String): String? = null
        override fun getFaviconCacheKey(tabId: String): String? = null
        @Composable override fun loadFavicon(cacheKey: String?): Painter? = null
        override fun getFallbackIcon(typeId: String): ImageVector? = null
        override fun getBrowserIntegration(tabId: String) = browsers[tabId]
        override fun createBrowserTab(url: String, title: String): String? = null
        override fun closeTab(tabId: String) = false
    }

    private fun errorCode(text: String) =
        Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `unknown tab, non-browser tab, and no provider are distinguished`() = runBlocking {
        val tools = TabActions { FakeTabs(emptyMap(), otherTabs = listOf("term")) }
        tools.observe("""{"tab_id":"nope"}""").let {
            assertTrue(it.isError); assertEquals(TabErrorCodes.TAB_NOT_FOUND, errorCode(it.text))
        }
        tools.observe("""{"tab_id":"term"}""").let {
            assertTrue(it.isError); assertEquals(TabErrorCodes.NO_BROWSER, errorCode(it.text))
        }
        TabActions { null }.step("""{"tab_id":"x","action":{"type":"wait","value":"1"}}""").let {
            assertTrue(it.isError); assertEquals(TabErrorCodes.NO_BROWSER, errorCode(it.text))
        }
    }

    @Test
    fun `observe decodes the page snapshot`() = runBlocking {
        val b = FakeBrowser()
        b.respond = {
            """{"url":"https://a.test/","title":"A","total":1,"items":[{"tag":"a","role":"link","label":"Home","href":"https://a.test/h","inViewport":true,"selector":{"type":"css","value":"a[aria-label=\"Home\"]"}}]}"""
        }
        val r = TabActions { FakeTabs(mapOf("t" to b)) }.observe("""{"tab_id":"t","max_elements":3}""")
        assertFalse(r.isError, r.text)
        val o = Json.parseToJsonElement(r.text).jsonObject
        assertEquals("t", o["tab_id"]!!.jsonPrimitive.content)
        assertEquals(false, o["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(b.scripts.single().contains("var MAX = 3;"))
    }

    @Test
    fun `observe reports a null or erroring script as SCRIPT_FAILED`() = runBlocking {
        val b = FakeBrowser()
        val tools = TabActions { FakeTabs(mapOf("t" to b)) }
        b.respond = { null }
        assertEquals(TabErrorCodes.SCRIPT_FAILED, errorCode(tools.observe("""{"tab_id":"t"}""").text))
        b.respond = { """{"error":"boom"}""" }
        assertEquals(TabErrorCodes.SCRIPT_FAILED, errorCode(tools.observe("""{"tab_id":"t"}""").text))
    }

    @Test
    fun `refused verb never touches the page`() = runBlocking {
        val b = FakeBrowser()
        val r = TabActions { FakeTabs(mapOf("t" to b)) }
            .step("""{"tab_id":"t","action":{"type":"run_script","value":"alert(1)"}}""")
        assertTrue(r.isError)
        assertEquals(TabErrorCodes.UNSUPPORTED_ACTION, errorCode(r.text))
        assertTrue(b.scripts.isEmpty())
    }

    @Test
    fun `click highlights, removes the highlight, then acts - and reports navigation`() = runBlocking {
        val b = FakeBrowser()
        b.respond = { s ->
            // The click body navigates: the frame is torn down and nothing comes back.
            if (s.contains("el.click();")) { b.url = "https://a.test/next"; null } else true
        }
        val r = TabActions { FakeTabs(mapOf("t" to b)) }
            .step("""{"tab_id":"t","action":{"type":"click","selector":{"type":"id","value":"go"}},"highlight_ms":1}""")
        assertFalse(r.isError, r.text)
        val o = Json.parseToJsonElement(r.text).jsonObject
        assertEquals(true, o["ok"]!!.jsonPrimitive.boolean)
        assertEquals(true, o["navigated"]!!.jsonPrimitive.boolean)
        assertEquals("https://a.test/", o["url_before"]!!.jsonPrimitive.content)
        assertEquals("https://a.test/next", o["url_after"]!!.jsonPrimitive.content)

        val draw = b.scripts.indexOfFirst { it.contains(HIGHLIGHT_ATTR) && it.contains("createElement") }
        val remove = b.scripts.indexOfFirst { it.contains(HIGHLIGHT_ATTR) && it.contains("removeChild(n[i])") }
        val click = b.scripts.indexOfFirst { it.contains("el.click();") }
        assertTrue(draw in 0 until remove && remove < click, "order was draw=$draw remove=$remove click=$click")
    }

    @Test
    fun `highlight_ms 0 injects nothing extra`() = runBlocking {
        val b = FakeBrowser()
        TabActions { FakeTabs(mapOf("t" to b)) }
            .step("""{"tab_id":"t","action":{"type":"click","selector":{"type":"css","value":"#go"}},"highlight_ms":0}""")
        assertTrue(b.scripts.none { it.contains(HIGHLIGHT_ATTR) })
    }

    @Test
    fun `a missing element is ok=false, not a tool error`() = runBlocking {
        val b = FakeBrowser()
        b.respond = { s -> if (s.contains("return !!(")) false else true }
        val r = TabActions { FakeTabs(mapOf("t" to b)) }
            .step("""{"tab_id":"t","action":{"type":"input","selector":{"type":"id","value":"nope"},"value":"secret-typed"},"highlight_ms":0}""")
        assertFalse(r.isError)
        val o = Json.parseToJsonElement(r.text).jsonObject
        assertEquals(false, o["ok"]!!.jsonPrimitive.boolean)
        assertEquals(false, o["navigated"]!!.jsonPrimitive.boolean)
        assertFalse(r.text.contains("secret-typed"), "typed values must not appear in the result")
    }
}
