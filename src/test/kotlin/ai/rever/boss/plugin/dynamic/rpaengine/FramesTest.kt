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
        var lookFn: (() -> String?)? = null
        var framed: (String) -> String? = { null }
        var mark: String? = null
        var hit: String? = null
        var focused: () -> String = { """{"tag":"body","editable":false,"hf":false}""" }
        var len: () -> Any? = { -1 }
        var value: (String) -> String? = { null }
        var count: () -> Any? = { 0 }
        var markFocus: () -> String? = { null }
        val scripts = mutableListOf<String>()

        fun answer(script: String): Any? {
            val kind = when {
                script.contains("/*rpa-len*/") -> "len"
                script.contains("/*rpa-value*/") -> "value"
                script.contains("/*rpa-count*/") -> "count"
                script.contains("/*rpa-mark-focus*/") -> "mark-focus"
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
                "look" -> lookFn?.invoke() ?: look
                "framed" -> framed(script)
                "mark" -> mark
                "hit" -> hit
                "len" -> len()
                "value" -> value(script)
                "count" -> count()
                "mark-focus" -> markFocus()
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

    private class Surface(val kids: List<FakeFrame>, val log: MutableList<String>? = null) : TabSurface {
        val events = mutableListOf<TabEvent>()
        var onKey: () -> Unit = {}
        override suspend fun bitmap(): BufferedImage = BufferedImage(2000, 1600, BufferedImage.TYPE_INT_RGB)
        override suspend fun focus() { log?.let { synchronized(it) { it += "focus" } } }
        override suspend fun dispatch(event: TabEvent): Boolean {
            events += event; log?.let { synchronized(it) { it += "ev:${event::class.simpleName}" } }
            if (event is TabEvent.KeyUp) onKey(); return true
        }
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
        val surface = Surface(kids, top.log)
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
        val walk = walkFrames(FrameView.top(1000, 800, "https://top.test/"), topData, kids, framedScript("1"))
        assertEquals(10, walk.answers.size); assertEquals(10, log.size)
        assertEquals(listOf("frame budget (10) spent", "frame budget (10) spent"), walk.debug.mapNotNull { it.excludedReason })
        // a chain 5 deep: 3 levels
        log.clear()
        val one = frameList(iframe(0, 10.0, 10.0, 100.0, 100.0))
        val chain = (0 until 5).map { FakeFrame(Doc("c$it", log).apply { this.framed = framed("https://c$it.test/", one) }) }
        for (i in 0 until 4) chain[i].kids += chain[i + 1]
        val deep = walkFrames(FrameView.top(1000, 800, null), Json.parseToJsonElement(framedJson("https://top.test/", one, "")).jsonObject, listOf(chain[0]), framedScript("1")).answers
        assertEquals(listOf("0", "0.0", "0.0.0"), deep.map { it.view.path })
        assertEquals(30.0, deep.last().view.offX)
        // hidden iframe: dropped with its reason; off-screen iframe: kept, no clip
        log.clear()
        val hidden = FakeFrame(Doc("hidden", log).apply { this.framed = framed("https://h.test/", "[]") })
        val far = FakeFrame(Doc("far", log).apply { this.framed = framed("https://far.test/", "[]") })
        val list = frameList(iframe(0, 0.0, 0.0, 0.0, 0.0, shown = false, vis = null), iframe(1, 0.0, 3000.0, 300.0, 300.0, vis = null, src = "https://far.test/"))
        val w = walkFrames(FrameView.top(1000, 800, null), Json.parseToJsonElement(framedJson("https://top.test/", list, "")).jsonObject, listOf(hidden, far), framedScript("1"))
        val r = w.answers
        assertEquals(listOf("far:framed"), log.filter { it.startsWith("far") }, "a rendered iframe's frame goes first")
        assertEquals("1", r.single().view.path); assertNull(r.single().view.clip)
        assertEquals(mapOf("0" to "iframe not rendered: zero size or hidden", "1" to "scrolled out of view (text only)"), w.debug.associate { it.path to it.excludedReason })
        assertEquals("https://h.test/", w.debug.first { it.path == "0" }.url)
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

    // ---- typing timing -----------------------------------------------------------------------

    private val addrFocused = """{"tag":"input","editable":true,"label":"Delivery address","type":"text","hf":true,"xp":"/html[1]/body[1]/input[1]"}"""

    @Test
    fun `type_text returns only once the typed length has landed, then waits for quiet`() {
        val c = Checkout()
        c.top.focused = { addrFocused }
        val lens = ArrayDeque(listOf(3, 5, 8)) // the engine applies keys asynchronously: "1A 2B 9z" lands over three polls
        c.top.len = { (if (lens.size > 1) lens.removeFirst() else lens.first()).toDouble() } // JxBrowser hands numbers back as Double
        val r = c.rig.run("""{"type":"type_text","value":"1A 2B 9z"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        assertFalse(r.containsKey("typed_complete"))
        assertEquals(3, c.log.count { it == "top:len" })
        val lastLen = c.log.lastIndexOf("top:len")
        assertTrue(c.log.indexOf("top:quiet") > lastLen, "the quiet wait comes after the text landed")
        assertTrue(c.log.indexOf("top:len") > c.log.lastIndexOf("ev:KeyUp"), "polled after the last key")
        // Enter is a keystroke, not content: "ab\n" lands as length 2
        assertEquals(2, typedLength("ab\n")); assertEquals(3, typedLength("a\tbc"))
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `type_text completes when the length stops changing, e g a maxlength or formatting field`() {
        val c = Checkout()
        c.top.focused = { addrFocused }
        c.top.len = { 5 }
        val t0 = System.currentTimeMillis()
        val r = c.rig.run("""{"type":"type_text","value":"1A 2B 9z"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean); assertFalse(r.containsKey("typed_complete"))
        assertTrue(System.currentTimeMillis() - t0 < TYPE_CAP_MIN_MS, "stable for 150 ms is done, not the cap")
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `type_text reports typed_complete false when the text has not landed by the cap`() {
        val c = Checkout()
        c.top.focused = { addrFocused }
        var n = 0
        c.top.len = { ++n % 7 } // keeps changing, never the expected 8
        val t0 = System.currentTimeMillis()
        val r = c.rig.run("""{"type":"type_text","value":"1A 2B 9z"}""")
        val took = System.currentTimeMillis() - t0
        assertTrue(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(false, r["typed_complete"]!!.jsonPrimitive.boolean)
        assertTrue(took >= TYPE_CAP_MIN_MS, "capped at max(1500 ms, 25 ms x chars): $took")
        assertTrue(took < TYPE_CAP_MIN_MS + 1_500, "and no longer: $took")
        // a <select> keeps type-ahead as before: no length poll
        c.log.clear()
        c.top.focused = { """{"tag":"select","editable":false,"label":"Size","options":["S","L"],"hf":true}""" }
        c.rig.run("""{"type":"type_text","value":"L"}""")
        assertFalse(c.log.contains("top:len"))
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `type_text by element waits for the field to take focus before the select-all and the first key, with no modifier held`() {
        val c = Checkout()
        c.rig.run("""{"type":"look"}""")
        c.top.mark = """{"state":"ok","x":110,"y":115,"label":"Delivery address","tag":"input"}"""
        val focus = ArrayDeque(listOf("""{"can":true,"f":false}""", """{"can":true,"f":false}""", """{"can":true,"f":true}"""))
        c.top.markFocus = { if (focus.size > 1) focus.removeFirst() else focus.first() }
        c.top.focused = { addrFocused }
        c.top.len = { 21 }
        c.log.clear()
        val r = c.rig.run("""{"type":"type_text","element":"m1","value":"12 MG Road, Bengaluru"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        val log = c.log
        val release = log.indexOf("ev:Release")
        val focusPolls = log.withIndex().filter { it.value == "top:mark-focus" }.map { it.index }
        val select = log.indexOf("top:focused+select")
        val firstKey = log.indexOf("ev:KeyDown")
        assertEquals(3, focusPolls.size, "polled until the field was the active element")
        assertTrue(release < focusPolls.first() && focusPolls.last() < select && select < firstKey, log.toString())
        // the browser was focused once, before the click; never again between the click and the keys
        assertEquals(listOf(log.indexOf("focus")), log.withIndex().filter { it.value == "focus" }.map { it.index })
        assertTrue(log.indexOf("focus") < log.indexOf("ev:Press"))
        // the select-all is a script, not a chord: no event before the first character carries ctrl/meta, and the first key is '1'
        val ev = c.rig.surface.events
        val first = ev.indexOfFirst { it is TabEvent.KeyDown }
        assertFalse(ev.any { (it is TabEvent.KeyDown && (it.mods.ctrl || it.mods.meta)) || (it is TabEvent.KeyUp && (it.mods.ctrl || it.mods.meta)) })
        assertEquals(TabEvent.KeyDown("KEY_CODE_1", '1'), ev[first])
        assertTrue(ev.subList(0, first).none { it is TabEvent.KeyUp || it is TabEvent.KeyChar })
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `click_at on an element that takes no text focus does not wait, and a field that never focuses waits at most 500 ms`() {
        val c = Checkout()
        c.rig.run("""{"type":"look"}""")
        c.top.mark = """{"state":"ok","x":110,"y":115,"label":"Delivery address","tag":"input"}"""
        c.top.markFocus = { """{"can":false,"f":false}""" }
        c.log.clear()
        c.rig.run("""{"type":"click_at","element":"m1"}""")
        assertEquals(1, c.log.count { it == "top:mark-focus" })
        c.top.markFocus = { """{"can":true,"f":false}""" }
        val t0 = System.currentTimeMillis()
        assertTrue(c.rig.run("""{"type":"click_at","element":"m1"}""")["ok"]!!.jsonPrimitive.boolean)
        val took = System.currentTimeMillis() - t0
        assertTrue(took in FOCUS_WAIT_MS..(FOCUS_WAIT_MS + 1_000), "$took")
        // a plain x,y click asks nothing
        c.log.clear()
        c.rig.run("""{"type":"click_at","value":"5,5"}""")
        assertFalse(c.log.contains("top:mark-focus"))
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `mark focus script tells a focused field from an unfocused one and from a button`() {
        assertTrue(markFocusScript("m1", MarkRef("/html[1]", "x", "input")).let { it.contains("/*rpa-mark-focus*/") && !it.contains("/*rpa-mark*/") })
        (NashornScriptEngineFactory().getScriptEngine("--language=es5") as Compilable).compile("(${markFocusScript("m1", MarkRef("/html[1]", "x", "input"))})")
        (NashornScriptEngineFactory().getScriptEngine("--language=es5") as Compilable).compile("(${focusedLengthScript()})")
        val e = frameDom()
        e.eval("""
            var field=new F('input',{rect:[0,0,10,10]});field.attrs={type:'text'};field.value='hunter2';
            var go=new F('button',{rect:[0,0,10,10]});
            html.contains=function(o){return o===field||o===go;};
            window.__rpaMarks={m1:field,m2:go};
        """.trimIndent())
        fun ask(id: String, tag: String) = TabJson.decodeFromString(MarkFocus.serializer(), e.eval(markFocusScript(id, MarkRef(null, "", tag))) as String)
        e.eval("document.activeElement=body;")
        assertEquals(MarkFocus(can = true, f = false), ask("m1", "input"))
        e.eval("document.activeElement=field;")
        assertEquals(MarkFocus(can = true, f = true), ask("m1", "input"))
        assertFalse(ask("m2", "button").can)
        // the length script reports the length only
        assertEquals(7, (e.eval(focusedLengthScript()) as Number).toInt())
        assertFalse(focusedLengthScript().contains("return el.value") || focusedLengthScript().contains("value+"))
        e.eval("document.activeElement=go;")
        assertEquals(-1, (e.eval(focusedLengthScript()) as Number).toInt())
    }

    // ---- real-site frames: matching, debug, late frames ------------------------------------------

    @Test
    fun `a redirected frame among hidden siblings matches its own iframe by size or window frames index, not the first leftover`() {
        // Zomato-like: ad/analytics frames first, the login iframe's src redirected, and only some children evaluated.
        val iframes = listOf(
            iframe(0, 0.0, 0.0, 0.0, 0.0, fi = 0, src = "https://gtm.test/ns", shown = false, vis = null),
            iframe(1, 0.0, 0.0, 0.0, 0.0, fi = 1, src = "about:blank", shown = false, vis = null),
            iframe(2, 300.0, 100.0, 380.0, 420.0, fi = 2, src = "https://accounts.zomato.test/oauth2/auth?x=1").copy(lw = 380.0, lh = 420.0),
        )
        val bySize = matchFrames(listOf(ChildId(5, "", "https://accounts.zomato.test/login?x=1", 380, 420)), iframes)
        assertEquals(2, bySize[5]!!.i, "own viewport size equals the iframe's content box")
        val byIndex = matchFrames(listOf(ChildId(2, "", "https://accounts.zomato.test/login?x=1")), iframes)
        assertEquals(2, byIndex[2]!!.i, "window.frames index equal to the child's index")
        // about:blank written later: no URL to go on, the size still matches
        assertEquals(2, matchFrames(listOf(ChildId(0, "", "about:blank", 380, 420)), iframes.map { if (it.i == 1) it.copy(src = "x") else it })[0]!!.i)
    }

    @Test
    fun `look carries frames_debug with url, matched iframe, element counts, and frames that never answered`() {
        val c = Checkout()
        val silent = Doc("silent", c.log) // look -> null: timed out or scripts blocked by a sandbox
        val rig = Rig(listOf(c.payFrame, FakeFrame(silent)), c.top)
        val dbg = rig.run("""{"type":"look"}""")["look"]!!.jsonObject["frames_debug"]!!.jsonArray.map { it.jsonObject }
        val byPath = dbg.associateBy { it["path"]!!.jsonPrimitive.content }
        assertEquals(setOf("0", "0.0", "1"), byPath.keys)
        assertEquals("http://localhost:8799/iframe-pay-frame.html", byPath["0"]!!["url"]!!.jsonPrimitive.content)
        assertEquals(2, byPath["0"]!!["elements"]!!.jsonPrimitive.int); assertEquals(1, byPath["0.0"]!!["elements"]!!.jsonPrimitive.int)
        val m = byPath["0"]!!["matched_iframe"]!!.jsonObject
        assertEquals("http://localhost:8799/iframe-pay-frame.html", m["src"]!!.jsonPrimitive.content)
        assertEquals(101, m["rect"]!!.jsonObject["x"]!!.jsonPrimitive.int)
        assertFalse(byPath["0"]!!.containsKey("excluded_reason"))
        assertTrue(byPath["1"]!!["excluded_reason"]!!.jsonPrimitive.content.startsWith("no answer within 2 s"))
        // no frames: no frames_debug
        val plain = Rig(emptyList(), Doc("top", mutableListOf()).apply { look = c.top.look })
        assertFalse(plain.run("""{"type":"look"}""")["look"]!!.jsonObject.containsKey("frames_debug"))
        rig.dir.deleteRecursively(); plain.dir.deleteRecursively(); c.rig.dir.deleteRecursively()
    }

    @Test
    fun `a new frame that is still empty gets up to 800 ms to populate and one re-read, and the tree is re-walked every look`() {
        val log = mutableListOf<String>()
        val top = Doc("top", log)
        val login = Doc("login", log)
        val frames = frameList(iframe(0, 300.0, 100.0, 380.0, 420.0, src = "http://localhost:8799/iframe-login-frame.html", lab = "Login"))
        top.look = lookJson("http://127.0.0.1:8799/iframe-login.html", frames, listOf(el("m1", "Log in", 900, 10, 50, 20, tag = "button")), meta = true)
        var populated = false
        login.look = null
        val emptyLook = lookJson("http://localhost:8799/iframe-login-frame.html", "[]", emptyList())
        val fullLook = lookJson("http://localhost:8799/iframe-login-frame.html", "[]", listOf(el("m1", "Phone", 20, 60, 200, 30)))
        var counts = 0
        login.count = { counts++; if (counts >= 3) { populated = true; 1 } else 0 }
        val kids = mutableListOf<FakeFrame>()
        val rig = Rig(kids, top)
        // first look: no frame yet
        assertEquals(listOf("Log in"), rig.run("""{"type":"look"}""")["look"]!!.jsonObject["elements"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content })
        // the dialog opens: a frame appears after page load, empty at first
        kids += FakeFrame(login)
        login.lookFn = { if (populated) fullLook else emptyLook }
        val t0 = System.currentTimeMillis()
        val els = rig.run("""{"type":"look"}""")["look"]!!.jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        assertTrue(System.currentTimeMillis() - t0 < FRAME_POPULATE_MS + 600)
        assertEquals(listOf("Log in", "Phone"), els.map { it["label"]!!.jsonPrimitive.content })
        assertEquals("0", els[1]["frame"]!!.jsonPrimitive.content)
        assertEquals(2, log.count { it == "login:look" }, "one re-read after it populated")
        assertEquals(3, counts)
        // the same frame next time: no waiting even if it is empty again
        populated = false; log.clear()
        rig.run("""{"type":"look"}""")
        assertEquals(1, log.count { it == "login:look" }); assertFalse(log.contains("login:count"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `child views and placement apply an iframe's transform scale`() {
        val top = FrameView.top(1000, 800, null)
        val v = childView(top, iframe(0, 100.0, 100.0, 200.0, 200.0).copy(sx = 0.5, sy = 0.5), 0, FakeFrame(Doc("a", mutableListOf())), "https://x.test/")
        val raw = TabJson.decodeFromString(RawLookElement.serializer(), el("m1", "Phone", 40, 60, 100, 20))
        assertEquals(LookRect(120, 130, 50, 10), placeElements(emptyList(), listOf(v to listOf(raw))).single().element.rect)
        val nested = childView(v, iframe(0, 10.0, 10.0, 50.0, 50.0), 0, FakeFrame(Doc("b", mutableListOf())), "https://y.test/")
        assertEquals(105.0, nested.offX); assertEquals(0.5, nested.sx)
    }

    // ---- type_text self-check -----------------------------------------------------------------

    @Test
    fun `type_text checks the field holds exactly the text, retypes once when not, and returns the value`() {
        val c = Checkout()
        c.top.focused = { addrFocused }
        c.top.len = { 21 }
        val text = "12 MG Road, Bengaluru"
        var reads = 0
        c.top.value = { s ->
            reads++
            val v = if (reads == 1) text.drop(1) else text
            """{"xp":"/html[1]/body[1]/input[1]","n":${v.length},"h":"${djb2Hex(v)}"${if (s.contains("v: s.slice")) ""","v":"$v"""" else ""}}"""
        }
        c.log.clear()
        val r = c.rig.run("""{"type":"type_text","value":"$text"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        assertEquals(true, r["retyped"]!!.jsonPrimitive.boolean)
        assertEquals(true, r["value_ok"]!!.jsonPrimitive.boolean)
        assertEquals(text, r["value"]!!.jsonPrimitive.content)
        assertEquals(2, c.log.count { it == "top:focused+select" }, "select-all again before the retype")
        assertEquals(text.length * 2, c.rig.surface.events.count { it is TabEvent.KeyChar })
        // right the first time: no retype
        reads = 1; c.rig.surface.events.clear()
        val ok = c.rig.run("""{"type":"type_text","value":"$text"}""")
        assertFalse(ok.containsKey("retyped")); assertEquals(true, ok["value_ok"]!!.jsonPrimitive.boolean)
        assertEquals(text.length, c.rig.surface.events.count { it is TabEvent.KeyChar })
        // still wrong after the one retype (a formatting field): value_ok false, no third try
        c.top.value = { """{"xp":"/html[1]/body[1]/input[1]","n":3,"h":"${djb2Hex("abc")}","v":"abc"}""" }
        c.rig.surface.events.clear()
        val bad = c.rig.run("""{"type":"type_text","value":"$text"}""")
        assertEquals(false, bad["value_ok"]!!.jsonPrimitive.boolean); assertEquals("abc", bad["value"]!!.jsonPrimitive.content)
        assertEquals(text.length * 2, c.rig.surface.events.count { it is TabEvent.KeyChar })
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `a sensitive field reports value_ok only and its content never leaves the page, and moved focus is not retyped`() {
        val c = Checkout()
        c.top.focused = { """{"tag":"input","editable":true,"label":"Password","type":"password","sensitive":true,"hf":true,"xp":"/html[1]/body[1]/input[2]"}""" }
        c.top.len = { 8 }
        val scripts = mutableListOf<String>()
        c.top.value = { s -> scripts += s; """{"xp":"/html[1]/body[1]/input[2]","n":8,"h":"${djb2Hex("hunter22")}"}""" }
        val r = c.rig.run("""{"type":"type_text","value":"hunter22"}""", ""","allow_sensitive":true""")
        assertEquals(true, r["value_ok"]!!.jsonPrimitive.boolean); assertFalse(r.containsKey("value"))
        assertTrue(scripts.isNotEmpty() && scripts.none { it.contains("v: s.slice") || it.contains("hunter22") })
        // OTP: focus moved to the next box, so the field read is another one: no retype, no verdict
        val log = mutableListOf<String>()
        val t = Doc("top", log)
        val rig = Rig(emptyList(), t)
        var box = 1
        t.focused = { """{"tag":"input","editable":true,"label":"Digit $box","type":"text","hf":true,"xp":"/html[1]/body[1]/input[$box]"}""" }
        rig.surface.onKey = { if (box < 6) box++ }
        t.value = { """{"xp":"/html[1]/body[1]/input[$box]","n":1,"h":"${djb2Hex("2")}","v":"2"}""" }
        val otp = rig.run("""{"type":"type_text","value":"424242"}""")
        assertFalse(otp.containsKey("retyped") || otp.containsKey("value_ok") || otp.containsKey("value"))
        assertEquals(1, log.count { it.endsWith("+select") })
        assertEquals(64, focusedValueScript(true).let { if (it.contains("v: s.slice(0, $VALUE_MAX)")) 64 else 0 })
        rig.dir.deleteRecursively(); c.rig.dir.deleteRecursively()
    }

    // ---- content_hash state --------------------------------------------------------------------

    @Test
    fun `content_hash moves with ARIA widget state and with focus, never with a field's content`() {
        val e = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        e.eval("""
            function El(tag,o){this.tagName=tag;this.attrs={};for(var k in o)this[k]=o[k];var d='';this.style={getPropertyValue:function(){return d;},getPropertyPriority:function(){return '';},setProperty:function(n,v){d=v;}};}
            El.prototype.getAttribute=function(n){return this.attrs.hasOwnProperty(n)?this.attrs[n]:null;};
            var sw=new El('BUTTON');sw.attrs={role:'switch','aria-checked':'false','aria-label':'Dark mode'};
            var a=new El('INPUT',{type:'text',value:'abc'});a.attrs={'aria-label':'Name'};
            var b=new El('INPUT',{type:'text',value:'xyz'});b.attrs={'aria-label':'City'};
            var body=new El('BODY');body.innerText='Settings';
            var document={body:body,documentElement:{},activeElement:body,
              querySelectorAll:function(q){if(q.indexOf('data-rpa')>=0)return [];if(q.indexOf('aria-checked')>=0)return [sw];if(q.indexOf('[tabindex]')>=0)return [sw,a,b];return [a,b];}};
            var location={hash:''};
        """.trimIndent())
        val js = contentHashScript()
        val base = e.eval(js) as String
        e.eval("sw.attrs['aria-checked']='true'")
        val toggled = e.eval(js) as String
        assertTrue(base != toggled, "a settings switch click counts")
        e.eval("document.activeElement=a")
        val focusA = e.eval(js) as String
        assertTrue(focusA != toggled, "focusing a field counts")
        e.eval("document.activeElement=b")
        assertTrue(e.eval(js) != focusA, "which field has focus counts")
        e.eval("b.value='zzz'")
        val same = e.eval(js) as String
        e.eval("b.value='qqq'")
        assertEquals(same, e.eval(js), "content of the same length is private")
        assertFalse(js.replace("el.value.length", "").replace("typeof el.value", "").contains(".value"))
    }

    @Test
    fun `frames list script reports transform scale, layout size, and why an iframe is not rendered`() {
        val e = frameDom()
        e.eval("A.offsetWidth=400;A.offsetHeight=400;A.rect=[100,50,300,250];")
        val list = TabJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(IframeInfo.serializer()), e.eval(framesListScript()) as String)
        val a = list[0]
        assertEquals(0.5, a.sx); assertEquals(100.5, a.x); assertEquals(199.0, a.w); assertEquals(398.0, a.lw)
        assertEquals("display:none", list[1].why); assertNull(a.why)
        e.eval("C.rect=[0,0,0,0];C.clientWidth=0;")
        assertEquals("zero size", TabJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(IframeInfo.serializer()), e.eval(framesListScript()) as String)[2].why)
        // the hit script maps a child point through the same scale
        assertEquals(MarkAnswer("ok", 351, 301), TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(frameHitScript(0, 500, 500)) as String))
    }

    @Test
    fun `look marks the focused element only through a chain of focused iframes`() {
        val top = FrameView.top(1000, 800, null)
        val raw = { s: String -> TabJson.decodeFromString(RawLookElement.serializer(), s) }
        val inFocus = childView(top, iframe(0, 0.0, 0.0, 400.0, 400.0).copy(act = true), 0, FakeFrame(Doc("a", mutableListOf())), "https://a.test/")
        val stale = childView(top, iframe(1, 500.0, 0.0, 400.0, 400.0), 1, FakeFrame(Doc("b", mutableListOf())), "https://b.test/")
        val placed = placeElements(
            listOf(raw(el("m1", "Search", 0, 500, 100, 20)), raw(el("m2", "Top focused", 200, 500, 100, 20, extra = ""","focused":true"""))),
            listOf(inFocus to listOf(raw(el("m1", "Phone", 10, 10, 100, 20, extra = ""","focused":true"""))), stale to listOf(raw(el("m1", "Old", 10, 10, 100, 20, extra = ""","focused":true""")))),
        ).associate { it.element.label to it.element.focused }
        assertEquals(mapOf("Search" to null, "Top focused" to true, "Phone" to true, "Old" to null), placed)
        // serialized: focused true appears, absent otherwise
        val c = Checkout()
        c.card.look = c.card.look!!.replace("\\\"xp\\\":\\\"/html[1]/body[1]/input[1]\\\"", "\\\"xp\\\":\\\"/html[1]/body[1]/input[1]\\\",\\\"focused\\\":true")
        c.top.look = c.top.look!!.replace("\\\"act\\\":false", "\\\"act\\\":true")
        c.pay.look = c.pay.look!!.replace("\\\"act\\\":false", "\\\"act\\\":true")
        val els = c.rig.run("""{"type":"look"}""")["look"]!!.jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(false, false, false, true), els.map { it["focused"]?.jsonPrimitive?.boolean ?: false })
        assertFalse(els[0].containsKey("focused"))
        assertTrue(lookElementsScript().contains("focused: !!AE && el === AE") && framesListScript().contains("act: el === document.activeElement"))
        c.rig.dir.deleteRecursively()
    }

    // ---- open shadow roots -------------------------------------------------------------------

    /** The shadow-form fixture: inputs inside crm-field shadow roots, slotted into crm-card whose own shadow root holds Save. */
    private fun shadowDom(): javax.script.ScriptEngine {
        val e = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        e.eval("""
            var NS='http://www.w3.org/1999/xhtml';
            var window={innerWidth:1000,innerHeight:800,getComputedStyle:function(e){return {display:'block',visibility:'visible',position:'static',zIndex:'auto'};}};
            function T(s){this.nodeType=3;this.nodeValue=s;this.parentNode=null;}
            function walk(n,f){for(var i=0;i<n.kids.length;i++){var c=n.kids[i];f(c);if(c.nodeType===1)walk(c,f);}}
            function qsa(n,sel){var out=[];walk(n,function(c){if(c.nodeType!==1)return;
              if(sel==='*'||(sel.indexOf('data-rpa')<0&&sel.indexOf('role=dialog')<0&&sel.indexOf('iframe')<0&&sel.indexOf('aria-checked')<0&&c.inter))out.push(c);});return out;}
            var proto={add:function(c){c.parentNode=this;c.parentElement=this.nodeType===1?this:null;this.kids.push(c);return c;},
              querySelectorAll:function(s){return qsa(this,s);},querySelector:function(){return null;}};
            function E(tag,o){this.nodeType=1;this.tagName=tag.toUpperCase();this.nodeName=this.tagName;this.localName=tag;this.namespaceURI=NS;this.attrs={};this.kids=[];
              this.parentNode=null;this.parentElement=null;this.rect=[0,0,0,0];this.inter=false;this.ctrl=false;this.innerText='';this.isConnected=true;for(var k in o)this[k]=o[k];}
            E.prototype=Object.create(proto);
            Object.defineProperty(E.prototype,'childNodes',{get:function(){return this.kids;}});
            Object.defineProperty(E.prototype,'children',{get:function(){return this.kids.filter(function(c){return c.nodeType===1;});}});
            Object.defineProperty(E.prototype,'previousSibling',{get:function(){if(!this.parentNode)return null;var k=this.parentNode.kids,i=k.indexOf(this);return i>0?k[i-1]:null;}});
            Object.defineProperty(E.prototype,'previousElementSibling',{get:function(){return null;}});
            Object.defineProperty(E.prototype,'textContent',{get:function(){var s='';walk(this,function(c){if(c.nodeType===3)s+=c.nodeValue;});return s;}});
            E.prototype.getAttribute=function(n){return this.attrs.hasOwnProperty(n)?this.attrs[n]:null;};
            E.prototype.hasAttribute=function(n){return this.attrs.hasOwnProperty(n);};
            E.prototype.getBoundingClientRect=function(){var r=this.rect;return {left:r[0],top:r[1],right:r[2],bottom:r[3],width:r[2]-r[0],height:r[3]-r[1]};};
            E.prototype.matches=function(s){if(s===':disabled')return false;if(s.indexOf('input,textarea')===0||s.indexOf('input,select,textarea')===0)return this.ctrl;
              if(s.indexOf('data-rpa')>=0||s.indexOf('role=dialog')>=0||s.indexOf('iframe')>=0||s.indexOf('aria-checked')>=0)return false;return this.inter;};
            E.prototype.closest=function(){return null;};
            E.prototype.contains=function(o){for(var n=o;n;n=n.parentNode)if(n===this)return true;return false;};
            function Root(host){this.nodeType=11;this.host=host;this.kids=[];this.activeElement=null;host.shadowRoot=this;this.at=function(){return [];};}
            Root.prototype=Object.create(proto);
            Object.defineProperty(Root.prototype,'children',{get:function(){return this.kids.filter(function(c){return c.nodeType===1;});}});
            Root.prototype.elementsFromPoint=function(x,y){return this.at(x,y);};
            function inside(el,x,y){var r=el.rect;return x>=r[0]&&x<=r[2]&&y>=r[1]&&y<=r[3];}
            var html=new E('html'),body=html.add(new E('body',{innerText:'New contact'}));
            body.add(new E('h1',{innerText:'New contact'})).add(new T('New contact'));
            var card=body.add(new E('crm-card',{rect:[0,80,400,360]}));
            function field(label,y){var h=card.add(new E('crm-field',{rect:[10,y,300,y+40]}));var r=new Root(h);
              var l=r.add(new E('label',{innerText:label}));l.add(new T(label));
              var i=r.add(new E('input',{rect:[20,y+10,220,y+30],inter:true,ctrl:true,value:''}));i.attrs={id:'i'};i.labels=[l];
              r.at=function(x,y2){return inside(i,x,y2)?[i]:[h];};return i;}
            var name=field('Full name',100),email=field('Email',160);
            var cr=new Root(card);var div=cr.add(new E('div',{innerText:'Save contact'}));
            div.add(new E('slot',{assignedElements:function(){return card.kids;}}));
            var save=div.add(new E('button',{rect:[20,300,140,330],inter:true,innerText:'Save contact'}));save.attrs={id:'save'};save.add(new T('Save contact'));
            cr.at=function(x,y){if(inside(save,x,y))return [save];var f=card.kids.filter(function(h){return inside(h,x,y);});return f.length?[f[0]]:[div];};
            var banner=null;
            var location={hash:''};
            var document={documentElement:html,body:body,baseURI:'https://a.test/',activeElement:body,
              querySelectorAll:function(s){return qsa(html,s);},getElementById:function(){return null;},elementFromPoint:function(){return null;},
              elementsFromPoint:function(x,y){if(banner&&inside(banner,x,y))return [banner];var f=card.kids.filter(function(h){return inside(h,x,y);});if(f.length)return [f[0],card];return inside(card,x,y)?[card]:[body];},
              evaluate:function(xp){var m={'/html[1]/body[1]/crm-card[1]/crm-field[1]':card.kids[0],'/html[1]/body[1]/crm-card[1]/crm-field[2]':card.kids[1],'/html[1]/body[1]/crm-card[1]':card};return {singleNodeValue:m[xp]||null};},
              createTreeWalker:function(root){var l=[];walk(root,function(c){if(c.nodeType===3)l.push(c);});var i=0;return {nextNode:function(){return l[i++]||null;}};}};
            T.prototype=null;
        """.trimIndent())
        // text nodes need a parentElement for the locate script
        e.eval("""(function fix(n){for(var i=0;i<n.kids.length;i++){var c=n.kids[i];if(c.nodeType===3){c.parentElement=n.nodeType===1?n:null;}else{fix(c);if(c.shadowRoot)fix(c.shadowRoot);}}})(html);""")
        return e
    }

    @Test
    fun `look elements, marks, covered checks and focus reach into open shadow roots`() {
        val e = shadowDom()
        val els = TabJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RawLookElement.serializer()), e.eval(lookElementsScript()) as String)
        assertEquals(listOf("Full name", "Email", "Save contact"), els.map { it.label })
        assertEquals(listOf(false, false, false), els.map { it.covered }, "the document answers with the host; the shadow root says what is on top")
        assertEquals("/html[1]/body[1]/crm-card[1]/crm-field[1]/#shadow/input[1]", els[0].xp)
        assertEquals("/html[1]/body[1]/crm-card[1]/#shadow/div[1]/button[1]", els[2].xp)
        // re-resolution by the shadow path when the kept node is gone
        e.eval("window.__rpaMarks=null;")
        val m = TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(markScript("m1", MarkRef(els[0].xp, "Full name", "input"))) as String)
        assertEquals(MarkAnswer("ok", 120, 120, "Full name", "input"), m)
        assertEquals("ok", TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(markScript("m3", MarkRef(els[2].xp, "Save contact", "button"))) as String).state)
        // something in the light DOM on top of the shadow button: covered
        e.eval("banner=new E('div',{rect:[0,290,400,340]});banner.attrs={'aria-label':'Cookie banner'};")
        val c = TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(markScript("m3", MarkRef(els[2].xp, "Save contact", "button"))) as String)
        assertEquals("covered", c.state); assertEquals("Cookie banner", c.cover)
        // focused walks down through shadowRoot.activeElement, with a shadow-aware path
        e.eval("document.activeElement=card.kids[1];card.kids[1].shadowRoot.activeElement=email;")
        val f = e.eval(focusedScript(false)) as String
        assertEquals("Email", TabJson.decodeFromString(FocusedInfo.serializer(), f).label)
        assertEquals("/html[1]/body[1]/crm-card[1]/crm-field[2]/#shadow/input[1]", TabJson.decodeFromString(FocusExtra.serializer(), f).xp)
        assertEquals(MarkFocus(can = true, f = true), TabJson.decodeFromString(MarkFocus.serializer(), e.eval(markFocusScript("m2", MarkRef(els[1].xp, "Email", "input"))) as String))
        // the snap script finds the shadow input under the host and does not move
        assertFalse(TabJson.decodeFromString(SnapAnswer.serializer(), e.eval(snapScript(120, 120, SnapBox(100.0, 100.0, 140.0, 140.0))) as String).moved)
    }

    @Test
    fun `page_text, locate_text and content_hash read open shadow roots`() {
        val e = shadowDom()
        val text = e.eval(pageTextScript(1000)) as String
        assertTrue(text.startsWith("New contact") && text.contains("Save contact") && text.contains("Full name") && text.contains("Email"), text)
        val loc = TabJson.decodeFromString(LocatedInfo.serializer(), e.eval(locateTextScript("Save contact")) as String)
        assertTrue(loc.found && loc.inView)
        assertEquals(LocatedRect(20, 300, 120, 30), loc.rect)
        val h0 = e.eval(contentHashScript()) as String
        e.eval("name.value='Asha'")
        assertTrue(h0 != e.eval(contentHashScript()), "typing into a shadow input moves the hash")
        val h1 = e.eval(contentHashScript()) as String
        e.eval("document.activeElement=card.kids[0];card.kids[0].shadowRoot.activeElement=name;")
        assertTrue(h1 != e.eval(contentHashScript()), "focusing a shadow input moves the hash")
    }

    // ---- hover_at ------------------------------------------------------------------------------

    @Test
    fun `hover_at moves the real mouse there after the glide, quiet-waits, and the next look moves nothing`() {
        val c = Checkout()
        c.log.clear()
        val r = c.rig.run("""{"type":"hover_at","value":"120,40"}""", ""","pointer":{"label":"Jev"}""")
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        assertEquals(listOf<TabEvent>(TabEvent.Move(120, 40)), c.rig.surface.events)
        val glide = c.top.scripts.indexOfFirst { it.contains("__rpa_pointer") && it.contains("root.style.transform") }
        assertTrue(glide >= 0)
        val log = c.log
        assertTrue(log.indexOf("ev:Move") < log.indexOf("top:quiet") && log.indexOf("ev:Move") > 0, log.toString())
        assertFalse(log.contains("focus"), "a hover does not take focus")
        // no ripple for a hover
        assertFalse(c.top.scripts[glide].contains("animate("))
        // what opened stays open: look, screenshot and page_text dispatch no input
        c.rig.run("""{"type":"look"}"""); c.rig.run("""{"type":"screenshot"}"""); c.rig.run("""{"type":"page_text"}""")
        assertEquals(1, c.rig.surface.events.size)
        // by element, inside a frame: resolved there, mapped to the top point
        c.card.mark = """{"state":"ok","x":185,"y":15,"label":"1234 1234 1234 1234","tag":"input"}"""
        c.pay.hit = """{"state":"ok","x":202,"y":55}"""
        c.top.hit = """{"state":"ok","x":303,"y":256}"""
        val h = c.rig.run("""{"type":"hover_at","element":"m4"}""")
        assertEquals(TabEvent.Move(303, 256), c.rig.surface.events.last())
        assertEquals("m4", h["hovered"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(c.rig.surface.events.any { it is TabEvent.Press || it is TabEvent.Release })
        c.rig.dir.deleteRecursively()
    }

    @Test
    fun `hover_at needs a point or an element, and element stays limited to pointing verbs`() {
        val step = { a: String -> """{"tab_id":"t","action":$a}""" }
        assertEquals("m3", parseStepArgs(step("""{"type":"hover_at","element":"m3"}""")).element)
        assertEquals("5,6", parseStepArgs(step("""{"type":"hover_at","value":"5,6"}""")).action.value)
        for (bad in listOf("""{"type":"hover_at"}""", """{"type":"hover_at","value":"x"}""", """{"type":"key","value":"Enter","element":"m1"}""")) {
            val ex = runCatching { parseStepArgs(step(bad)) }.exceptionOrNull()
            assertTrue(ex is TabToolException && ex.code == TabErrorCodes.INVALID_INPUT, bad)
        }
        assertTrue(CuTypes.HOVER_AT in STEP_ALLOWED_TYPES && TabActions.STEP_SCHEMA.contains("\"hover_at\""))
    }
}
