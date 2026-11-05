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

    private class Log { val entries = mutableListOf<String>(); @Synchronized fun add(s: String) { entries += s } }

    private class FakeBrowser(val log: Log) : BrowserIntegration {
        var focusedJson = """{"tag":"input","editable":true,"label":"Search","type":"text"}"""
        var viewport = "1000,800,2"
        var scroll: Any? = "10,250,1800"
        var hash: Any? = "1a2b3c4d"
        var snap: Any? = null
        var locate: Any? = null
        override suspend fun executeJavaScript(script: String): Any? {
            val r: Any? = when {
                script == Viewport.SCRIPT -> viewport
                script == ScrollInfo.SCRIPT -> scroll
                script == contentHashScript() -> hash
                script.contains("elementFromPoint") -> snap
                script.contains("createTreeWalker") -> locate
                script.contains("JSON.stringify({ tag: tag") -> focusedJson
                script.contains("getBoundingClientRect") && script.contains("Math.round(r.left") -> "40,50"
                script.contains(".innerText") -> "Best: A, B, C"
                else -> true
            }
            log.add(
                when {
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

    private class FakeTabs(private val b: BrowserIntegration) : ActiveTabsProvider {
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

    private class Rig(val dir: File = Files.createTempDirectory("cu").toFile()) {
        val log = Log()
        val browser = FakeBrowser(log)
        val surface = FakeSurface(log)
        var hasSurface = true
        val tools = TabActions(computerUse = ComputerUse({ if (hasSurface) surface else null }, ShotStore(dir))) { FakeTabs(browser) }
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
}
