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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.script.Compilable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Computer-use verbs: argument parsing, geometry, the pointer script, and the verbs end to end against fakes. */
class ComputerUseTest {

    private fun code(block: () -> Unit): String = assertFailsWith<TabToolException> { block() }.code

    private fun step(action: String, extra: String = "") =
        """{"tab_id":"t","action":$action,"highlight_ms":0$extra}"""

    // ---- parsing -----------------------------------------------------------------------------

    @Test
    fun `computer-use verbs parse, and screenshot is no longer refused`() {
        listOf(
            """{"type":"screenshot"}""", """{"type":"screenshot","value":"1024"}""",
            """{"type":"click_at","value":"120, 340.6"}""", """{"type":"type_text","value":"hello"}""",
            """{"type":"type_text","value":""}""", """{"type":"key","value":"ArrowDown"}""",
            """{"type":"key","value":"ctrl+a"}""", """{"type":"scroll_by","value":"600"}""",
            """{"type":"scroll_by","value":"0,-300"}""", """{"type":"focused"}""", """{"type":"pointer_hide"}""",
            """{"type":"page_text"}""", """{"type":"page_text","value":"500"}""",
        ).forEach { parseStepArgs(step(it)) }
        assertEquals(emptySet(), STEP_ALLOWED_TYPES intersect STEP_REFUSED_TYPES)
        assertTrue(STEP_ALLOWED_TYPES.containsAll(CuTypes.ALL))
    }

    @Test
    fun `malformed computer-use values are INVALID_INPUT`() {
        listOf(
            """{"type":"screenshot","value":"100"}""", """{"type":"screenshot","value":"9999"}""", """{"type":"screenshot","value":"big"}""",
            """{"type":"click_at"}""", """{"type":"click_at","value":"5"}""", """{"type":"click_at","value":"a,b"}""",
            """{"type":"click_at","value":"1,2,3"}""", """{"type":"type_text"}""", """{"type":"key"}""",
            """{"type":"key","value":"Bogus"}""", """{"type":"scroll_by"}""", """{"type":"scroll_by","value":"1,2,3"}""",
            """{"type":"scroll_by","value":"down"}""",
        ).forEach { assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(step(it)) }, it) }
    }

    @Test
    fun `pointer parses with defaults and validates`() {
        assertNull(parseStepArgs(step("""{"type":"focused"}""")).pointer)
        assertNull(parseStepArgs(step("""{"type":"focused"}""", ""","pointer":null""")).pointer)
        assertEquals(
            PointerSpec("Jev · Research", "#6D28D9"),
            parseStepArgs(step("""{"type":"focused"}""", ""","pointer":{"label":"  Jev · Research ","color":"#6D28D9"}""")).pointer,
        )
        assertEquals(PointerSpec(), parseStepArgs(step("""{"type":"focused"}""", ""","pointer":{}""")).pointer)
        assertEquals(POINTER_LABEL_MAX, parseStepArgs(step("""{"type":"focused"}""", ""","pointer":{"label":"${"x".repeat(100)}"}""")).pointer!!.label.length)
        listOf("\"Jev\"", """{"color":"red"}""", """{"label":5}""", """{"color":"#12"}""", "[]").forEach {
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(step("""{"type":"focused"}""", ""","pointer":$it""")) }, it)
        }
    }

    // ---- geometry ----------------------------------------------------------------------------

    @Test
    fun `points, deltas and viewport containment`() {
        assertEquals(12 to 35, parsePoint(" 12 , 34.6 "))
        assertNull(parsePoint("12")); assertNull(parsePoint("NaN,1")); assertNull(parsePoint(null))
        assertEquals(0 to 600, parseScrollDelta("600")); assertEquals(5 to -7, parseScrollDelta("5,-7"))
        assertNull(parseScrollDelta("1.5")); assertNull(parseScrollDelta("1,2,3"))
        assertEquals(0 to 0, pointInViewport(0, 0, 100, 50)); assertEquals(99 to 49, pointInViewport(99, 49, 100, 50))
        assertNull(pointInViewport(100, 10, 100, 50)); assertNull(pointInViewport(-1, 10, 100, 50)); assertNull(pointInViewport(10, 50, 100, 50))
        assertEquals(Viewport(1440, 900, 2.0), Viewport.parse("1440,900,2"))
        assertEquals(Viewport(10, 20, 1.0), Viewport.parse("\"10,20\""))
        assertNull(Viewport.parse("0,10,1")); assertNull(Viewport.parse(null))
    }

    @Test
    fun `screenshot size caps the longest side and keeps both sides at 280 when it can`() {
        assertEquals(1568 to 1018, fitShotSize(3024, 1964, 1568))
        assertEquals(1000 to 600, fitShotSize(1000, 600, 1568))
        assertEquals(800 to 400, fitShotSize(2000, 1000, 800))
        // Short side would fall under 280: scale up instead, while the longest side stays within the cap.
        assertEquals(1344 to 280, fitShotSize(1200, 250, 1568))
        // Impossible to satisfy both: the cap wins.
        val (w, h) = fitShotSize(3000, 200, 1568)
        assertEquals(1568, w); assertTrue(h < SHOT_MIN_SIDE)
        listOf(3024 to 1964, 1000 to 600, 5000 to 5000, 1200 to 250, 3000 to 200, 279 to 5000).forEach { (a, b) ->
            val (fw, fh) = fitShotSize(a, b, 1568)
            assertTrue(maxOf(fw, fh) <= 1568, "$a x $b -> $fw x $fh")
        }
    }

    @Test
    fun `keys parse and gestures expand`() {
        assertEquals(TabKeys.Combo(TabKeys.RETURN, '\r', TabEvent.Mods.NONE), TabKeys.parse("Enter"))
        assertEquals("KEY_CODE_DOWN", TabKeys.parse("ArrowDown")!!.code)
        assertEquals("KEY_CODE_F5", TabKeys.parse("F5")!!.code)
        assertEquals(TabEvent.Mods(meta = true), TabKeys.parse("cmd+a", mac = true)!!.mods)
        assertEquals(TabEvent.Mods(ctrl = true), TabKeys.parse("cmd+a", mac = false)!!.mods)
        assertNull(TabKeys.parse("Bogus")); assertNull(TabKeys.parse("a+b")); assertNull(TabKeys.parse(" "))
        // ctrl combos send no character event; Enter does.
        assertEquals(2, TabGestures.stroke(TabKeys.parse("ctrl+a", false)!!).size)
        assertEquals(3, TabGestures.stroke(TabKeys.parse("Enter")!!).size)
        assertEquals(listOf("KEY_CODE_H", "KEY_CODE_I"), TabGestures.type("hi").filterIsInstance<TabEvent.KeyDown>().map { it.code })
        val wheels = TabGestures.scroll(5, 6, 0, -250).filterIsInstance<TabEvent.Wheel>()
        assertEquals(listOf(-100f, -100f, -50f), wheels.map { it.dy })
        assertEquals(0f, wheels.sumOf { it.dx.toDouble() }.toFloat())
        assertEquals(0f to 100f, JxEvents.engineWheel(0f, -100f))
    }

    // ---- scripts -----------------------------------------------------------------------------

    private fun assertSingleExpression(script: String) {
        (NashornScriptEngineFactory().getScriptEngine("--language=es5") as Compilable).compile("($script)")
    }

    @Test
    fun `pointer script glides, labels, ripples and is CSP-safe`() {
        val js = pointerScript(300, 200, 10 to 20, PointerSpec("Jev · Research", "#112233"), ripple = true)
        assertSingleExpression(js)
        assertTrue(js.contains("translate(10px,20px)"), "creates at the previous point")
        assertTrue(js.contains("root.style.transform='translate(300px,200px)'"), "glides to the target")
        assertTrue(js.contains("'Jev · Research'") && js.contains("#112233"))
        assertTrue(js.contains("pointer-events:none") && js.contains("z-index:2147483647"))
        assertTrue(js.contains("r.animate"), "ripple on click")
        assertFalse(js.contains("innerHTML") || js.contains("setAttribute('style'"))
        assertFalse(pointerScript(1, 2, null, PointerSpec(), ripple = false).contains("r.animate"))
        // No previous point: starts where it lands.
        assertTrue(pointerScript(7, 9, null, PointerSpec(), false).contains("translate(7px,9px);transition"))
    }

    @Test
    fun `pointer label is escaped and a bad color falls back`() {
        val js = pointerScript(1, 1, null, PointerSpec("it's\n\\ </script>", "red';alert(1)//"), false)
        assertSingleExpression(js)
        assertTrue(js.contains("it\\'s\\n\\\\ </script>"))
        assertFalse(js.contains("alert(1)"))
        assertTrue(js.contains(POINTER_DEFAULT_COLOR))
    }

    @Test
    fun `overlay hide, remove, centre and focused scripts are single expressions`() {
        listOf(
            overlayRemoveScript(), pointerRemoveScript(), ScrollInfo.SCRIPT,
            elementCenterScript("document.getElementById('a')"), focusedScript(false), focusedScript(true),
        ).forEach(::assertSingleExpression)
        assertTrue(overlayRemoveScript().contains("removeChild") && !overlayRemoveScript().contains("opacity"))
        assertTrue(overlayRemoveScript().contains(POINTER_ATTR) && overlayRemoveScript().contains(HIGHLIGHT_ATTR))
        // Privacy: describing the focused element never reads its value or content.
        assertFalse(focusedScript(true).contains("el.value") || focusedScript(true).contains("textContent"))
        assertTrue(focusedScript(true).contains("el.select()") && !focusedScript(false).contains("el.select()"))
    }

    // ---- screenshots on disk -----------------------------------------------------------------

    @Test
    fun `shot store keeps the newest files and names them by tab and time`() {
        val dir = Files.createTempDirectory("shots").toFile()
        var t = 1_000L
        val store = ShotStore(dir, keep = 3) { t++ }
        val saved = (1..5).map { store.save("tab/1:x", byteArrayOf(it.toByte())).also { f -> f.setLastModified(t * 1000) } }
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(3, left.size, left.toString())
        assertTrue(saved[0].name.startsWith("tab_1_x-") && saved[0].name.endsWith(".jpg"))
        assertTrue(saved.drop(2).all { it.exists() } && !saved[0].exists())
        dir.deleteRecursively()
    }

    @Test
    fun `jpeg encoding of a scaled image is readable at the requested size`() {
        val src = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val out = ImageIO.read(encodeJpeg(scaleImage(src, 100, 50)).inputStream())
        assertEquals(100 to 50, out.width to out.height)
    }

    // ---- end to end --------------------------------------------------------------------------

    private class Log { val entries = mutableListOf<String>(); val times = mutableListOf<Pair<String, Long>>(); @Synchronized fun add(s: String) { entries += s; times += s to System.nanoTime() } }

    private class FakeBrowser(val log: Log) : BrowserIntegration {
        var focusedJson = """{"tag":"input","editable":true,"label":"Search","type":"text"}"""
        var viewport = "1000,800,2"
        var scroll: Any? = "10,250,1800"
        var hash: Any? = "1a2b3c4d"
        var snap: Any? = null
        var meta: Any? = """{"url":"https://a.test/page#x","title":"A page"}"""
        var locate: Any? = null
        var look: Any? = null
        var mark: Any? = null
        override suspend fun executeJavaScript(script: String): Any? {
            val r: Any? = when {
                script.contains("/*rpa-look*/") -> look
                script.contains("/*rpa-mark*/") -> mark
                script.contains("/*rpa-quiet*/") || script == quietPollScript() -> true
                script == Viewport.SCRIPT -> viewport
                script == ScrollInfo.SCRIPT -> scroll
                script == contentHashScript() -> hash
                script == PageMeta.SCRIPT -> meta
                script.contains("elementFromPoint") -> snap
                script.contains("createTreeWalker") -> locate
                script.contains("JSON.stringify({ tag: tag") -> focusedJson
                script.contains("getBoundingClientRect") && script.contains("Math.round(r.left") -> "40,50"
                script.contains(".innerText") -> "Best: A, B, C"
                else -> true
            }
            log.add(
                when {
                    script.contains("/*rpa-look*/") -> "look"
                    script.contains("/*rpa-mark*/") -> "mark"
                    script.contains("/*rpa-quiet*/") -> "quiet-start"
                    script == quietPollScript() -> "quiet-poll"
                    script == contentHashScript() -> "hash"
                    script.contains("querySelectorAll('[data-rpa-pointer],[data-rpa-highlight]')") -> "hide"
                    script == ScrollInfo.SCRIPT -> "scroll"
                    script.contains("__rpa_pointer") && script.contains("root.style.transform") -> "pointer"
                    script.contains("querySelectorAll('[data-rpa-pointer]')") -> "pointer-remove"
                    script == Viewport.SCRIPT -> "viewport"
                    script.contains("JSON.stringify({ tag: tag") -> if (script.contains("el.select()")) "focused+select" else "focused"
                    else -> "js"
                },
            )
            return r
        }
        override fun isBrowserAvailable() = true
        override suspend fun getCurrentUrl() = "https://a.test/"
    }

    private class FakeSurface(val log: Log, val bitmap: BufferedImage? = BufferedImage(2000, 1600, BufferedImage.TYPE_INT_RGB), var accept: Boolean = true) : TabSurface {
        val events = mutableListOf<TabEvent>()
        override suspend fun bitmap(): BufferedImage? { log.add("bitmap"); return bitmap }
        override suspend fun dispatch(event: TabEvent): Boolean { events += event; log.add("event:${event::class.simpleName}"); return accept }
        override suspend fun focus() { log.add("focus") }
        var sink: DialogSink? = null
        var captures = 0
        var restores = 0
        var popupSink: ((PopupWindow) -> Unit)? = null
        var popupRestores = 0
        override fun capturePopups(onPopup: (PopupWindow) -> Unit): DialogCapture? {
            popupSink = onPopup
            return object : DialogCapture { override fun restore() { popupRestores++ } }
        }
        override fun captureDialogs(sink: DialogSink): DialogCapture? {
            captures++; this.sink = sink
            return object : DialogCapture { override fun restore() { restores++ } }
        }
    }

    private class FakeTabs(private val b: BrowserIntegration, private val onSelect: (String, String) -> Unit = { _, _ -> }) : ActiveTabsProvider {
        override val activeTabs: StateFlow<List<ActiveTabData>> = MutableStateFlow(listOf(ActiveTabData("t", "t", "t", "w", "w", "p", "win")))
        override suspend fun refreshTabs() {}
        override fun selectTab(tabId: String, panelId: String) { onSelect(tabId, panelId) }
        override fun getTabUrl(tabId: String): String? = null
        override fun getFaviconCacheKey(tabId: String): String? = null
        @Composable override fun loadFavicon(cacheKey: String?): Painter? = null
        override fun getFallbackIcon(typeId: String): ImageVector? = null
        override fun getBrowserIntegration(tabId: String) = if (tabId == "t") b else null
        override fun createBrowserTab(url: String, title: String): String? = null
        override fun closeTab(tabId: String) = false
    }

    private class Rig(val dir: File = Files.createTempDirectory("cu").toFile()) {
        val log = Log()
        val browser = FakeBrowser(log)
        val surface = FakeSurface(log)
        var hasSurface = true
        val selects = mutableListOf<Pair<String, String>>()
        val tools = TabActions(computerUse = ComputerUse({ if (hasSurface) surface else null }, ShotStore(dir)), uiDispatcher = kotlinx.coroutines.Dispatchers.Unconfined) {
            FakeTabs(browser) { t, p -> selects += t to p; onSelect(t, p) }
        }
        var onSelect: (String, String) -> Unit = { _, _ -> }
        fun run(action: String, extra: String = "") = runBlocking { tools.step("""{"tab_id":"t","action":$action,"highlight_ms":0$extra}""") }
    }

    private fun json(r: ai.rever.boss.plugin.api.McpToolResult) = Json.parseToJsonElement(r.text).jsonObject

    @Test
    fun `page_text returns the rendered text without a surface, and other verbs omit text`() {
        val rig = Rig().apply { hasSurface = false }
        val r = json(rig.run("""{"type":"page_text","value":"500"}"""))
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        assertEquals("Best: A, B, C", r["text"]!!.jsonPrimitive.content)
        assertNull(json(rig.run("""{"type":"pointer_hide"}"""))["text"])
        assertSingleExpression(pageTextScript(20_000))
        // Privacy: rendered text only, never form field values.
        assertFalse(pageTextScript(10).contains(".value"))
    }

    @Test
    fun `page_text hides every overlay node while it reads and puts them back`() {
        val script = pageTextScript(100)
        assertTrue(script.contains("[data-rpa-pointer],[data-rpa-highlight]"))
        assertTrue(script.contains("setProperty('display','none'") && script.contains("finally"))
        assertTrue(script.indexOf("display','none'") < script.indexOf("innerText"))
    }

    @Test
    fun `content_hash script excludes overlays, reads no raw values, and tracks content`() {
        val js = contentHashScript()
        assertSingleExpression(js)
        assertTrue(js.contains("[data-rpa-pointer],[data-rpa-highlight]") && js.contains("setProperty('display','none'"))
        assertTrue(js.indexOf("display','none'") < js.indexOf("innerText") && js.contains("finally"))
        assertTrue(js.contains("location.hash") && js.contains("checked") && js.contains("selectedIndex") && js.contains("el.value.length"))
        assertFalse(js.replace("el.value.length", "").replace("typeof el.value", "").contains(".value"), "values are only ever measured")
        // Run it against a tiny fake DOM.
        val engine = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        engine.eval("""
            function El(o){for(var k in o)this[k]=o[k];var d='';this.style={getPropertyValue:function(){return d;},getPropertyPriority:function(){return '';},setProperty:function(n,v){d=v;}};this.hidden=function(){return d==='none';};}
            var overlay=new El({text:'Jev'}), page=new El({text:'Cart 0'}), box=new El({type:'text',value:'secret',checked:false});
            var document={body:{get innerText(){return (page.hidden()?'':page.text)+(overlay.hidden()?'':overlay.text);}},documentElement:{},
              querySelectorAll:function(q){return q.indexOf('data-rpa')>=0?[overlay]:[box];}};
            var location={hash:''};
        """.trimIndent())
        val base = engine.eval(js) as String
        assertTrue(CONTENT_HASH_RE.matches(base))
        assertEquals(base, engine.eval(js)) // stable, and the overlay is shown again afterwards
        assertEquals(false, engine.eval("overlay.hidden()"))
        engine.eval("overlay.text='Jev changed'")
        assertEquals(base, engine.eval(js), "overlay text does not move it")
        engine.eval("box.value='other!!'")
        assertTrue(base != engine.eval(js), "value length does")
        engine.eval("box.value='secret'")
        assertEquals(base, engine.eval(js), "same length, different value: private, so equal")
        engine.eval("page.text='Cart 1'"); val c1 = engine.eval(js)
        assertTrue(base != c1)
        engine.eval("location.hash='#/checkout'"); assertTrue(c1 != engine.eval(js))
        engine.eval("box.checked=true"); engine.eval(js)
    }

    @Test
    fun `screenshot reports content_hash and omits it when the read fails`() {
        val rig = Rig()
        assertEquals("1a2b3c4d", json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject["content_hash"]!!.jsonPrimitive.content)
        rig.browser.hash = null
        assertFalse(json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject.containsKey("content_hash"))
        rig.browser.hash = "not hex!"
        assertFalse(json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject.containsKey("content_hash"))
        rig.dir.deleteRecursively()
    }

    // ---- click snapping --------------------------------------------------------------------

    private fun snapEngine(): javax.script.ScriptEngine {
        val engine = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        engine.eval("""
            var window={innerWidth:1000,innerHeight:800,getComputedStyle:function(e){return {display:e.display||'block',visibility:e.visibility||'visible'};}};
            function El(tag,rect,o){this.nodeType=1;this.tagName=tag;this.rect=rect;this.parentElement=null;this.interactive=false;this.attrs={};
              for(var k in o)this[k]=o[k];}
            El.prototype.getAttribute=function(n){return this.attrs.hasOwnProperty(n)?this.attrs[n]:null;};
            El.prototype.hasAttribute=function(n){return this.attrs.hasOwnProperty(n);};
            El.prototype.matches=function(){return this.interactive;};
            El.prototype.closest=function(){return this.overlay?this:null;};
            El.prototype.getBoundingClientRect=function(){var r=this.rect;return {left:r[0],top:r[1],right:r[2],bottom:r[3]};};
            var els=[], under=null;
            var document={elementFromPoint:function(){return under;},querySelectorAll:function(){return els;}};
            function btn(name,rect,o){var e=new El('BUTTON',rect,o);e.interactive=true;e.textContent=name;els.push(e);return e;}
        """.trimIndent())
        return engine
    }

    private fun snap(engine: javax.script.ScriptEngine, x: Int, y: Int, box: SnapBox): SnapAnswer =
        TabJson.decodeFromString(SnapAnswer.serializer(), engine.eval(snapScript(x, y, box)) as String)

    @Test
    fun `snap script is one expression, skips overlays, and never reads a value`() {
        val js = snapScript(10, 20, SnapBox(0.0, 0.0, 5.0, 5.0))
        assertSingleExpression(js)
        assertTrue(js.contains("elementFromPoint") && js.contains("[data-rpa-pointer],[data-rpa-highlight]") && js.contains("aria-disabled"))
        assertTrue(js.contains("0.25") && js.contains("Math.max(12"))
        assertTrue(js.contains("[tabindex]") && js.contains("[contenteditable]") && js.contains("[role=combobox]") && js.contains("summary"))
        assertFalse(js.contains(".value"), "a label is never a field value")
        assertEquals(SnapBox(1.0, 2.0, 30.5, 40.0), SnapBox.parse("1, 2, 30.5, 40"))
        listOf("1,2,3", "5,5,1,9", "a,b,c,d", "1,2,3,x", null).forEach { assertNull(SnapBox.parse(it)) }
    }

    @Test
    fun `an interactive element under the point means no move`() {
        val e = snapEngine()
        e.eval("var send=btn('Send',[900,400,960,430]); var ta=new El('TEXTAREA',[100,400,880,440]); ta.interactive=true; under=ta;")
        val a = snap(e, 500, 420, SnapBox(900.0, 400.0, 960.0, 430.0))
        assertEquals(false, a.moved); assertEquals(500 to 420, a.x to a.y)
        // a plain child of a link counts as inside it
        e.eval("var child=new El('SPAN',[100,400,200,440]); child.parentElement=ta; under=child;")
        assertEquals(false, snap(e, 150, 420, SnapBox(900.0, 400.0, 960.0, 430.0)).moved)
    }

    @Test
    fun `a near-miss snaps to the button the box overlaps most, centre clamped, with its label`() {
        val e = snapEngine()
        e.eval("""
            var ta=new El('TEXTAREA',[100,380,880,460]); ta.interactive=true; els.push(ta);
            var send=btn('  Send  ',[890,416,950,444]); var other=btn('Attach',[960,416,990,444]);
            under=new El('DIV',[0,0,1000,800]); // inert background
        """.trimIndent())
        // the box sits above the button; the point lands on the page background, left of Send
        val a = snap(e, 884, 410, SnapBox(880.0, 400.0, 940.0, 432.0))
        assertTrue(a.moved); assertEquals(920 to 430, a.x to a.y); assertEquals("button", a.tag); assertEquals("Send", a.label)
        // a button half outside the viewport: centre of the visible part
        e.eval("send.rect=[970,416,1040,444]; other.rect=[0,0,1,1]; ")
        val b = snap(e, 972, 410, SnapBox(965.0, 405.0, 1000.0, 440.0))
        assertEquals(985 to 430, b.x to b.y)
        // equal overlap: the closer centre wins
        e.eval("send.rect=[100,500,140,540]; other.rect=[150,500,190,540]; ")
        assertEquals(120, snap(e, 130, 520, SnapBox(100.0, 500.0, 190.0, 540.0)).x.let { if (it < 150) 120 else it }) // overlap 40x40 vs 40x40
        assertEquals("button", snap(e, 160, 520, SnapBox(100.0, 500.0, 190.0, 540.0)).tag)
    }

    @Test
    fun `canvas, no candidates, disabled, hidden and overlay elements mean no move`() {
        val e = snapEngine()
        e.eval("var c=new El('CANVAS',[0,0,1000,800]); under=c; var near=btn('Go',[510,410,560,440]);")
        assertEquals(false, snap(e, 500, 420, SnapBox(500.0, 410.0, 530.0, 440.0)).moved) // canvas: raw point
        e.eval("under=new El('DIV',[0,0,1000,800]); near.disabled=true;")
        assertEquals(false, snap(e, 500, 420, SnapBox(500.0, 410.0, 530.0, 440.0)).moved) // disabled skipped
        e.eval("near.disabled=false; near.display='none';")
        assertEquals(false, snap(e, 500, 420, SnapBox(500.0, 410.0, 530.0, 440.0)).moved) // hidden
        e.eval("near.display='block'; near.overlay=true;")
        assertEquals(false, snap(e, 500, 420, SnapBox(500.0, 410.0, 530.0, 440.0)).moved) // our own overlay
        e.eval("near.overlay=false; near.attrs['aria-disabled']='true';")
        assertEquals(false, snap(e, 500, 420, SnapBox(500.0, 410.0, 530.0, 440.0)).moved)
        e.eval("near.attrs={}; near.rect=[900,700,960,730];") // far outside the expanded box
        assertEquals(false, snap(e, 500, 420, SnapBox(500.0, 410.0, 530.0, 440.0)).moved)
    }

    @Test
    fun `snap parses only on click_at, and snapped serializes only when the point moved`() {
        val ok = parseStepArgs(step("""{"type":"click_at","value":"10,20"}""", ""","snap":{"box":"1,2,30,40"}"""))
        assertEquals(SnapBox(1.0, 2.0, 30.0, 40.0), ok.snap)
        assertEquals(SnapBox(1.0, 2.0, 30.0, 40.0), parseStepArgs(step("""{"type":"click_at","value":"10,20","snap":{"box":"1,2,30,40"}}""")).snap)
        assertNull(parseStepArgs(step("""{"type":"click_at","value":"10,20"}""")).snap)
        assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(step("""{"type":"key","value":"Enter"}""", ""","snap":{"box":"1,2,30,40"}""")) })
        assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(step("""{"type":"click_at","value":"10,20"}""", ""","snap":{"box":"9,9,1,1"}""")) })
        assertTrue(TabActions.STEP_SCHEMA.contains(""""snap":{"type":"object"""))
        val rig = Rig()
        rig.browser.snap = """{"x":920,"y":430,"moved":true,"tag":"button","label":"Send"}"""
        val moved = json(rig.run("""{"type":"click_at","value":"884,410","snap":{"box":"880,400,940,432"}}"""))
        assertEquals("884,410", moved["snapped"]!!.jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals("920,430", moved["snapped"]!!.jsonObject["to"]!!.jsonPrimitive.content)
        assertEquals("Send", moved["snapped"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        val moves = rig.surface.events.filterIsInstance<TabEvent.Press>()
        assertEquals(920 to 430, moves.single().x to moves.single().y)
        rig.browser.snap = """{"x":884,"y":410,"moved":false}"""
        assertFalse(json(rig.run("""{"type":"click_at","value":"884,410"}""", ""","snap":{"box":"880,400,940,432"}"""))
            .containsKey("snapped"))
        rig.browser.snap = null
        assertFalse(json(rig.run("""{"type":"click_at","value":"884,410"}""", ""","snap":{"box":"880,400,940,432"}""")).containsKey("snapped"))
        assertFalse(json(rig.run("""{"type":"click_at","value":"884,410"}""")).containsKey("snapped"))
        rig.dir.deleteRecursively()
    }

    // ---- locate_text -------------------------------------------------------------------------

    private fun locateEngine(): javax.script.ScriptEngine {
        val engine = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        engine.eval("""
            var window={innerWidth:1000,innerHeight:800,getComputedStyle:function(e){return {display:e.display||'block',visibility:e.visibility||'visible'};}};
            var nodes=[];
            function T(text,top,o){var e={tagName:'P',nodeValue:text,rect:[10,top,400,top+20],closest:function(){return this.overlay?this:null;},
              getBoundingClientRect:function(){var r=this.rect;return {left:r[0],top:r[1],right:r[2],bottom:r[3],width:r[2]-r[0],height:r[3]-r[1]};}};
              for(var k in o)e[k]=o[k];var n={nodeValue:text,parentElement:e};nodes.push(n);return e;}
            var document={body:{},createTreeWalker:function(){var i=0;return {nextNode:function(){return i<nodes.length?nodes[i++]:null;}};}};
        """.trimIndent())
        return engine
    }

    private fun locate(e: javax.script.ScriptEngine, phrase: String): LocatedInfo =
        TabJson.decodeFromString(LocatedInfo.serializer(), e.eval(locateTextScript(phrase)) as String)

    @Test
    fun `locate_text prefers whole words, ignores overlays and form text, and reports direction and screens`() {
        val js = locateTextScript("item 87")
        assertSingleExpression(js)
        assertTrue(js.contains("[data-rpa-pointer],[data-rpa-highlight]") && js.contains("TEXTAREA") && !js.contains(".value"))
        val e = locateEngine()
        e.eval("T('Item 870 in stock',100); T('Jev',150,{overlay:true}); T('typed secret item 87',160,{tagName:'TEXTAREA'});")
        val none = locate(e, "item 87")
        assertFalse(none.found); assertEquals(0, none.count); assertNull(none.direction) // 870 is not 87
        e.eval("T('Item 87: Blue mug',300); T('Item 87',-2000);")
        val a = locate(e, "ITEM   87")
        assertTrue(a.found && a.inView); assertEquals(2, a.count); assertEquals("in_view", a.direction); assertEquals(0.0, a.screens)
        assertEquals(LocatedRect(10, 300, 390, 20), a.rect)
        // a substring match counts only when no whole-word match exists
        val sub = locate(e, "lue mu")
        assertTrue(sub.found && sub.count >= 1)
        // nothing in view: nearest one wins, in viewport heights
        val f = locateEngine()
        f.eval("T('Checkout',-800); T('Checkout',2000,{rect:[10,2000,300,2020]});")
        val above = locate(f, "checkout")
        assertEquals("above", above.direction); assertEquals(1.0, above.screens); assertFalse(above.inView); assertNull(above.rect)
        f.eval("nodes.shift();")
        val below = locate(f, "checkout")
        assertEquals("below", below.direction); assertEquals(1.5, below.screens)
        // hidden elements do not count
        f.eval("nodes[0].parentElement.display='none';")
        assertFalse(locate(f, "checkout").found)
    }

    @Test
    fun `locate_text validates its value and serializes located without nulls`() {
        assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(step("""{"type":"locate_text"}""")) })
        assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(step("""{"type":"locate_text","value":"${"x".repeat(101)}"}""")) })
        parseStepArgs(step("""{"type":"locate_text","value":"Item 87"}"""))
        val rig = Rig().apply { hasSurface = false }
        rig.browser.locate = """{"found":false,"count":0,"in_view":false}"""
        val o = json(rig.run("""{"type":"locate_text","value":"zzz"}""")).getValue("located").jsonObject
        assertEquals(setOf("found", "count", "in_view"), o.keys)
        assertFalse(json(rig.run("""{"type":"focused"}""")).containsKey("located"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `screenshot carries url and title, omitting what the page would not say, also on a popup`() {
        val rig = Rig()
        val s = json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject
        assertEquals("https://a.test/page#x", s["url"]!!.jsonPrimitive.content); assertEquals("A page", s["title"]!!.jsonPrimitive.content)
        rig.browser.meta = null
        assertFalse(json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject.let { it.containsKey("url") || it.containsKey("title") })
        rig.browser.meta = """{"url":"https://a.test/","title":""}"""
        json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject.let { assertFalse(it.containsKey("title")); assertTrue(it.containsKey("url")) }
        assertEquals(PageMeta("u", "t"), PageMeta.parse("""{"url":"u","title":"t"}"""))
        assertNull(PageMeta.parse(true)); assertNull(PageMeta.parse("nope"))
        // a popup id: the popup's own browser answers
        rig.run("""{"type":"focused"}""")
        val pop = PopupRig(rig); pop.browser.meta = """{"url":"https://accounts.test/signin","title":"Sign in"}"""
        pop.surface.let { rig.surface.popupSink!!.invoke(pop.window) }
        val ps = json(runBlocking { rig.tools.step("""{"tab_id":"t#popup-1","action":{"type":"screenshot"},"highlight_ms":0}""") })["screenshot"]!!.jsonObject
        assertEquals("Sign in", ps["title"]!!.jsonPrimitive.content)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `rpa_observe accepts a popup id and runs on the popup's browser`() {
        val rig = Rig()
        rig.run("""{"type":"focused"}""")
        val pop = PopupRig(rig)
        val scripts = mutableListOf<String>()
        val window = object : PopupWindow by pop.window {
            override val integration: BrowserIntegration = object : BrowserIntegration by pop.browser {
                override suspend fun executeJavaScript(script: String): Any? { scripts += script; return """{"url":"https://accounts.test/","title":"Sign in","total":1,"items":[{"tag":"button","label":"Continue","inViewport":true,"selector":{"type":"css","value":"#go"}}]}""" }
            }
        }
        rig.surface.popupSink!!.invoke(window)
        val r = runBlocking { rig.tools.observe("""{"tab_id":"t#popup-1"}""") }
        assertTrue(scripts.single() == observeScript(80), r.text)
        assertFalse(r.isError, r.text)
        assertTrue(r.text.contains("t#popup-1") && r.text.contains("Continue"))
        assertTrue(runBlocking { rig.tools.observe("""{"tab_id":"t#popup-7"}""") }.text.contains("TAB_NOT_FOUND"))
        rig.dir.deleteRecursively()
    }

    // ---- modal detection ---------------------------------------------------------------------

    private fun modalEngine(): javax.script.ScriptEngine {
        val engine = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        engine.eval("""
            var window={innerWidth:1000,innerHeight:800,getComputedStyle:function(e){return {display:e.display||'block',visibility:e.visibility||'visible',position:e.position||'static',zIndex:e.z===undefined?'auto':String(e.z)};}};
            function El(o){this.nodeType=1;this.rect=[0,0,100,100];this.attrs={};this.parentElement=null;this.kids=[];this.text='';this.main=false;this.overlay=false;this.hiddenAnc=false;
              for(var k in o)this[k]=o[k];}
            El.prototype.getAttribute=function(n){return this.attrs.hasOwnProperty(n)?this.attrs[n]:null;};
            El.prototype.getBoundingClientRect=function(){var r=this.rect;return {left:r[0],top:r[1],right:r[2],bottom:r[3],width:r[2]-r[0],height:r[3]-r[1]};};
            El.prototype.closest=function(sel){if(sel.indexOf('data-rpa')>=0)return this.overlay?this:null;if(sel.indexOf('aria-hidden')>=0)return this.hiddenAnc?this:null;return null;};
            El.prototype.matches=function(){return this.main;};
            El.prototype.querySelector=function(){return this.heading||null;};
            Object.defineProperty(El.prototype,'innerText',{get:function(){return this.text;}});
            var dialogs=[], under=null, ids={};
            var location={href:'https://shop.test/'}, document={title:'Shop',visibilityState:'visible',body:new El({}),documentElement:new El({}),
              querySelectorAll:function(){return dialogs;},elementFromPoint:function(){return under;},getElementById:function(i){return ids[i]||null;}};
        """.trimIndent())
        return engine
    }

    private fun modalOf(e: javax.script.ScriptEngine): ModalInfo? = PageMeta.parse(e.eval(PageMeta.SCRIPT))!!.modal

    @Test
    fun `a visible dialog is the modal, labelled by aria-label, labelledby or heading, and hidden ones are not`() {
        assertSingleExpression(PageMeta.SCRIPT)
        val e = modalEngine()
        assertNull(modalOf(e))
        e.eval("var d=new El({rect:[300,200,700,600],attrs:{role:'dialog','aria-label':'Log in'}}); dialogs.push(d);")
        assertEquals(ModalInfo("Log in", "dialog"), modalOf(e))
        e.eval("d.attrs={role:'alertdialog','aria-labelledby':'t1'}; ids.t1=new El({text:'  Are you   sure? '});")
        assertEquals(ModalInfo("Are you sure?", "alertdialog"), modalOf(e))
        e.eval("d.attrs={'aria-modal':'true'}; d.heading=new El({text:'Welcome back'});")
        assertEquals(ModalInfo("Welcome back", "dialog"), modalOf(e))
        e.eval("d.display='none';"); assertNull(modalOf(e))
        e.eval("d.display='block'; d.rect=[0,0,0,0];"); assertNull(modalOf(e))
        e.eval("d.rect=[300,200,700,600]; d.hiddenAnc=true;"); assertNull(modalOf(e))
        e.eval("d.hiddenAnc=false; d.overlay=true;"); assertNull(modalOf(e)) // our own node
        e.eval("d.overlay=false; d.rect=[2000,2000,2100,2100];"); assertNull(modalOf(e)) // off screen
    }

    @Test
    fun `without ARIA a big fixed high-z-index ancestor at the viewport centre is the modal, page content is not`() {
        val e = modalEngine()
        e.eval("""
            var box=new El({rect:[0,0,1000,800],position:'fixed',z:1000,heading:new El({text:'Login or Sign up'})});
            var inner=new El({rect:[300,200,700,600],parentElement:box}); under=inner;
        """.trimIndent())
        assertEquals(ModalInfo("Login or Sign up", "dialog"), modalOf(e))
        e.eval("box.z=5;"); assertNull(modalOf(e)) // low z-index
        e.eval("box.z=1000; box.rect=[0,0,300,200];"); assertNull(modalOf(e)) // under 30% of the viewport
        e.eval("box.rect=[0,0,1000,800]; box.position='static';"); assertNull(modalOf(e))
        e.eval("box.position='absolute'; box.main=true;"); assertNull(modalOf(e)) // the page's main content
        e.eval("box.main=false; box.overlay=true;"); assertNull(modalOf(e))
    }

    @Test
    fun `observe script flags covered elements, lists modal elements first, and serializes both`() {
        val js = observeScript(80)
        assertSingleExpression(js)
        assertTrue(js.contains("findModal()") && js.contains("elementsFromPoint") && js.contains("coveredEl(el)"))
        assertTrue(js.indexOf("take(minv, true); take(moutv, false); take(inv, true)") > 0)
        assertTrue(js.contains("mOvl(l[q])"), "the plugin's own overlay is skipped when testing what is on top")
        assertTrue(js.contains("modal: modal ? { label: modal.label, role: modal.role } : null"))
        val snap = TabJson.decodeFromString(PageSnapshot.serializer(),
            """{"url":"u","title":"t","total":2,"modal":{"label":"  Log in ","role":"dialog"},"items":[
               {"tag":"input","selector":{"type":"id","value":"phone"},"inViewport":true},
               {"tag":"input","selector":{"type":"id","value":"q"},"inViewport":true,"covered":true}]}""")
        val r = buildObserveResult("t1", snap)
        assertEquals(ModalInfo("Log in", "dialog"), r.modal)
        assertEquals(listOf(false, true), r.elements.map { it.covered })
        val out = Json.parseToJsonElement(TabJson.encodeToString(ObserveResult.serializer(), r)).jsonObject
        assertEquals("Log in", out["modal"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(out["elements"]!!.jsonArray[1].jsonObject["covered"]!!.jsonPrimitive.boolean)
        assertNull(buildObserveResult("t1", PageSnapshot()).modal)
    }

    @Test
    fun `screenshot carries modal only while a dialog is open`() {
        val rig = Rig().apply { browser.meta = """{"url":"https://a.test/","title":"A","vis":"visible","modal":{"label":"Log in","role":"dialog"}}""" }
        val s = json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject
        assertEquals("Log in", s["modal"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        rig.browser.meta = meta("visible")
        assertFalse(json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject.containsKey("modal"))
        rig.dir.deleteRecursively()
    }

    private fun meta(vis: String) = """{"url":"https://a.test/","title":"A","vis":"$vis"}"""

    @Test
    fun `a visible page is captured without selecting its tab`() {
        val rig = Rig().apply { browser.meta = meta("visible") }
        val s = json(rig.run("""{"type":"screenshot","ensure_visible":true}"""))["screenshot"]!!.jsonObject
        assertTrue(rig.selects.isEmpty())
        assertTrue(s["visible"]!!.jsonPrimitive.boolean); assertFalse(s.containsKey("revealed"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `a hidden page is selected through the provider, awaited, then captured with revealed true`() {
        val rig = Rig().apply { browser.meta = meta("hidden") }
        rig.onSelect = { _, _ -> rig.log.add("select"); rig.browser.meta = meta("visible") }
        val s = json(rig.run("""{"type":"screenshot","ensure_visible":true}"""))["screenshot"]!!.jsonObject
        assertEquals(listOf("t" to "p"), rig.selects)
        assertTrue(s["visible"]!!.jsonPrimitive.boolean); assertTrue(s["revealed"]!!.jsonPrimitive.boolean)
        val order = rig.log.entries.filter { it == "select" || it == "bitmap" }
        assertEquals(listOf("select", "bitmap"), order)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `a page that never becomes visible is captured anyway with visible false`() {
        val rig = Rig().apply { browser.meta = meta("hidden") }
        val s = json(rig.run("""{"type":"screenshot","ensure_visible":true}"""))["screenshot"]!!.jsonObject
        assertEquals(1, rig.selects.size)
        assertFalse(s["visible"]!!.jsonPrimitive.boolean); assertTrue(s["revealed"]!!.jsonPrimitive.boolean)
        assertTrue(rig.log.entries.contains("bitmap"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `without ensure_visible a hidden page is only reported, and a popup is never revealed`() {
        val rig = Rig().apply { browser.meta = meta("hidden") }
        val s = json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject
        assertTrue(rig.selects.isEmpty()); assertFalse(s["visible"]!!.jsonPrimitive.boolean); assertFalse(s.containsKey("revealed"))
        rig.run("""{"type":"focused"}""")
        val pop = PopupRig(rig); pop.browser.meta = meta("hidden")
        rig.surface.popupSink!!.invoke(pop.window)
        val ps = json(runBlocking { rig.tools.step("""{"tab_id":"t#popup-1","action":{"type":"screenshot","ensure_visible":true},"highlight_ms":0}""") })["screenshot"]!!.jsonObject
        assertTrue(rig.selects.isEmpty()); assertFalse(ps["visible"]!!.jsonPrimitive.boolean); assertFalse(ps.containsKey("revealed"))
        rig.dir.deleteRecursively()
    }

    private class PopupRig(val rig: Rig) {
        val log = Log()
        val browser = FakeBrowser(log)
        val surface = FakeSurface(log)
        var closed = false
        val window = object : PopupWindow {
            override val surface: TabSurface = this@PopupRig.surface
            override val integration: BrowserIntegration = browser
            override fun url() = "https://accounts.test/signin"
            override fun title() = "Sign in"
            override fun isClosed() = closed
        }
    }

    @Test
    fun `popups ride on results, popup ids route to the popup browser, and closing reports once`() {
        val rig = Rig()
        rig.run("""{"type":"focused"}""") // first CU verb installs the popup wrapper
        val pop = PopupRig(rig)
        rig.surface.popupSink!!.invoke(pop.window)
        assertEquals(1, pop.surface.captures) // dialogs captured on adoption
        val r = json(rig.run("""{"type":"focused"}"""))
        val info = r["popups"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "open", "url", "title"), info.keys)
        assertEquals("t#popup-1", info["id"]!!.jsonPrimitive.content); assertTrue(info["open"]!!.jsonPrimitive.boolean)
        // the pseudo id targets the popup's own browser and surface, not the parent's
        rig.log.entries.clear()
        val shot = rig.tools.let { runBlocking { it.step("""{"tab_id":"t#popup-1","action":{"type":"click_at","value":"30,40"},"highlight_ms":0}""") }}
        assertTrue(json(shot)["ok"]!!.jsonPrimitive.boolean, shot.text)
        assertTrue(pop.surface.events.any { it is TabEvent.Press }); assertTrue(rig.surface.events.none { it is TabEvent.Press })
        assertTrue(json(shot).containsKey("popups")) // the popup's own results list its siblings too
        // selector verbs and unknown ids are refused clearly
        val sel = runBlocking { rig.tools.step("""{"tab_id":"t#popup-1","action":{"type":"click","selector":{"type":"css","value":"a"}}}""") }
        assertTrue(sel.isError && sel.text.contains("computer-use verbs"), sel.text)
        assertTrue(runBlocking { rig.tools.step("""{"tab_id":"t#popup-9","action":{"type":"focused"}}""") }.text.contains("TAB_NOT_FOUND"))
        // a dialog in the popup is answered through the popup id
        val calls = mutableListOf<String>()
        pop.surface.sink!!.onDialog(DialogInfo("confirm", "Allow?")) { a, t -> calls += "$a:$t" }
        val ans = json(runBlocking { rig.tools.step("""{"tab_id":"t#popup-1","action":{"type":"dialog_answer","value":"ok"}}""") })
        assertTrue(ans["ok"]!!.jsonPrimitive.boolean); assertEquals(listOf("true:null"), calls)
        // closed: reported once as {id, open:false}
        pop.closed = true
        val gone = json(rig.run("""{"type":"focused"}""", ""))["popups"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "open"), gone.keys); assertFalse(gone["open"]!!.jsonPrimitive.boolean)
        assertFalse(json(rig.run("""{"type":"focused"}""")).containsKey("popups"))
        assertTrue(runBlocking { rig.tools.step("""{"tab_id":"t#popup-1","action":{"type":"focused"}}""") }.text.contains("TAB_NOT_FOUND"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `cu_end and dispose restore the popup wrapper and the popups' dialog captures`() {
        val rig = Rig()
        rig.run("""{"type":"focused"}""")
        val pop = PopupRig(rig); rig.surface.popupSink!!.invoke(pop.window)
        rig.run("""{"type":"cu_end"}""")
        assertEquals(1, rig.surface.popupRestores); assertEquals(1, rig.surface.restores); assertEquals(1, pop.surface.restores)
        assertFalse(json(rig.run("""{"type":"cu_end"}""")).containsKey("popups"))
        rig.run("""{"type":"focused"}""")
        rig.tools.dispose()
        assertEquals(2, rig.surface.popupRestores)
        rig.dir.deleteRecursively()
    }

    private fun dialogCall(rig: Rig, type: String, message: String): List<String> {
        val calls = mutableListOf<String>()
        rig.surface.sink!!.onDialog(DialogInfo(type, message, url = "https://a.test/")) { a, t -> calls += "$a:$t" }
        return calls
    }

    @Test
    fun `capture starts at the first CU verb, a dialog rides on every result, dialog_answer answers it`() {
        val rig = Rig()
        assertEquals(0, rig.surface.captures)
        assertNull(json(rig.run("""{"type":"focused"}"""))["dialog"])
        assertEquals(1, rig.surface.captures)
        rig.run("""{"type":"key","value":"Enter"}""")
        assertEquals(1, rig.surface.captures) // once per tab
        val calls = dialogCall(rig, "confirm", "Delete it?")
        val blocked = json(rig.run("""{"type":"click_at","value":"10,10"}"""))
        assertFalse(blocked["ok"]!!.jsonPrimitive.boolean)
        val d = blocked["dialog"]!!.jsonObject
        assertEquals("confirm", d["type"]!!.jsonPrimitive.content); assertEquals("Delete it?", d["message"]!!.jsonPrimitive.content)
        assertEquals("https://a.test/", d["url"]!!.jsonPrimitive.content); assertFalse(d.containsKey("default_value"))
        assertNotNull(json(rig.run("""{"type":"screenshot"}"""))["dialog"])
        assertTrue(calls.isEmpty())
        val ans = json(rig.run("""{"type":"dialog_answer","value":"ok"}"""))
        assertTrue(ans["ok"]!!.jsonPrimitive.boolean); assertFalse(ans.containsKey("dialog"))
        assertEquals(listOf("true:null"), calls)
        assertTrue(json(rig.run("""{"type":"focused"}""")).let { it["ok"]!!.jsonPrimitive.boolean && !it.containsKey("dialog_auto") })
        val none = json(rig.run("""{"type":"dialog_answer","value":"cancel"}"""))
        assertFalse(none["ok"]!!.jsonPrimitive.boolean); assertTrue(none["error"]!!.jsonPrimitive.content.contains("No browser dialog"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `a second dialog is auto-answered and reported once as dialog_auto`() {
        val rig = Rig()
        rig.run("""{"type":"focused"}""")
        dialogCall(rig, "confirm", "one")
        val second = dialogCall(rig, "confirm", "two")
        assertEquals(listOf("false:null"), second)
        val r = json(rig.run("""{"type":"dialog_answer","value":"ok"}"""))
        val auto = r["dialog_auto"]!!.jsonArray.single().jsonObject
        assertEquals("two", auto["message"]!!.jsonPrimitive.content); assertEquals("second_dialog", auto["reason"]!!.jsonPrimitive.content)
        assertFalse(json(rig.run("""{"type":"focused"}""")).containsKey("dialog_auto"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `cu_end restores once, answers a held dialog safely, and a later CU verb captures again`() {
        val rig = Rig()
        rig.run("""{"type":"focused"}""")
        val calls = dialogCall(rig, "prompt", "name?")
        val end = json(rig.run("""{"type":"cu_end"}"""))
        assertTrue(end["ok"]!!.jsonPrimitive.boolean); assertFalse(end.containsKey("dialog"))
        assertEquals(1, rig.surface.restores); assertEquals(listOf("false:null"), calls)
        rig.run("""{"type":"cu_end"}""")
        assertEquals(1, rig.surface.restores) // nothing left to restore
        rig.run("""{"type":"focused"}""")
        assertEquals(2, rig.surface.captures)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `dispose restores every captured tab`() {
        val rig = Rig()
        rig.run("""{"type":"focused"}""")
        rig.tools.dispose()
        assertEquals(1, rig.surface.restores)
        rig.tools.dispose()
        assertEquals(1, rig.surface.restores)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `dialog_answer needs a valid value, and the schema lists the new verbs`() {
        listOf("""{"type":"dialog_answer"}""", """{"type":"dialog_answer","value":"yes"}""").forEach {
            assertEquals(TabErrorCodes.INVALID_INPUT, code { parseStepArgs(step(it)) })
        }
        parseStepArgs(step("""{"type":"dialog_answer","value":"ok:hi"}""")); parseStepArgs(step("""{"type":"cu_end"}"""))
        assertTrue(TabActions.STEP_SCHEMA.contains(""""cu_end","dialog_answer"]"""))
        assertTrue(TabActions.STEP_DESCRIPTION.contains("dialog_answer") && TabActions.STEP_DESCRIPTION.contains("120 s"))
    }

    @Test
    fun `screenshot hides the overlay, captures, restores, and reports the saved size`() {
        val rig = Rig()
        val r = rig.run("""{"type":"screenshot","value":"800"}""")
        assertFalse(r.isError, r.text)
        val o = json(r)
        assertTrue(o["ok"]!!.jsonPrimitive.boolean, r.text)
        val s = o["screenshot"]!!.jsonObject
        assertEquals(800, s["width"]!!.jsonPrimitive.int); assertEquals(640, s["height"]!!.jsonPrimitive.int)
        assertEquals(1000, s["css_width"]!!.jsonPrimitive.int); assertEquals(800, s["css_height"]!!.jsonPrimitive.int)
        assertEquals(2.0, s["dpr"]!!.jsonPrimitive.double)
        val file = File(s["path"]!!.jsonPrimitive.content)
        assertTrue(file.isFile && file.name.endsWith(".jpg") && file.name.startsWith("t-"))
        ImageIO.read(file).let { assertEquals(800 to 640, it.width to it.height) }
        val order = rig.log.entries.filter { it in setOf("hide", "bitmap", "show") }
        assertEquals(listOf("bitmap"), order) // default: the overlay is left alone
        assertEquals(10, s["scroll"]!!.jsonObject["x"]!!.jsonPrimitive.int)
        assertEquals(250, s["scroll"]!!.jsonObject["y"]!!.jsonPrimitive.int)
        assertEquals(1800, s["scroll"]!!.jsonObject["max_y"]!!.jsonPrimitive.int)
        assertFalse(o.containsKey("scroll")) // top-level: only inside screenshot
        assertFalse(o.containsKey("focused"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `a default screenshot leaves the pointer untouched, and hide_overlay removes then re-injects it at its spot`() {
        val rig = Rig()
        val scripts = mutableListOf<String>()
        val browser = object : BrowserIntegration by rig.browser {
            override suspend fun executeJavaScript(script: String): Any? { scripts += script; return rig.browser.executeJavaScript(script) }
        }
        val tools = TabActions(computerUse = ComputerUse({ rig.surface }, ShotStore(rig.dir))) { FakeTabs(browser) }
        runBlocking {
            tools.step("""{"tab_id":"t","action":{"type":"click_at","value":"120,340"},"pointer":{"label":"Jev · Research","color":"#112233"},"highlight_ms":0}""")
            rig.log.entries.clear(); scripts.clear()
            tools.step("""{"tab_id":"t","action":{"type":"screenshot"},"highlight_ms":0}""")
        }
        // default: no removal, no re-injection, no glide: the pointer is not touched at all
        assertEquals(listOf("bitmap"), rig.log.entries.filter { it in setOf("hide", "bitmap", "pointer") })
        assertTrue(scripts.none { it.contains("root.style.transform") || it.contains(POINTER_ATTR) && it.contains("remove") })
        rig.log.entries.clear(); scripts.clear()
        runBlocking { tools.step("""{"tab_id":"t","action":{"type":"screenshot","hide_overlay":true},"highlight_ms":0}""") }
        assertEquals(listOf("hide", "bitmap", "pointer"), rig.log.entries.filter { it in setOf("hide", "bitmap", "pointer") })
        val reinject = scripts.last { it.contains("root.style.transform") }
        assertTrue(reinject.contains("translate(120px,340px)") && reinject.contains("#112233") && reinject.contains("Jev · Research"))
        assertFalse(reinject.contains("animate")) // no ripple on restore
        // the next pointer action still re-creates the overlay (e.g. after a navigation): pointerScript builds it when absent
        assertTrue(pointerScript(5, 6, 120 to 340, PointerSpec("Jev", "#112233"), false).contains("if(!root){"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `an unreadable scroll is omitted and pointer_hide stops the restore`() {
        val rig = Rig().apply { browser.scroll = true }
        val s = json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject
        assertFalse(s.containsKey("scroll"))
        rig.run("""{"type":"click_at","value":"50,60"}""", ""","pointer":{"label":"Jev"}""")
        rig.run("""{"type":"pointer_hide"}""")
        rig.log.entries.clear()
        rig.run("""{"type":"screenshot","hide_overlay":true}""")
        assertFalse(rig.log.entries.contains("pointer"))
        assertEquals(ScrollInfo(1, 2, 3), ScrollInfo.parse("1,2,3"))
        assertEquals(ScrollInfo(0, 0, 0), ScrollInfo.parse("\"0.4,-1,0\""))
        assertNull(ScrollInfo.parse("x,1,2")); assertNull(ScrollInfo.parse(true))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `screenshot restores the overlay even when the bitmap is missing`() {
        val rig = Rig()
        val broken = TabActions(computerUse = ComputerUse({ FakeSurface(rig.log, bitmap = null) }, ShotStore(rig.dir))) { FakeTabs(rig.browser) }
        val out = json(runBlocking { broken.step("""{"tab_id":"t","action":{"type":"screenshot","hide_overlay":true},"highlight_ms":0}""") })
        assertFalse(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("hide", "bitmap"), rig.log.entries.filter { it in setOf("hide", "bitmap", "pointer") })
        rig.dir.deleteRecursively()
    }

    @Test
    fun `click_at sends move, press, release inside the viewport and refuses outside`() {
        val rig = Rig()
        val ok = json(rig.run("""{"type":"click_at","value":"120,340"}"""))
        assertTrue(ok["ok"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf<TabEvent>(TabEvent.Move(120, 340), TabEvent.Press(120, 340), TabEvent.Release(120, 340)),
            rig.surface.events,
        )
        assertFalse(rig.log.entries.contains("pointer"), "no pointer unless asked")
        rig.surface.events.clear()
        val out = json(rig.run("""{"type":"click_at","value":"1000,10"}"""))
        assertFalse(out["ok"]!!.jsonPrimitive.boolean)
        assertTrue(out["error"]!!.jsonPrimitive.content.contains("outside"))
        assertTrue(rig.surface.events.isEmpty())
        rig.dir.deleteRecursively()
    }

    @Test
    fun `click_at with a pointer glides before the click`() {
        val rig = Rig()
        rig.run("""{"type":"click_at","value":"50,60"}""", ""","pointer":{"label":"Jev"}""")
        val l = rig.log.entries
        assertTrue(l.indexOf("pointer") in 0 until l.indexOf("event:Press"), l.toString())
        rig.dir.deleteRecursively()
    }

    @Test
    fun `a selector click with a pointer glides to the element centre first`() {
        val rig = Rig()
        json(rig.run("""{"type":"click","selector":{"type":"id","value":"go"}}""", ""","pointer":{"label":"Jev"}"""))
        assertEquals(1, rig.log.entries.count { it == "pointer" })
        // Without a pointer the old path runs untouched.
        val plain = Rig()
        plain.run("""{"type":"click","selector":{"type":"id","value":"go"}}""")
        assertFalse(plain.log.entries.contains("pointer"))
        rig.dir.deleteRecursively(); plain.dir.deleteRecursively()
    }

    @Test
    fun `type_text selects the field first and types real keys`() {
        val rig = Rig()
        val o = json(rig.run("""{"type":"type_text","value":"Hi"}"""))
        assertTrue(o["ok"]!!.jsonPrimitive.boolean, o.toString())
        assertTrue(rig.log.entries.contains("focused+select"))
        assertEquals(listOf("KEY_CODE_H", "KEY_CODE_I"), rig.surface.events.filterIsInstance<TabEvent.KeyDown>().map { it.code })
        assertEquals("input", o["focused"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `type_text into a select presses Escape then type-ahead without selecting`() {
        val rig = Rig()
        rig.browser.focusedJson = """{"tag":"select","editable":false,"label":"State","options":["Ohio","Texas"]}"""
        val o = json(rig.run("""{"type":"type_text","value":"Te"}"""))
        assertTrue(o["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("KEY_CODE_ESCAPE", "KEY_CODE_T", "KEY_CODE_E"), rig.surface.events.filterIsInstance<TabEvent.KeyDown>().map { it.code })
        assertFalse(rig.log.entries.contains("focused+select"))
        assertEquals(2, o["focused"]!!.jsonObject["options"]!!.jsonArray.size)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `type_text refuses a non-field and a sensitive field`() {
        val rig = Rig()
        rig.browser.focusedJson = """{"tag":"body","editable":false}"""
        val o = json(rig.run("""{"type":"type_text","value":"x"}"""))
        assertFalse(o["ok"]!!.jsonPrimitive.boolean)
        assertTrue(rig.surface.events.isEmpty())

        rig.browser.focusedJson = """{"tag":"input","editable":true,"type":"password","sensitive":true}"""
        val refused = rig.run("""{"type":"type_text","value":"hunter2"}""")
        assertTrue(refused.isError)
        assertFalse(refused.text.contains("hunter2"))
        assertTrue(rig.surface.events.isEmpty())
        val allowed = json(rig.run("""{"type":"type_text","value":"ab"}""", ""","allow_sensitive":true"""))
        assertTrue(allowed["ok"]!!.jsonPrimitive.boolean)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `key and scroll_by send real events, focused and pointer_hide are script only`() {
        val rig = Rig()
        assertTrue(json(rig.run("""{"type":"key","value":"Escape"}"""))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf<Class<*>>(TabEvent.KeyDown::class.java, TabEvent.KeyUp::class.java), rig.surface.events.map { it::class.java })
        rig.surface.events.clear()
        assertTrue(json(rig.run("""{"type":"scroll_by","value":"250"}"""))["ok"]!!.jsonPrimitive.boolean)
        val wheel = rig.surface.events.filterIsInstance<TabEvent.Wheel>()
        assertEquals(3, wheel.size); assertTrue(wheel.all { it.x == 500 && it.y == 400 })
        rig.surface.events.clear()

        rig.hasSurface = false
        val f = json(rig.run("""{"type":"focused"}"""))
        assertTrue(f["ok"]!!.jsonPrimitive.boolean)
        assertEquals("Search", f["focused"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(json(rig.run("""{"type":"pointer_hide"}"""))["ok"]!!.jsonPrimitive.boolean)
        assertTrue(rig.log.entries.contains("pointer-remove"))
        val noSurface = json(rig.run("""{"type":"click_at","value":"1,1"}"""))
        assertFalse(noSurface["ok"]!!.jsonPrimitive.boolean)
        assertNotNull(noSurface["error"]); assertFalse(noSurface.containsKey("screenshot"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `a refused dispatch is a failed step`() {
        val rig = Rig()
        rig.surface.accept = false
        assertFalse(json(rig.run("""{"type":"click_at","value":"5,5"}"""))["ok"]!!.jsonPrimitive.boolean)
        assertFalse(json(rig.run("""{"type":"key","value":"Tab"}"""))["ok"]!!.jsonPrimitive.boolean)
        rig.dir.deleteRecursively()
    }

    // ---- D5: speed and accuracy ---------------------------------------------------------------

    private fun durationMs(o: kotlinx.serialization.json.JsonObject) = o["duration_ms"]!!.jsonPrimitive.int

    @Test
    fun `read verbs take no settle and no quiet wait, action verbs do both`() {
        val rig = Rig().apply { browser.look = lookAnswer(); browser.locate = """{"found":false,"count":0,"in_view":false}""" }
        for (a in listOf("""{"type":"screenshot"}""", """{"type":"page_text"}""", """{"type":"locate_text","value":"x"}""", """{"type":"focused"}""", """{"type":"look"}""", """{"type":"pointer_hide"}""")) {
            val o = json(rig.run(a))
            assertTrue(o["ok"]!!.jsonPrimitive.boolean, "$a $o")
            assertTrue(durationMs(o) < STEP_URL_SETTLE_MS, "$a took ${durationMs(o)} ms")
        }
        assertFalse(rig.log.entries.any { it.startsWith("quiet") }, rig.log.entries.toString())
        assertTrue(STEP_NO_SETTLE_TYPES.containsAll(setOf("screenshot", "page_text", "locate_text", "focused", "look")))
        for (a in listOf("""{"type":"click_at","value":"5,5"}""", """{"type":"key","value":"Tab"}""", """{"type":"scroll_by","value":"10"}""", """{"type":"type_text","value":"a"}""", """{"type":"click","selector":{"type":"id","value":"go"}}""")) {
            rig.log.entries.clear()
            assertTrue(json(rig.run(a))["ok"]!!.jsonPrimitive.boolean, a)
            assertEquals(1, rig.log.entries.count { it == "quiet-start" }, "$a ${rig.log.entries}")
            assertTrue(rig.log.entries.contains("quiet-poll"), a)
        }
        rig.dir.deleteRecursively()
    }

    @Test
    fun `url settle is 0 for reads, 100 ms for quiet-waited action verbs and 300 ms for the legacy verbs`() {
        for (t in listOf("screenshot", "page_text", "locate_text", "focused", "look")) assertEquals(0L, stepUrlSettleMs(t), t)
        for (t in listOf("click_at", "type_text", "key", "scroll_by", "dialog_answer")) assertEquals(100L, stepUrlSettleMs(t), t)
        for (t in listOf("click", "input", "select", "keypress", "submit", "navigate", "wait", "download")) assertEquals(300L, stepUrlSettleMs(t), t)
        val rig = Rig()
        val quick = durationMs(json(rig.run("""{"type":"key","value":"Tab"}""")))
        val legacy = durationMs(json(rig.run("""{"type":"click","selector":{"type":"id","value":"go"}}""")))
        assertTrue(quick < STEP_URL_SETTLE_MS, "key took $quick ms")
        assertTrue(legacy >= STEP_URL_SETTLE_MS, "legacy click took $legacy ms")
        rig.dir.deleteRecursively()
    }

    private fun quietEngine(): javax.script.ScriptEngine {
        val e = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        e.eval("""
            var clock=0,timers=[],loc={href:'https://a.test/'},location=loc,observers=[];
            var Date={now:function(){return clock;}};
            function setTimeout(f,ms){timers.push({f:f,at:clock+ms});}
            function MutationObserver(cb){this.cb=cb;observers.push(this);}
            MutationObserver.prototype.observe=function(){};
            MutationObserver.prototype.disconnect=function(){};
            var window={MutationObserver:MutationObserver,requestAnimationFrame:function(f){setTimeout(f,16);}};
            var document={documentElement:{}};
            function advance(ms){var end=clock+ms;for(;;){var best=-1;
              for(var i=0;i<timers.length;i++){if(timers[i].at<=end&&(best<0||timers[i].at<timers[best].at))best=i;}
              if(best<0)break;var t=timers.splice(best,1)[0];clock=Math.max(clock,t.at);t.f();}
              clock=end;}
            function mutate(ours){observers[0].cb([{type:'attributes',target:{nodeType:1,closest:function(){return ours?{}:null;}}}]);}
            function done(){return window.__rpaQuiet.done;}
        """.trimIndent())
        return e
    }

    @Test
    fun `quiet script is one expression, waits two frames and 100 ms of calm, and is capped at 800 ms`() {
        val js = quietStartScript()
        assertSingleExpression(js)
        assertTrue(js.contains("Q=100") && js.contains("CAP=800") && js.contains("MutationObserver") && js.contains("requestAnimationFrame") && js.contains("window.__rpaQuiet"))
        assertEquals(100L, QUIET_MS); assertEquals(800L, QUIET_CAP_MS); assertEquals(30L, QUIET_POLL_MS)
        assertSingleExpression(quietPollScript())
        // calm page: done after the frames plus 100 ms, not before
        var e = quietEngine(); e.eval(js)
        e.eval("advance(90)"); assertEquals(false, e.eval("done()"))
        e.eval("advance(50)"); assertEquals(true, e.eval("done()"))
        // a mutation restarts the calm window
        e = quietEngine(); e.eval(js)
        e.eval("advance(80); mutate(false); advance(80)"); assertEquals(false, e.eval("done()"))
        e.eval("advance(60)"); assertEquals(true, e.eval("done()"))
        // continuous churn stops at the cap
        e = quietEngine(); e.eval(js)
        e.eval("for(var k=0;k<14;k++){advance(50);mutate(false);}"); assertEquals(false, e.eval("done()")) // clock 700
        e.eval("for(var k=0;k<4;k++){advance(50);mutate(false);}"); assertEquals(true, e.eval("done()")) // clock 900
        assertTrue((e.eval("clock") as Number).toInt() <= 900)
        // the plugin's own overlay churn (the gliding pointer) does not count
        e = quietEngine(); e.eval(js)
        e.eval("advance(60); mutate(true); advance(60); mutate(true); advance(20)"); assertEquals(true, e.eval("done()"))
        // a location change ends it early
        e = quietEngine(); e.eval(js)
        e.eval("advance(40); loc.href='https://a.test/next'; advance(30)"); assertEquals(true, e.eval("done()"))
        // the poll reads the flag, and a page that dropped it (navigated) counts as done
        assertEquals(true, e.eval(quietPollScript()))
        e.eval("window.__rpaQuiet=undefined"); assertEquals(true, e.eval(quietPollScript()))
    }

    @Test
    fun `the glide starts and the click follows after the lead, not after the glide`() {
        val rig = Rig()
        rig.run("""{"type":"click_at","value":"50,60"}""", ""","pointer":{"label":"Jev"}""")
        val t = rig.log.times
        val p = t.first { it.first == "pointer" }.second
        val press = t.first { it.first == "event:Press" }.second
        val ms = (press - p) / 1_000_000
        assertTrue(ms >= POINTER_LEAD_MS - 5, "lead was $ms ms")
        assertTrue(ms < POINTER_GLIDE_MS, "the click waited $ms ms, as long as the glide")
        assertEquals(60L, POINTER_LEAD_MS)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `image defaults are 1100 px and quality 0_7, and an explicit screenshot value still wins`() {
        assertEquals(1100, SHOT_DEFAULT_MAX_SIDE); assertEquals(0.7, SHOT_JPEG_QUALITY)
        assertEquals(LookOptions(maxSide = 1100, quality = 0.7), LookOptions.parse(null))
        val rig = Rig().apply { browser.look = lookAnswer() }
        assertEquals(1100, json(rig.run("""{"type":"screenshot"}"""))["screenshot"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        assertEquals(900, json(rig.run("""{"type":"screenshot","value":"900"}"""))["screenshot"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        val lookShot = json(rig.run("""{"type":"look"}"""))["look"]!!.jsonObject["screenshot"]!!.jsonObject
        assertEquals(1100, lookShot["width"]!!.jsonPrimitive.int); assertEquals(880, lookShot["height"]!!.jsonPrimitive.int)
        val small = json(rig.run("""{"type":"look","value":"{\"max_side\":600,\"quality\":0.5}"}"""))["look"]!!.jsonObject["screenshot"]!!.jsonObject
        assertEquals(600, small["width"]!!.jsonPrimitive.int)
        val big = File(lookShot["path"]!!.jsonPrimitive.content).length(); val low = File(small["path"]!!.jsonPrimitive.content).length()
        assertTrue(big > low)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `look options validate`() {
        val o = LookOptions.parse("""{"max_side":1100,"quality":0.7,"locate":["Magnolia","Tres Leches","Magnolia"],"elements":false,"ensure_visible":true,"page_text":true}""")
        assertEquals(LookOptions(1100, 0.7, listOf("Magnolia", "Tres Leches"), false, true, true), o)
        for (bad in listOf("[1]", "not json", """{"max_side":100}""", """{"quality":0}""", """{"quality":1.5}""", """{"locate":"x"}""", """{"locate":[""]}""", """{"elements":"yes"}""", """{"locate":[${(1..21).joinToString(",") { "\"p$it\"" }}]}""")) {
            assertFailsWith<IllegalArgumentException>(bad) { LookOptions.parse(bad) }
        }
        assertEquals("INVALID_INPUT", code { parseStepArgs(step("""{"type":"look","value":"{\"max_side\":5}"}""")) })
        assertEquals("INVALID_INPUT", code { parseStepArgs(step("""{"type":"click","selector":{"type":"id","value":"a"},"element":"m1"}""")) })
        assertEquals("INVALID_INPUT", code { parseStepArgs(step("""{"type":"click_at","value":"1,1","element":"12"}""")) })
        assertEquals("m3", parseStepArgs(step("""{"type":"click_at","element":"m3"}""")).element)
        assertEquals("INVALID_INPUT", code { parseStepArgs(step("""{"type":"click_at"}""")) })
        assertTrue(TabActions.STEP_SCHEMA.contains(""""locate_text","look","cu_end"""") && TabActions.STEP_SCHEMA.contains(""""element":{"type":"string""""))
    }

    private fun elJson(id: String, label: String, extra: String = "") =
        """{"id":"$id","role":"button","tag":"button","label":"$label","x":10,"y":20,"w":80,"h":30,"disabled":false,"covered":false,"context":"Eggless Tres Leches ₹470","editable":false$extra,"xp":"/html[1]/body[1]/button[${id.drop(1)}]"}"""

    private fun lookAnswer(els: List<String> = listOf(elJson("m1", "ADD")), text: String? = null, located: Map<String, String> = emptyMap()): String {
        val o = kotlinx.serialization.json.buildJsonObject {
            put("meta", kotlinx.serialization.json.JsonPrimitive("""{"url":"https://a.test/menu","title":"Menu","vis":"visible"}"""))
            put("vp", kotlinx.serialization.json.JsonPrimitive("1000,800,2"))
            put("scroll", kotlinx.serialization.json.JsonPrimitive("0,120,900"))
            put("hash", kotlinx.serialization.json.JsonPrimitive("1a2b3c4d"))
            put("located", kotlinx.serialization.json.buildJsonObject { located.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) } })
            put("els", kotlinx.serialization.json.JsonPrimitive("[" + els.joinToString(",") + "]"))
            put("text", text?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }
        return o.toString()
    }

    @Test
    fun `look result carries the screenshot, located phrases, flagged elements and omits what was not asked`() {
        val rig = Rig().apply {
            browser.look = lookAnswer(
                els = listOf(
                    elJson("m1", "Log in", ""","value_kind":"empty""""),
                    elJson("m2", "ADD").replace(""""disabled":false""", """"disabled":true"""),
                    elJson("m3", "Cart").replace(""""covered":false""", """"covered":true"""),
                    elJson("m4", "Docs", ""","href_host":"docs.test""""),
                ),
                located = mapOf("Tres Leches" to """{"found":true,"count":2,"in_view":false,"direction":"below","screens":1.5}"""),
            )
        }
        val r = json(rig.run("""{"type":"look","value":"{\"locate\":[\"Tres Leches\",\"Nowhere\"]}"}"""))
        assertTrue(r["ok"]!!.jsonPrimitive.boolean, r.toString())
        val look = r["look"]!!.jsonObject
        assertEquals(setOf("screenshot", "located", "elements"), look.keys)
        val shot = look["screenshot"]!!.jsonObject
        assertEquals(setOf("path", "width", "height", "css_width", "css_height", "dpr", "scroll", "content_hash", "url", "title", "visible"), shot.keys)
        assertEquals("https://a.test/menu", shot["url"]!!.jsonPrimitive.content)
        assertEquals("below", look["located"]!!.jsonObject["Tres Leches"]!!.jsonObject["direction"]!!.jsonPrimitive.content)
        assertEquals(1.5, look["located"]!!.jsonObject["Tres Leches"]!!.jsonObject["screens"]!!.jsonPrimitive.double)
        assertFalse(look["located"]!!.jsonObject["Nowhere"]!!.jsonObject["found"]!!.jsonPrimitive.boolean)
        val els = look["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("m1", "m2", "m3", "m4"), els.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("id", "role", "tag", "label", "value_kind", "rect", "disabled", "covered", "context", "editable"), els[0].keys)
        assertEquals(setOf("x", "y", "w", "h"), els[0]["rect"]!!.jsonObject.keys)
        assertEquals(listOf(false, true, false, false), els.map { it["disabled"]!!.jsonPrimitive.boolean })
        assertEquals(listOf(false, false, true, false), els.map { it["covered"]!!.jsonPrimitive.boolean })
        assertEquals("docs.test", els[3]["href_host"]!!.jsonPrimitive.content)
        assertFalse(els.any { it.containsKey("xp") }, "the stable path stays inside rpaengine")
        assertFalse(look.containsKey("page_text")); assertFalse(r.containsKey("screenshot"))
        // the look script read everything in one call plus the bitmap
        assertEquals(listOf("look", "bitmap"), rig.log.entries.filter { it in setOf("look", "bitmap", "hash", "viewport", "scroll", "focused") })
        val withText = json(rig.run("""{"type":"look","value":"{\"page_text\":true}"}"""))
        rig.browser.look = lookAnswer(text = "Menu text")
        assertEquals("Menu text", json(rig.run("""{"type":"look","value":"{\"page_text\":true}"}"""))["look"]!!.jsonObject["page_text"]!!.jsonPrimitive.content)
        assertNotNull(withText)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `look caps elements at 120, carries modal on the screenshot, popups and dialog, and fails when the page will not answer`() {
        val rig = Rig().apply {
            browser.meta = null
            browser.look = lookAnswer(els = (1..150).map { elJson("m$it", "B$it") }).replace("""\"vis\":\"visible\"}""", """\"vis\":\"visible\",\"modal\":{\"label\":\"Log in\",\"role\":\"dialog\"}}""")
        }
        val look = json(rig.run("""{"type":"look"}"""))["look"]!!.jsonObject
        assertEquals(120, look["elements"]!!.jsonArray.size)
        assertEquals("Log in", look["screenshot"]!!.jsonObject["modal"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        rig.browser.look = null
        val bad = json(rig.run("""{"type":"look"}"""))
        assertFalse(bad["ok"]!!.jsonPrimitive.boolean); assertFalse(bad.containsKey("look"))
        assertEquals(LOOK_MAX_ELEMENTS, 120)
        assertTrue(lookElementsScript().contains("MAX = 120") && lookElementsScript(500).contains("MAX = 120"))
        rig.dir.deleteRecursively()
    }

    // A small DOM for the elements and mark scripts.
    private fun domEngine(): javax.script.ScriptEngine {
        val e = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        e.eval("""
            var NS='http://www.w3.org/1999/xhtml';
            var window={innerWidth:1000,innerHeight:800,getComputedStyle:function(e){return {display:e.display||'block',visibility:'visible',position:e.position||'static',zIndex:e.z===undefined?'auto':String(e.z)};}};
            function T(s){this.nodeType=3;this.nodeValue=s;}
            function E(tag,o){this.nodeType=1;this.tagName=tag.toUpperCase();this.nodeName=this.tagName;this.localName=tag;this.namespaceURI=NS;
              this.attrs={};this.kids=[];this.childNodes=this.kids;this.parentNode=null;this.parentElement=null;this.rect=[0,0,0,0];this.dis=false;this.ctrl=false;this.ovl=false;this.inter=false;
              this.innerText='';for(var k in o)this[k]=o[k];}
            E.prototype.add=function(c){c.parentNode=this;c.parentElement=this;this.kids.push(c);return c;};
            E.prototype.getAttribute=function(n){return this.attrs.hasOwnProperty(n)?this.attrs[n]:null;};
            E.prototype.getBoundingClientRect=function(){var r=this.rect;return {left:r[0],top:r[1],right:r[2],bottom:r[3],width:r[2]-r[0],height:r[3]-r[1]};};
            E.prototype.matches=function(s){if(s===':disabled')return this.dis;if(s.indexOf('input,textarea')===0)return this.ctrl;return false;};
            E.prototype.closest=function(s){if(s.indexOf('data-rpa')>=0){for(var n=this;n;n=n.parentElement)if(n.ovl)return n;return null;}return null;};
            E.prototype.contains=function(o){for(var n=o;n;n=n.parentElement)if(n===this)return true;return false;};
            E.prototype.querySelector=function(){return null;};
            E.prototype.querySelectorAll=function(){var out=[];(function w(n){for(var i=0;i<n.kids.length;i++){var c=n.kids[i];if(c.nodeType===1){if(c.inter)out.push(c);w(c);}}})(this);return out;};
            Object.defineProperty(E.prototype,'previousSibling',{get:function(){if(!this.parentNode)return null;var k=this.parentNode.kids,i=k.indexOf(this);return i>0?k[i-1]:null;}});
            Object.defineProperty(E.prototype,'previousElementSibling',{get:function(){if(!this.parentNode)return null;var k=this.parentNode.kids,i=k.indexOf(this);while(--i>=0)if(k[i].nodeType===1)return k[i];return null;}});
            Object.defineProperty(E.prototype,'textContent',{get:function(){var s='';(function w(n){for(var i=0;i<n.kids.length;i++){var c=n.kids[i];if(c.nodeType===3)s+=c.nodeValue;else w(c);}})(this);return s;}});
            var html=new E('html'),body=html.add(new E('body'));
            var all=[],dialogs=[],stack=function(x,y){return [];};
            var document={documentElement:html,body:body,baseURI:'https://a.test/',
              querySelectorAll:function(s){return s.indexOf('role=dialog')>=0?dialogs:all;},
              elementsFromPoint:function(x,y){return stack(x,y);},elementFromPoint:function(){return null;},getElementById:function(){return null;}};
            function btn(parent,label,rect,o){var b=parent.add(new E('button',{rect:rect,inter:true,innerText:label}));b.add(new T(label));all.push(b);for(var k in o)b[k]=o[k];return b;}
        """.trimIndent())
        return e
    }

    private fun domElements(e: javax.script.ScriptEngine, max: Int = LOOK_MAX_ELEMENTS): List<RawLookElement> =
        TabJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RawLookElement.serializer()), e.eval(lookElementsScript(max)) as String)

    @Test
    fun `elements script keeps disabled and covered, skips overlays and off-screen, lists modal first, and adds context`() {
        assertSingleExpression(lookElementsScript())
        val e = domElements0()
        val els = domElements(e)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), els.map { it.id })
        // modal first: its button leads even though it comes last in the document
        assertEquals("OK", els[0].label)
        val byLabel = els.associateBy { it.label }
        assertTrue(byLabel.getValue("ADD").covered, "the modal sits on top of the page behind it")
        assertFalse(byLabel.getValue("OK").covered)
        assertEquals(true, byLabel.getValue("Sold out").disabled)
        assertEquals("Eggless Tres Leches ₹470", byLabel.getValue("ADD").context)
        assertEquals("Menu", byLabel.getValue("Sold out").context.takeIf { it.isNotEmpty() } ?: "Menu")
        assertFalse(els.any { it.label == "Overlay" || it.label == "Far away" })
        assertEquals(40, byLabel.getValue("ADD").x); assertEquals(100, byLabel.getValue("ADD").y)
        assertEquals(60, byLabel.getValue("ADD").w)
        // typed values never leave the page; only their presence does
        val q = byLabel.getValue("Search")
        assertEquals("text", q.valueKind); assertTrue(q.editable)
        assertFalse(e.eval(lookElementsScript()).toString().contains("hunter2"))
        assertEquals("checked", byLabel.getValue("Eggless").valueKind)
        assertEquals("selected:Large", byLabel.getValue("Size").valueKind)
        assertNull(byLabel.getValue("ADD").valueKind)
        assertTrue(els.all { it.xp!!.startsWith("/html[1]/body[1]") })
        assertTrue(lookElementsScript().let { it.contains("window.__rpaMarks") && it.contains("data-rpa") })
    }

    private fun domElements0(): javax.script.ScriptEngine {
        val e = domEngine()
        e.eval("""
            var card=body.add(new E('div',{rect:[0,80,400,140]}));card.add(new T('Eggless Tres Leches ₹470 '));
            var add=btn(card,'ADD',[40,100,100,130]);
            var soldRow=body.add(new E('div',{rect:[0,150,400,200]}));soldRow.add(new E('h3',{innerText:'Menu'}));
            var sold=btn(soldRow,'Sold out',[40,160,100,190],{dis:true});
            btn(body,'Overlay',[0,0,50,50],{ovl:true});
            btn(body,'Far away',[0,3000,50,3040]);
            var s=body.add(new E('input',{rect:[10,300,200,330],inter:true,ctrl:true,value:'hunter2'}));s.attrs={type:'text','aria-label':'Search'};all.push(s);
            var c=body.add(new E('input',{rect:[10,340,30,360],inter:true,ctrl:true,checked:true}));c.attrs={type:'checkbox','aria-label':'Eggless'};all.push(c);
            var sel=body.add(new E('select',{rect:[10,370,200,400],inter:true,ctrl:true,selectedIndex:1,options:[{label:'Small'},{label:'Large'}]}));sel.attrs={'aria-label':'Size'};all.push(sel);
            var dlg=body.add(new E('div',{rect:[300,200,700,600]}));dlg.attrs={role:'dialog','aria-label':'Log in'};dialogs.push(dlg);
            var ok=btn(dlg,'OK',[400,500,460,530]);
            stack=function(x,y){if(x>=400&&x<=460&&y>=500&&y<=530)return [ok,dlg];return [dlg];};
        """.trimIndent())
        return e
    }

    @Test
    fun `the elements script caps at 120 and falls back to the preceding heading for context`() {
        val e = domEngine()
        e.eval("""
            for(var i=0;i<130;i++){btn(body,'B'+i,[0,i*5,50,i*5+4]);}
        """.trimIndent())
        val els = domElements(e)
        assertEquals(120, els.size)
        assertEquals("m120", els.last().id)
        val e2 = domEngine()
        e2.eval("""
            body.add(new E('h2',{innerText:'Desserts'}));var row=body.add(new E('div',{rect:[0,0,300,100]}));btn(row,'ADD',[10,10,60,40]);
        """.trimIndent())
        assertEquals("Desserts", domElements(e2).single().context)
    }

    @Test
    fun `mark script clicks the visible centre, and reports disabled, covered by label or modal, and gone`() {
        val e = domElements0()
        val els = domElements(e)
        fun ref(label: String) = els.first { it.label == label }.let { it.id to MarkRef(it.xp, it.label, it.tag) }
        fun mark(label: String): MarkAnswer { val (id, r) = ref(label); return TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(markScript(id, r)) as String) }
        assertSingleExpression(markScript("m1", MarkRef("/html[1]", "x", "button")))
        val ok = mark("OK")
        assertEquals(MarkAnswer("ok", 430, 515, "OK", "button"), ok)
        assertEquals("disabled", mark("Sold out").state)
        val covered = mark("ADD")
        assertEquals("covered", covered.state); assertEquals("Log in", covered.cover) // the modal's label
        // covered by an ordinary element: its label
        e.eval("var banner=body.add(new E('div',{rect:[0,0,1000,60]}));banner.attrs={'aria-label':'Cookie banner'};dialogs.length=0;stack=function(x,y){return [banner];};add.rect=[40,20,100,50];")
        val byBanner = mark("ADD")
        assertEquals("covered", byBanner.state); assertEquals("Cookie banner", byBanner.cover)
        // a point inside the element that is free is used even when its centre is covered
        e.eval("stack=function(x,y){return (x>=60&&x<=80&&y>=20&&y<=50)?[banner]:[];};")
        assertEquals("ok", mark("ADD").state)
        // gone: detached from the page and no usable path
        e.eval("all.length=0; card.kids.length=0; card.parentNode.kids.splice(card.parentNode.kids.indexOf(card),1); card.parentElement=null; card.parentNode=null;")
        assertEquals("gone", mark("ADD").state)
        assertEquals("gone", TabJson.decodeFromString(MarkAnswer.serializer(), e.eval(markScript("m99", MarkRef(null, "x", "button"))) as String).state)
    }

    private fun lookRig(): Rig = Rig().apply { browser.look = lookAnswer(els = listOf(elJson("m1", "ADD"), elJson("m2", "Sold out"))) }

    @Test
    fun `click_at by element id clicks the resolved point and reports clicked`() {
        val rig = lookRig()
        rig.run("""{"type":"look"}""")
        rig.browser.mark = """{"state":"ok","x":430,"y":515,"label":"ADD","tag":"button"}"""
        val o = json(rig.run("""{"type":"click_at","element":"m1","value":"10,20"}"""))
        assertTrue(o["ok"]!!.jsonPrimitive.boolean, o.toString())
        assertEquals(listOf<TabEvent>(TabEvent.Move(430, 515), TabEvent.Press(430, 515), TabEvent.Release(430, 515)), rig.surface.events)
        assertEquals("m1", o["clicked"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("ADD", o["clicked"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("button", o["clicked"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        assertTrue(rig.log.entries.contains("mark"))
        // a plain click_at has no clicked
        assertFalse(json(rig.run("""{"type":"click_at","value":"5,5"}"""))["clicked"] != null)
        rig.dir.deleteRecursively()
    }

    @Test
    fun `click_at by element id refuses a disabled or covered element without clicking`() {
        val rig = lookRig()
        rig.run("""{"type":"look"}""")
        rig.surface.events.clear()
        rig.browser.mark = """{"state":"disabled","label":"ADD","tag":"button"}"""
        val d = json(rig.run("""{"type":"click_at","element":"m1","value":"10,20"}"""))
        assertFalse(d["ok"]!!.jsonPrimitive.boolean)
        assertEquals("'ADD' is disabled", d["error"]!!.jsonPrimitive.content)
        rig.browser.mark = """{"state":"covered","label":"ADD","tag":"button","cover":"Log in"}"""
        val c = json(rig.run("""{"type":"click_at","element":"m1","value":"10,20"}"""))
        assertFalse(c["ok"]!!.jsonPrimitive.boolean)
        assertEquals("'ADD' is covered by Log in", c["error"]!!.jsonPrimitive.content)
        assertTrue(rig.surface.events.isEmpty())
        assertFalse(c.containsKey("clicked"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `click_at by element id falls back to x,y when the element is gone, and fails without one`() {
        val rig = lookRig()
        rig.run("""{"type":"look"}""")
        rig.surface.events.clear()
        rig.browser.mark = """{"state":"gone"}"""
        val g = json(rig.run("""{"type":"click_at","element":"m1","value":"120,340"}"""))
        assertTrue(g["ok"]!!.jsonPrimitive.boolean, g.toString())
        assertEquals(TabEvent.Press(120, 340), rig.surface.events.filterIsInstance<TabEvent.Press>().single())
        assertFalse(g.containsKey("clicked"))
        rig.surface.events.clear()
        val n = json(rig.run("""{"type":"click_at","element":"m1"}"""))
        assertFalse(n["ok"]!!.jsonPrimitive.boolean)
        assertTrue(n["error"]!!.jsonPrimitive.content.contains("gone"))
        assertTrue(rig.surface.events.isEmpty())
        // an id the latest look never had is gone too, without asking the page
        rig.log.entries.clear()
        val u = json(rig.run("""{"type":"click_at","element":"m77","value":"7,8"}"""))
        assertTrue(u["ok"]!!.jsonPrimitive.boolean)
        assertFalse(rig.log.entries.contains("mark"))
        // a new look replaces the marks
        rig.browser.look = lookAnswer(els = listOf(elJson("m5", "Next")))
        rig.run("""{"type":"look"}""")
        rig.log.entries.clear()
        rig.run("""{"type":"click_at","element":"m1","value":"7,8"}""")
        assertFalse(rig.log.entries.contains("mark"))
        rig.dir.deleteRecursively()
    }

    @Test
    fun `type_text by element clicks it first and then types`() {
        val rig = Rig().apply { browser.look = lookAnswer(els = listOf(elJson("m1", "Search", ""","editable":true"""))) }
        rig.run("""{"type":"look"}""")
        rig.browser.mark = """{"state":"ok","x":200,"y":315,"label":"Search","tag":"input"}"""
        val o = json(rig.run("""{"type":"type_text","element":"m1","value":"Hi"}"""))
        assertTrue(o["ok"]!!.jsonPrimitive.boolean, o.toString())
        assertEquals("m1", o["clicked"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val ev = rig.surface.events
        assertTrue(ev.indexOfFirst { it is TabEvent.Press } in 0 until ev.indexOfFirst { it is TabEvent.KeyDown })
        assertEquals(listOf("KEY_CODE_H", "KEY_CODE_I"), ev.filterIsInstance<TabEvent.KeyDown>().map { it.code })
        // a disabled element is not typed into
        rig.surface.events.clear()
        rig.browser.mark = """{"state":"disabled","label":"Search","tag":"input"}"""
        val d = json(rig.run("""{"type":"type_text","element":"m1","value":"Hi"}"""))
        assertFalse(d["ok"]!!.jsonPrimitive.boolean); assertTrue(rig.surface.events.isEmpty())
        rig.dir.deleteRecursively()
    }

    private fun pointerDomEngine(popover: Boolean): javax.script.ScriptEngine {
        val e = NashornScriptEngineFactory().getScriptEngine("--language=es5")
        e.eval("""
            var calls=[],popoverOk=$popover,byId={};
            function El(tag){this.tagName=tag;this.attrs={};this.kids=[];this.parentNode=null;this.offsetWidth=1;this.open=false;
              var self=this;this.style={cssText:'',setProperty:function(){}};
              if(popoverOk){this.showPopover=function(){if(!self.attrs.popover)throw new Error('NotSupportedError');self.open=true;calls.push('show:'+(self.id||self.tagName));};
                this.hidePopover=function(){self.open=false;calls.push('hide:'+(self.id||self.tagName));};}}
            El.prototype.setAttribute=function(n,v){this.attrs[n]=v;};
            El.prototype.getAttribute=function(n){return this.attrs.hasOwnProperty(n)?this.attrs[n]:null;};
            El.prototype.hasAttribute=function(n){return this.attrs.hasOwnProperty(n);};
            El.prototype.matches=function(s){return s===':popover-open'&&this.open;};
            El.prototype.appendChild=function(c){c.parentNode=this;this.kids.push(c);if(c.id)byId[c.id]=c;return c;};
            El.prototype.remove=function(){};
            El.prototype.querySelector=function(){return this.kids[1]||null;};
            var document={body:new El('body'),documentElement:new El('html'),
              getElementById:function(i){return byId[i]||null;},createElement:function(t){return new El(t);},createElementNS:function(n,t){return new El(t);}};
            Object.defineProperty(El.prototype,'textContent',{set:function(v){},get:function(){return '';}});
        """.trimIndent())
        return e
    }

    @Test
    fun `pointer is a manual popover raised to the top layer on every glide, with the z-index fallback`() {
        val js = pointerScript(300, 200, 10 to 20, PointerSpec(), ripple = true)
        assertSingleExpression(js)
        assertTrue(js.contains("showPopover") && js.contains("hidePopover") && js.contains(":popover-open") && js.contains("typeof root.showPopover==='function'"))
        assertTrue(js.contains("setAttribute('popover','manual')"))
        assertTrue(js.contains("z-index:2147483647") && js.contains("pointer-events:none") && js.contains("margin:0;padding:0;border:0;background:transparent;overflow:visible;inset:auto;"))
        assertTrue(js.contains("r.showPopover()"), "the ripple joins the top layer after the pointer")
        // supported: shown on creation, hidden then shown on each later glide
        val e = pointerDomEngine(true)
        e.eval(pointerScript(5, 6, null, PointerSpec(), false))
        assertEquals("show:__rpa_pointer", e.eval("calls.join(',')") as String)
        e.eval("calls.length=0")
        e.eval(pointerScript(50, 60, 5 to 6, PointerSpec(), false))
        assertEquals("hide:__rpa_pointer,show:__rpa_pointer", (e.eval("calls.join(',')") as String))
        assertEquals("manual", e.eval("document.getElementById('__rpa_pointer').attrs.popover"))
        // unsupported: no popover calls, still injected, no throw
        val f = pointerDomEngine(false)
        assertEquals("ok", f.eval(pointerScript(5, 6, null, PointerSpec(), false)))
        assertEquals("ok", f.eval(pointerScript(7, 8, 5 to 6, PointerSpec(), true)))
        assertEquals(0, (f.eval("calls.length") as Number).toInt())
        assertEquals(true, f.eval("document.getElementById('__rpa_pointer')!==null"))
    }
}
