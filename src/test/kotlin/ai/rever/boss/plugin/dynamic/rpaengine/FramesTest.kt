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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.script.Compilable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Child frames: matching, geometry, the walk's caps, and look / click / type / focused / page_text across frames against fakes. */
class FramesTest {

    // ---- fakes -------------------------------------------------------------------------------

    /** One document's answers, keyed by which script came in. Every script is logged as "<doc>:<kind>". */
    private class Doc(val id: String, val log: MutableList<String>) {
        var look: String? = null
        var framed: (String) -> String? = { null }
        var mark: String? = null
        var hit: String? = null
        var focused: () -> String = { """{"tag":"body","editable":false,"hf":false}""" }
        val scripts = mutableListOf<String>()

        fun answer(script: String): Any? {
            val kind = when {
                script.contains("/*rpa-look*/") -> "look"
                script.contains("/*rpa-framed*/") -> "framed"
                script.contains("/*rpa-mark*/") -> "mark"
                script.contains("/*rpa-frame-hit*/") -> "hit"
                script.contains("JSON.stringify({ tag: tag") -> if (script.contains("el.select()")) "focused+select" else "focused"
                script.contains("/*rpa-quiet*/") || script == quietPollScript() -> "quiet"
                script == Viewport.SCRIPT -> "viewport"
                else -> "js"
            }
            synchronized(log) { log += "$id:$kind" }
            scripts += script
            return when (kind) {
                "look" -> look
                "framed" -> framed(script)
                "mark" -> mark
                "hit" -> hit
                "focused", "focused+select" -> focused()
                "quiet" -> true
                "viewport" -> "1000,800,2"
                else -> true
            }
        }
    }

    private class TopBrowser(val doc: Doc) : BrowserIntegration {
        override suspend fun executeJavaScript(script: String): Any? = doc.answer(script)
        override fun isBrowserAvailable() = true
        override suspend fun getCurrentUrl() = "http://127.0.0.1:8799/page.html"
    }

    private class FakeFrame(val doc: Doc, private val nm: String = "", val kids: MutableList<FakeFrame> = mutableListOf()) : SubFrame {
        override fun name() = nm
        override fun children(): List<SubFrame> = kids
        override suspend fun eval(script: String): Any? = doc.answer(script)
    }

    private class Surface(val kids: List<FakeFrame>) : TabSurface {
        val events = mutableListOf<TabEvent>()
        var onKey: () -> Unit = {}
        override suspend fun bitmap(): BufferedImage = BufferedImage(2000, 1600, BufferedImage.TYPE_INT_RGB)
        override suspend fun dispatch(event: TabEvent): Boolean { events += event; if (event is TabEvent.KeyUp) onKey(); return true }
        override fun childFrames(): List<SubFrame> = kids
    }

    private class Tabs(private val b: BrowserIntegration) : ActiveTabsProvider {
        override val activeTabs: StateFlow<List<ActiveTabData>> = MutableStateFlow(listOf(ActiveTabData("t", "t", "t", "w", "w", "p", "win")))
        override suspend fun refreshTabs() {}
        override fun selectTab(tabId: String, panelId: String) {}
        override fun getTabUrl(tabId: String): String? = null
        override fun getFaviconCacheKey(tabId: String): String? = null
        @Composable override fun loadFavicon(cacheKey: String?): Painter? = null
        override fun getFallbackIcon(typeId: String): ImageVector? = null
        override fun getBrowserIntegration(tabId: String) = if (tabId == "t") b else null
        override fun createBrowserTab(url: String, title: String): String? = null
        override fun closeTab(tabId: String) = false
    }

    private class Rig(kids: List<FakeFrame>, val top: Doc) {
        val dir: File = Files.createTempDirectory("frames").toFile()
        val surface = Surface(kids)
        val tools = TabActions(computerUse = ComputerUse({ surface }, ShotStore(dir)), uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined) { Tabs(TopBrowser(top)) }
        fun run(action: String, extra: String = ""): JsonObject =
            Json.parseToJsonElement(runBlocking { tools.step("""{"tab_id":"t","action":$action,"highlight_ms":0$extra}""") }.text).jsonObject
    }

    private fun iframe(i: Int, x: Double, y: Double, w: Double, h: Double, fi: Int = i, name: String = "", src: String = "", shown: Boolean = true,
                       vis: List<Double>? = listOf(x, y, x + w, y + h), cov: String? = "0".repeat(16), mod: Boolean = false, lab: String = "") =
        IframeInfo(i, fi, name, src, x, y, w, h, shown, vis, cov, mod, lab)

    private fun frameList(vararg f: IframeInfo) = TabJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(IframeInfo.serializer()), f.toList())

    private fun el(id: String, label: String, x: Int, y: Int, w: Int, h: Int, tag: String = "input", extra: String = "") =
        """{"id":"$id","role":"textbox","tag":"$tag","label":"$label","x":$x,"y":$y,"w":$w,"h":$h,"disabled":false,"covered":false,"context":"","editable":true,"xp":"/html[1]/body[1]/$tag[${id.drop(1)}]"$extra}"""

    private fun lookJson(url: String, frames: String, els: List<String>, text: String = "", hash: String = "1a2b3c4d", located: Map<String, String> = emptyMap(), meta: Boolean = false) =
        buildJsonObject {
            if (meta) put("meta", JsonPrimitive("""{"url":"$url","title":"Checkout","vis":"visible"}"""))
            put("vp", JsonPrimitive("1000,800,2"))
            put("hash", JsonPrimitive(hash))
            put("u", JsonPrimitive(url))
            put("f", JsonPrimitive(frames))
            put("located", buildJsonObject { located.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
            put("els", JsonPrimitive("[" + els.joinToString(",") + "]"))
            put("text", JsonPrimitive(text))
        }.toString()

    private fun framedJson(url: String, frames: String, p: String?) = buildJsonObject {
        put("u", JsonPrimitive(url)); put("f", JsonPrimitive(frames)); put("p", p?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull); put("v", JsonPrimitive("1000,800"))
    }.toString()

    /** The checkout fixture: address in the top page, payment in a cross-origin iframe, card number in a srcdoc iframe inside it. */
    private class Checkout(covTop: String = "0".repeat(16)) {
        val log = mutableListOf<String>()
        val top = Doc("top", log)
        val pay = Doc("pay", log)
        val card = Doc("card", log)
        val cardFrame = FakeFrame(card)
        val payFrame = FakeFrame(pay, kids = mutableListOf(cardFrame))
        val rig = Rig(listOf(payFrame), top)
        val topFrames: String
        val payFrames: String

        init {
            val t = FramesTest()
            topFrames = t.frameList(t.iframe(0, 101.0, 201.0, 420.0, 300.0, src = "http://localhost:8799/iframe-pay-frame.html", cov = covTop, lab = "Secure payment"))
            payFrames = t.frameList(t.iframe(0, 17.0, 40.0, 388.0, 46.0, src = "about:srcdoc", lab = "Card number"))
            top.look = t.lookJson("http://127.0.0.1:8799/iframe-checkout.html", topFrames, listOf(t.el("m1", "Delivery address", 10, 100, 200, 30)), text = "Checkout\nTres leches cake", meta = true,
                located = mapOf("Pay" to """{"found":false,"count":0,"in_view":false}"""))
            pay.look = t.lookJson("http://localhost:8799/iframe-pay-frame.html", payFrames,
                listOf(t.el("m1", "Expiry", 16, 120, 100, 30), t.el("m2", "Pay ₹450", 16, 200, 80, 30, tag = "button")), text = "Card number\nExpiry CVC\nPay ₹450", hash = "00000001",
                located = mapOf("Pay" to """{"found":true,"count":1,"in_view":true,"direction":"in_view","screens":0.0,"rect":{"x":16,"y":200,"w":80,"h":30}}"""))
            card.look = t.lookJson("about:srcdoc", "[]", listOf(t.el("m1", "1234 1234 1234 1234", 0, 0, 370, 30)), hash = "00000002")
        }
    }

    // ---- pure: matching and geometry ---------------------------------------------------------

    @Test
    fun `frames match their iframe by name, then by URL, then by window frames order, then document order`() {
        val a = iframe(0, 0.0, 0.0, 10.0, 10.0, fi = 2, name = "login", src = "https://x.test/a")
        val b = iframe(1, 0.0, 0.0, 10.0, 10.0, fi = 0, src = "https://pay.test/frame#k")
        val c = iframe(2, 0.0, 0.0, 10.0, 10.0, fi = 1, src = "https://ads.test/1")
        val d = iframe(3, 0.0, 0.0, 10.0, 10.0, fi = -1, src = "https://ads.test/1")
        val m = matchFrames(
            listOf(ChildId(0, "", "https://pay.test/frame"), ChildId(1, "", "https://ads.test/1"), ChildId(2, "login", "https://elsewhere.test/"), ChildId(3, "", null)),
            listOf(a, b, c, d),
        )
        assertEquals(a, m[2], "by name although its URL changed")
        assertEquals(b, m[0], "by URL, fragment ignored")
        // two iframes share the ads URL, so order decides: window.frames index first, document order after
        assertEquals(c, m[1]); assertEquals(d, m[3])
        // a name shared by two iframes is no evidence
        val n = matchFrames(listOf(ChildId(0, "x", null), ChildId(1, "x", null)), listOf(iframe(0, 0.0, 0.0, 1.0, 1.0, fi = 1, name = "x"), iframe(1, 0.0, 0.0, 1.0, 1.0, fi = 0, name = "x")))
        assertEquals(1, n[0]!!.i); assertEquals(0, n[1]!!.i)
    }

    @Test
    fun `child views add iframe offsets along the chain, clip to every ancestor, and carry covers and sensitivity`() {
        val top = FrameView.top(1000, 800, "http://127.0.0.1:8799/x")
        val f1 = iframe(0, 101.0, 701.0, 420.0, 300.0, vis = listOf(101.0, 701.0, 521.0, 800.0), cov = "1111" + "0".repeat(12), lab = "Secure payment")
        val v1 = childView(top, f1, 0, FakeFrame(Doc("a", mutableListOf())), "http://localhost:8799/pay")
        assertEquals("0", v1.path); assertEquals(101.0, v1.offX); assertEquals(701.0, v1.offY)
        assertEquals(Box(101.0, 701.0, 521.0, 800.0), v1.clip)
        assertFalse(v1.sensitive); assertEquals("localhost", v1.host)
        val f2 = iframe(0, 17.0, 40.0, 388.0, 46.0, vis = listOf(17.0, 40.0, 405.0, 86.0), src = "about:srcdoc", lab = "Card number")
        val v2 = childView(v1, f2, 0, FakeFrame(Doc("b", mutableListOf())), "about:srcdoc")
        assertEquals("0.0", v2.path); assertEquals(118.0, v2.offX); assertEquals(741.0, v2.offY)
        assertEquals(Box(118.0, 741.0, 506.0, 787.0), v2.clip)
        assertTrue(v2.sensitive, "a card-number iframe makes its fields sensitive")
        assertEquals("localhost", v2.host, "srcdoc inherits its parent's host")
        assertEquals(listOf(FrameHop(0, 0, "http://localhost:8799/pay"), FrameHop(0, 0, "about:srcdoc")), v2.hops)
        assertTrue(v2.covers.any { it.coveredAt(150.0, 720.0) }); assertFalse(v2.covers.any { it.coveredAt(150.0, 790.0) })
        // scrolled out of the top viewport: no clip, so no elements
        val off = childView(top, iframe(1, 0.0, 2000.0, 300.0, 300.0, vis = null), 1, FakeFrame(Doc("c", mutableListOf())), "https://x.test/")
        assertNull(off.clip)
        assertNull(childView(off, f2, 0, FakeFrame(Doc("d", mutableListOf())), "about:srcdoc").clip)
    }

    @Test
    fun `placed elements get top-viewport rects, unique ids across frames, frame paths, and modal-frame ordering`() {
        val top = FrameView.top(1000, 800, "https://a.test/")
        val inModal = childView(top, iframe(0, 300.0, 100.0, 400.0, 400.0, mod = true, cov = "0000000000001111"), 0, FakeFrame(Doc("a", mutableListOf())), "https://b.test/")
        val offScreen = childView(top, iframe(1, 0.0, 2000.0, 300.0, 300.0, vis = null), 1, FakeFrame(Doc("b", mutableListOf())), "https://c.test/")
        val raw = { s: String -> TabJson.decodeFromString(RawLookElement.serializer(), s) }
        val placed = placeElements(
            listOf(raw(el("m1", "Close", 690, 100, 10, 10, extra = ""","mod":true""")), raw(el("m2", "Log in", 900, 10, 50, 20))),
            listOf(
                inModal to listOf(raw(el("m1", "Phone", 20, 30, 200, 30)), raw(el("m2", "Verify", 20, 350, 80, 30)), raw(el("m3", "Clipped", 390, 30, 50, 30))),
                offScreen to listOf(raw(el("m1", "Hidden", 10, 10, 50, 20))),
            ),
        )
        val els = placed.map { it.element }
        assertEquals(listOf("Close", "Phone", "Verify", "Clipped", "Log in"), els.map { it.label })
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), els.map { it.id })
        assertEquals(listOf(null, "0", "0", "0", null), els.map { it.frame })
        assertEquals(LookRect(320, 130, 200, 30), els[1].rect)
        assertEquals(LookRect(690, 130, 10, 30), els[3].rect, "clipped to the iframe's right edge")
        assertTrue(els[2].covered, "the parent covers the iframe's bottom row"); assertFalse(els[1].covered)
        // the mark keeps the element's own id in its own frame
        assertEquals("m1", placed[1].mark.localId); assertEquals("0", placed[1].mark.frame!!.path)
        assertNull(placed[4].mark.frame)
        // no frames: the page's ids stand
        assertEquals(listOf("m7"), placeElements(listOf(raw(el("m7", "X", 0, 0, 5, 5))), emptyList()).map { it.element.id })
    }

    @Test
    fun `the walk caps frames at 10 and depth at 3, skips hidden iframes and keeps off-screen ones for text`() = runBlocking {
        val log = mutableListOf<String>()
        val framed = { url: String, frames: String -> { _: String -> framedJson(url, frames, "text of $url") } }
        // 12 rendered siblings: 10 evaluated
        val kids = (0 until 12).map { k -> FakeFrame(Doc("k$k", log).apply { this.framed = framed("https://f$k.test/", "[]") }) }
        val topData = Json.parseToJsonElement(framedJson("https://top.test/", frameList(*(0 until 12).map { iframe(it, 0.0, it * 20.0, 100.0, 20.0, src = "https://f$it.test/") }.toTypedArray()), "")).jsonObject
        val out = walkFrames(FrameView.top(1000, 800, "https://top.test/"), topData, kids, framedScript("1"))
        assertEquals(10, out.size); assertEquals(10, log.size)
        // a chain 5 deep: 3 levels
        log.clear()
        val one = frameList(iframe(0, 10.0, 10.0, 100.0, 100.0))
        val chain = (0 until 5).map { FakeFrame(Doc("c$it", log).apply { this.framed = framed("https://c$it.test/", one) }) }
        for (i in 0 until 4) chain[i].kids += chain[i + 1]
        val deep = walkFrames(FrameView.top(1000, 800, null), Json.parseToJsonElement(framedJson("https://top.test/", one, "")).jsonObject, listOf(chain[0]), framedScript("1"))
        assertEquals(listOf("0", "0.0", "0.0.0"), deep.map { it.view.path })
        assertEquals(30.0, deep.last().view.offX)
        // hidden iframe: never evaluated; off-screen iframe: evaluated, no clip
        log.clear()
        val hidden = FakeFrame(Doc("hidden", log).apply { this.framed = framed("https://h.test/", "[]") })
        val far = FakeFrame(Doc("far", log).apply { this.framed = framed("https://far.test/", "[]") })
        val list = frameList(iframe(0, 0.0, 0.0, 0.0, 0.0, shown = false, vis = null), iframe(1, 0.0, 3000.0, 300.0, 300.0, vis = null, src = "https://far.test/"))
        val r = walkFrames(FrameView.top(1000, 800, null), Json.parseToJsonElement(framedJson("https://top.test/", list, "")).jsonObject, listOf(hidden, far), framedScript("1"))
        assertEquals(listOf("far:framed"), log)
        assertEquals("1", r.single().view.path); assertNull(r.single().view.clip)
    }

    @Test
    fun `hashes fold only when frames exist, frame text blocks are labelled and capped, located prefers an in-view frame match`() {
        assertEquals("abc", combineHashes(listOf("abc")))
        val h = combineHashes(listOf("abc", "1"))!!
        assertTrue(CONTENT_HASH_RE.matches(h)); assertTrue(h != combineHashes(listOf("abc", "2")))
        assertEquals("Top\n\n[frame pay.test]\nCard", joinFrameText("Top", listOf("pay.test" to "Card", "x.test" to "  "), 1000))
        assertEquals(10, joinFrameText("Top", listOf("pay.test" to "Card number"), 10).length)
        val v = childView(FrameView.top(1000, 800, null), iframe(0, 100.0, 200.0, 400.0, 300.0), 0, FakeFrame(Doc("a", mutableListOf())), "https://p.test/")
        val miss = LocatedInfo(false, 0, false)
        val m = mergeLocated(miss, listOf(v to LocatedInfo(true, 2, true, "in_view", 0.0, LocatedRect(10, 20, 30, 10))))
        assertEquals(LocatedInfo(true, 2, true, "in_view", 0.0, LocatedRect(110, 220, 30, 10)), m)
        val topBelow = LocatedInfo(true, 1, false, "below", 1.5)
        assertEquals(3, mergeLocated(topBelow, listOf(v to LocatedInfo(true, 2, false, "below", 0.4))).count)
    }

    // ---- end to end against fakes ------------------------------------------------------------

    @Test
    fun `look covers nested cross-origin frames with offsets, frame paths, unique ids, frame text and located`() {
        val c = Checkout()
        val r = c.rig.run("""{"type":"look","value":"{\"page_text\":true,\"locate\":[\"Pay\"]}"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        val look = r["look"]!!.jsonObject
        val els = look["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("m1", "m2", "m3", "m4"), els.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("Delivery address", "Expiry", "Pay ₹450", "1234 1234 1234 1234"), els.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf(null, "0", "0", "0.0"), els.map { it["frame"]?.jsonPrimitive?.content })
        fun rect(o: JsonObject) = o["rect"]!!.jsonObject.let { listOf(it["x"]!!.jsonPrimitive.int, it["y"]!!.jsonPrimitive.int, it["w"]!!.jsonPrimitive.int, it["h"]!!.jsonPrimitive.int) }
        assertEquals(listOf(117, 321, 100, 30), rect(els[1]))
        assertEquals(listOf(118, 241, 370, 30), rect(els[3]), "card field: top offset + pay iframe + card iframe")
        assertEquals("Checkout\nTres leches cake\n\n[frame localhost]\nCard number\nExpiry CVC\nPay ₹450", look["page_text"]!!.jsonPrimitive.content)
        val pay = look["located"]!!.jsonObject["Pay"]!!.jsonObject
        assertTrue(pay["in_view"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(117, 401, 80, 30), pay["rect"]!!.jsonObject.let { o -> listOf("x", "y", "w", "h").map { o[it]!!.jsonPrimitive.int } })
        assertTrue(look["screenshot"]!!.jsonObject["content_hash"]!!.jsonPrimitive.content != "1a2b3c4d", "frame content moves the hash")
        // one script per document; the children ran the child variant (no meta)
        assertEquals(listOf("top:look", "pay:look", "card:look"), c.log.filter { it.endsWith(":look") })
        assertFalse(c.pay.scripts.first().contains("visibilityState"))
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `click_at by element in a nested frame resolves it in its own frame and clicks the mapped top-viewport point`() {
        val c = Checkout()
        c.rig.run("""{"type":"look"}""")
        c.card.mark = """{"state":"ok","x":185,"y":15,"label":"1234 1234 1234 1234","tag":"input"}"""
        c.pay.hit = """{"state":"ok","x":202,"y":55}"""
        c.top.hit = """{"state":"ok","x":303,"y":256}"""
        c.log.clear()
        val r = c.rig.run("""{"type":"click_at","element":"m4"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        assertEquals(listOf<TabEvent>(TabEvent.Move(303, 256), TabEvent.Press(303, 256), TabEvent.Release(303, 256)), c.rig.surface.events)
        assertEquals(listOf("card:mark", "pay:hit", "top:hit"), c.log.filter { it.endsWith(":mark") || it.endsWith(":hit") })
        // the element is asked for by its own frame's id, and each parent gets the child's point
        assertTrue(c.card.scripts.last { it.contains("/*rpa-mark*/") }.contains("var ID = ${"m1".asJsString()}"))
        assertTrue(c.pay.scripts.last { it.contains("/*rpa-frame-hit*/") }.contains("[0]") && c.pay.scripts.last { it.contains("/*rpa-frame-hit*/") }.contains("+ 185"))
        assertTrue(c.top.scripts.last { it.contains("/*rpa-frame-hit*/") }.contains("+ 202"))
        // quiet waits in the top document and in the frame acted in
        assertTrue(c.log.contains("top:quiet") && c.log.contains("card:quiet"))
        assertEquals("m4", r["clicked"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        // a plain x,y click is unchanged: no frame scripts
        c.log.clear(); c.rig.surface.events.clear()
        assertTrue(c.rig.run("""{"type":"click_at","value":"5,5"}""")["ok"]!!.jsonPrimitive.boolean)
        assertFalse(c.log.any { it.startsWith("card:") || it.startsWith("pay:") || it.endsWith(":hit") })
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `a frame element whose iframe is covered in the parent is refused without a click, and look flags it`() {
        // the top page covers the payment iframe's second sample row (y 276..351): Expiry sits there
        val c = Checkout(covTop = "0000111100000000")
        val els = c.rig.run("""{"type":"look"}""")["look"]!!.jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(false, true, false, false), els.map { it["covered"]!!.jsonPrimitive.boolean })
        c.card.mark = """{"state":"ok","x":185,"y":15,"label":"1234 1234 1234 1234","tag":"input"}"""
        c.pay.hit = """{"state":"ok","x":202,"y":55}"""
        c.top.hit = """{"state":"covered","cover":"Cookie banner"}"""
        val r = c.rig.run("""{"type":"click_at","element":"m4"}""")
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("'1234 1234 1234 1234' is covered by Cookie banner", r["error"]!!.jsonPrimitive.content)
        assertTrue(c.rig.surface.events.isEmpty())
        // disabled inside the frame: refused there, parents never asked
        c.log.clear()
        c.card.mark = """{"state":"disabled","label":"1234 1234 1234 1234","tag":"input"}"""
        assertEquals("'1234 1234 1234 1234' is disabled", c.rig.run("""{"type":"click_at","element":"m4"}""")["error"]!!.jsonPrimitive.content)
        assertFalse(c.log.any { it.endsWith(":hit") })
        // the frame went away: gone
        c.payFrame.kids.clear()
        assertTrue(c.rig.run("""{"type":"click_at","element":"m4"}""")["error"]!!.jsonPrimitive.content.contains("gone"))
        c.rig.dir.deleteRecursively()
    }

    private val payFocused = """{"tag":"iframe","editable":false,"hf":true,"fr":{"i":0,"fi":0,"name":"","lab":"Card number"}}"""

    @Test
    fun `focused walks down into the frame that has focus, and a card frame field is sensitive`() {
        val c = Checkout()
        val other = Doc("other", c.log).apply { focused = { """{"tag":"body","editable":false,"hf":false}""" } }
        val rig = Rig(listOf(FakeFrame(other), c.payFrame), c.top)
        c.top.focused = { """{"tag":"iframe","editable":false,"hf":true,"fr":{"i":1,"fi":1,"name":"","lab":"Secure payment"}}""" }
        c.pay.focused = { payFocused }
        c.card.focused = { """{"tag":"input","editable":true,"label":"1234 1234 1234 1234","type":"text","sensitive":false,"hf":true,"xp":"/html[1]/body[1]/input[1]"}""" }
        val f = rig.run("""{"type":"focused"}""")["focused"]!!.jsonObject
        assertEquals("input", f["tag"]!!.jsonPrimitive.content)
        assertEquals("1.0", f["frame"]!!.jsonPrimitive.content)
        assertTrue(f["sensitive"]!!.jsonPrimitive.boolean)
        assertFalse(f.containsKey("hf") || f.containsKey("xp") || f.containsKey("fr"), "walk details stay inside rpaengine")
        // typing into it needs allow_sensitive, and the select-all runs in the card frame, not the top
        assertTrue(rig.run("""{"type":"type_text","value":"4242"}""").toString().contains("INVALID_INPUT"))
        c.log.clear()
        val ok = rig.run("""{"type":"type_text","value":"4242"}""", ""","allow_sensitive":true""")
        assertTrue(ok["ok"]!!.jsonPrimitive.boolean, ok.toString())
        assertEquals(listOf("card:focused+select"), c.log.filter { it.endsWith("+select") })
        assertEquals(4, rig.surface.events.filterIsInstance<TabEvent.KeyDown>().size)
        assertFalse(ok.containsKey("note"), "focus stayed on the field")
        // top-level focus stays as before: no frame key
        c.top.focused = { """{"tag":"input","editable":true,"label":"Delivery address","type":"text","hf":true,"xp":"/html[1]/body[1]/input[1]"}""" }
        assertFalse(rig.run("""{"type":"focused"}""")["focused"]!!.jsonObject.containsKey("frame"))
        rig.dir.deleteRecursively(); c.rig.dir.deleteRecursively()
    }

    @Test
    fun `typing a code into the first split box with real keys lets the page advance focus, and the result notes it`() {
        val log = mutableListOf<String>()
        val top = Doc("top", log)
        val login = Doc("login", log)
        val rig = Rig(listOf(FakeFrame(login)), top)
        top.focused = { """{"tag":"iframe","editable":false,"hf":true,"fr":{"i":0,"fi":0,"name":"","lab":"Login"}}""" }
        var box = 1
        login.focused = { """{"tag":"input","editable":true,"label":"Digit $box","type":"text","hf":true,"xp":"/html[1]/body[1]/div[3]/div[1]/input[$box]"}""" }
        rig.surface.onKey = { if (box < 6) box++ } // the page's input handler focuses the next box
        val r = rig.run("""{"type":"type_text","value":"424242"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        // one select-all, before the first key; then six plain strokes, no selection or script between them
        assertEquals(1, log.count { it.endsWith("+select") })
        val ev = rig.surface.events
        assertEquals(listOf("4", "2", "4", "2", "4", "2"), ev.filterIsInstance<TabEvent.KeyChar>().map { it.char.toString() })
        assertEquals("Digit 6", r["focused"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("Focus moved while typing from 'Digit 1' to 'Digit 6' (the page advanced it, as split code boxes do)", r["note"]!!.jsonPrimitive.content)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `page_text, locate_text and the screenshot hash include frames, without frames they send the old scripts`() {
        val c = Checkout()
        c.top.framed = { s -> framedJson("http://127.0.0.1:8799/iframe-checkout.html", c.topFrames, if (s.contains("createTreeWalker")) """{"found":false,"count":0,"in_view":false}""" else if (s.contains("innerText") && !s.contains("5381")) "Checkout" else "aaaa0001") }
        c.pay.framed = { s -> framedJson("http://localhost:8799/iframe-pay-frame.html", c.payFrames, if (s.contains("createTreeWalker")) """{"found":true,"count":1,"in_view":true,"direction":"in_view","screens":0.0,"rect":{"x":16,"y":200,"w":80,"h":30}}""" else if (s.contains("innerText") && !s.contains("5381")) "Pay ₹450" else "bbbb0002") }
        c.card.framed = { s -> framedJson("about:srcdoc", "[]", if (s.contains("createTreeWalker")) """{"found":false,"count":0,"in_view":false}""" else if (s.contains("innerText") && !s.contains("5381")) "" else "cccc0003") }
        assertEquals("Checkout\n\n[frame localhost]\nPay ₹450", c.rig.run("""{"type":"page_text"}""")["text"]!!.jsonPrimitive.content)
        val loc = c.rig.run("""{"type":"locate_text","value":"Pay"}""")["located"]!!.jsonObject
        assertTrue(loc["in_view"]!!.jsonPrimitive.boolean); assertEquals(401, loc["rect"]!!.jsonObject["y"]!!.jsonPrimitive.int)
        assertEquals(listOf("top:framed", "pay:framed", "card:framed"), c.log.takeLast(3))
        val shot = c.rig.run("""{"type":"screenshot"}""")["screenshot"]!!.jsonObject
        assertEquals(combineHashes(listOf("aaaa0001", "bbbb0002", "cccc0003")), shot["content_hash"]!!.jsonPrimitive.content)
        // no frames: page_text is the plain script, as before
        val log = mutableListOf<String>()
        val plain = Doc("top", log)
        val rig = Rig(emptyList(), plain)
        rig.run("""{"type":"page_text"}""")
        assertEquals(listOf("top:js"), log)
        rig.dir.deleteRecursively(); c.rig.dir.deleteRecursively()
    }

    // ---- page scripts in a fake DOM ----------------------------------------------------------

    private fun frameDom(): javax.script.ScriptEngine {
        val e = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        e.eval("""
            function F(tag,o){this.tagName=tag.toUpperCase();this.nodeName=this.tagName;this.localName=tag;this.nodeType=1;this.attrs={};this.clientLeft=0;this.clientTop=0;this.parentElement=null;for(var k in o)this[k]=o[k];}
            F.prototype.getBoundingClientRect=function(){var r=this.rect;return {left:r[0],top:r[1],right:r[2],bottom:r[3],width:r[2]-r[0],height:r[3]-r[1]};};
            F.prototype.getAttribute=function(n){return this.attrs.hasOwnProperty(n)?this.attrs[n]:null;};
            F.prototype.hasAttribute=function(n){return this.attrs.hasOwnProperty(n);};
            F.prototype.closest=function(){return null;};
            F.prototype.contains=function(o){return o===this;};
            F.prototype.matches=function(){return false;};
            F.prototype.querySelector=function(){return null;};
            var w0={},w1={},w2={};
            var window={innerWidth:1000,innerHeight:800,frames:[w1,w0],getComputedStyle:function(e){return {display:e.display||'block',visibility:'visible',paddingLeft:'0px',paddingTop:'0px',paddingRight:'0px',paddingBottom:'0px',position:'static',zIndex:'auto'};}};
            var A=new F('iframe',{rect:[100,50,500,450],clientLeft:1,clientTop:1,clientWidth:398,clientHeight:398,contentWindow:w0,src:'http://localhost:8799/login.html'});A.attrs={name:'login',title:'Login'};
            var B=new F('iframe',{rect:[0,0,0,0],clientWidth:0,clientHeight:0,display:'none',contentWindow:w2,src:''});B.attrs={srcdoc:'<p>x</p>'};
            var C=new F('iframe',{rect:[0,2000,300,2300],clientWidth:300,clientHeight:300,contentWindow:w1,src:'http://ads.test/'});
            var banner=new F('div',{rect:[0,0,300,250]});banner.attrs={'aria-label':'Cookie banner'};
            var html=new F('html',{rect:[0,0,1000,800]}),body=new F('body',{rect:[0,0,1000,800]});
            var focus=A;
            var document={documentElement:html,body:body,activeElement:A,hasFocus:function(){return true;},
              querySelectorAll:function(s){return s==='iframe,frame'?[A,B,C]:[];},
              elementsFromPoint:function(x,y){return (x<300&&y<250)?[banner,body]:(x>=100&&x<500&&y>=50&&y<450)?[A,body]:[body];},
              elementFromPoint:function(){return null;},getElementById:function(){return null;}};
        """.trimIndent())
        return e
    }

    @Test
    fun `frames list script reports content-box offsets, the covered grid, window frames index, srcdoc and visibility`() {
        listOf(framesListScript(), framedScript(pageTextScript(10)), frameHitScript(0, 1, 2), lookScript(LookOptions(), child = true), lookScript(LookOptions()))
            .forEach { (NashornScriptEngineFactory().getScriptEngine("--language=es5") as Compilable).compile("($it)") }
        val e = frameDom()
        val list = TabJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(IframeInfo.serializer()), e.eval(framesListScript()) as String)
        assertEquals(3, list.size)
        val a = list[0]
        assertEquals(101.0, a.x); assertEquals(51.0, a.y); assertEquals(398.0, a.w)
        assertEquals(1, a.fi); assertEquals("login", a.name); assertEquals("Login", a.lab); assertTrue(a.shown)
        assertEquals(listOf(101.0, 51.0, 499.0, 449.0), a.vis)
        assertEquals("1100110000000000", a.cov, "the banner sits over the top-left quarter")
        assertEquals("about:srcdoc", list[1].src); assertFalse(list[1].shown); assertNull(list[1].vis)
        assertTrue(list[2].shown); assertNull(list[2].vis, "scrolled out of view"); assertEquals(0, list[2].fi)
        // with no iframes the script answers at once
        e.eval("document.querySelectorAll=function(){return [];};")
        assertEquals("[]", e.eval(framesListScript()))
    }

    @Test
    fun `frame hit script maps a child point through the iframe and reports what covers it`() {
        val e = frameDom()
        fun hit(px: Int, py: Int) = TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(frameHitScript(0, px, py)) as String)
        assertEquals(MarkAnswer("ok", 401, 351), hit(300, 300))
        assertEquals(MarkAnswer("covered", cover = "Cookie banner"), hit(10, 20))
        assertEquals("gone", TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(frameHitScript(7, 0, 0)) as String).state)
    }

    @Test
    fun `focused script names the focused iframe and never describes inside it`() {
        val e = frameDom()
        e.eval("var NOT_USED = 1;")
        val raw = e.eval(focusedScript(false)) as String
        val extra = TabJson.decodeFromString(FocusExtra.serializer(), raw)
        assertEquals(FocusFrameRef(0, 1, "login", "Login"), extra.fr)
        assertEquals(true, extra.hf)
        assertEquals("iframe", TabJson.decodeFromString(FocusedInfo.serializer(), raw).tag)
        assertFalse(TabJson.decodeFromString(FocusedInfo.serializer(), raw).editable)
    }

    @Test
    fun `snap never moves a click that lands on an iframe`() {
        val e = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        e.eval("""
            var window={innerWidth:1000,innerHeight:800,getComputedStyle:function(){return {display:'block',visibility:'visible'};}};
            var frame={tagName:'IFRAME',nodeType:1,parentElement:null};
            var btn={tagName:'BUTTON',nodeType:1,disabled:false,hidden:false,getAttribute:function(){return null;},hasAttribute:function(){return false;},matches:function(){return true;},closest:function(){return null;},
              getBoundingClientRect:function(){return {left:90,top:90,right:130,bottom:110};},textContent:'Go'};
            var document={elementFromPoint:function(){return frame;},querySelectorAll:function(){return [btn];}};
        """.trimIndent())
        val a = TabJson.decodeFromString(SnapAnswer.serializer(), e.eval(snapScript(100, 100, SnapBox(80.0, 80.0, 140.0, 120.0))) as String)
        assertFalse(a.moved)
    }
}
