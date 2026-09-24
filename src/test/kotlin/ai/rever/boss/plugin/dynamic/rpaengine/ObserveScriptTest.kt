package ai.rever.boss.plugin.dynamic.rpaengine

import java.io.File
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory
import javax.script.Compilable
import javax.script.ScriptEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The observe script and its helpers. The helpers are evaluated for real (Nashorn, ES5); the DOM
 * part can only be parsed here, so the privacy rule is pinned by a guard on the script text.
 */
class ObserveScriptTest {

    private fun engine(): ScriptEngine = NashornScriptEngineFactory().getScriptEngine("--language=es5")

    private fun helpers(): ScriptEngine = engine().also { it.eval(OBSERVE_HELPERS_JS) }

    /** Compiles only if [script] is exactly one expression: a trailing statement breaks the parentheses. */
    private fun assertSingleExpression(script: String) {
        (engine() as Compilable).compile("($script)")
    }

    @Test
    fun `observe script is a single expression`() {
        assertSingleExpression(observeScript(80))
        assertFalse(observeScript(80).trimEnd().endsWith(";"))
    }

    /** Dumped for pasting into a real page (`browser_run_js`); only the DOM can show what it picks. */
    @Test
    fun `dump scripts for manual checking`() {
        File("build/tmp").mkdirs()
        File("build/tmp/observe.js").writeText(observeScript(80))
        File("build/tmp/highlight.js").writeText(highlightScript("document.querySelector('button')", "tok", 600))
        File("build/tmp/highlight-remove.js").writeText(removeHighlightScript("tok"))
        File("build/tmp/download-target.js").writeText(downloadTargetScript("document.querySelector('a.mw-file-description')"))
        File("build/tmp/download-start.js").writeText(
            downloadStartScript("https://upload.wikimedia.org/wikipedia/commons/a/ab/Persialainen.jpg", "Persialainen.jpg", "tok"),
        )
        File("build/tmp/download-poll.js").writeText(downloadPollScript("tok"))
    }

    @Test
    fun `observe script never reads a field value`() {
        val js = observeScript(80)
        assertFalse(Regex("""\.value\b""").containsMatchIn(js), "observe must not read .value")
        assertFalse(js.contains("valueAsNumber") || js.contains("selectedIndex") || js.contains("selectedOptions"))
        // textContent only ever comes from the scrubbed clone in textOf.
        assertEquals(1, Regex("""textContent""").findAll(js).count(), js)
        assertTrue(js.contains("var drop = c.querySelectorAll(CONTROLS"), "textOf must strip form controls from the clone")
        // Image fields and labels come from attributes, currentSrc, and a figcaption through textOf.
        assertTrue(js.contains("if (cap) { s = textOf(cap);"), "figcaption text must go through the scrubbed textOf")
        assertTrue(js.contains("alt: norm(img.getAttribute('alt')) || null"))
    }

    @Test
    fun `standalone images never displace interactive elements`() {
        val js = observeScript(80)
        // Interactive elements are cut to MAX first; images get their own cap and are appended after.
        assertTrue(js.contains("var picked = seen.slice(0, MAX); var items = picked.map("))
        assertTrue(js.contains("pics.slice(0, $OBSERVE_IMAGE_LIMIT)"))
        assertTrue(js.contains("items: items.concat(images)"))
        // Only images outside a reported element, and only from 100x100.
        assertTrue(js.contains("inside = picked[j].el.contains(im)"))
        assertTrue(js.contains("ir.width < $STANDALONE_IMAGE_MIN_PX || ir.height < $STANDALONE_IMAGE_MIN_PX"))
        assertTrue(js.contains("image: imageOf(img, $IMAGE_MIN_PX)"))
    }

    @Test
    fun `image label falls back only after the accessible name`() {
        assertTrue(observeScript(80).contains("label: labelOf(el, tag, role, type) || imageLabel(el, tag, img)"))
    }

    @Test
    fun `pickSrc takes the largest srcset candidate, then currentSrc, then src`() {
        val e = helpers()
        val wiki = "//upload.wikimedia.org/t/250px-X.jpg 1.5x, //upload.wikimedia.org/t/330px-X.jpg 2x"
        assertEquals("//upload.wikimedia.org/t/330px-X.jpg", e.eval("pickSrc(${wiki.asJsString()}, 'cur', 'src')"))
        assertEquals("b.jpg", e.eval("pickSrc('a.jpg 320w, b.jpg 1024w, c.jpg 640w', 'cur', 'src')"))
        assertEquals("b.jpg", e.eval("pickSrc('a.jpg 1x,b.jpg 3x', null, null)"), "no space after the comma")
        assertEquals("b.jpg", e.eval("pickSrc('a.jpg, b.jpg 2x', null, null)"), "no descriptor is 1x")
        assertEquals("a.jpg", e.eval("pickSrc('a.jpg', null, null)"))
        assertEquals("w.jpg", e.eval("pickSrc('x.jpg 3x, w.jpg 100w', null, null)"), "a width descriptor wins")
        assertEquals("cur", e.eval("pickSrc('', 'cur', 'src')"))
        assertEquals("src", e.eval("pickSrc(null, '', 'src')"))
        assertNull(e.eval("pickSrc(null, '', null)"))
    }

    @Test
    fun `safeSrc drops long data URIs only`() {
        val e = helpers()
        assertEquals("data:image/gif;base64,R0", e.eval("safeSrc('data:image/gif;base64,R0')"))
        assertNull(e.eval("safeSrc('data:' + new Array(197).join('x'))"), "201 chars")
        assertEquals(200.0, (e.eval("safeSrc('data:' + new Array(196).join('x')).length") as Number).toDouble())
        assertEquals("https://x.test/a.jpg", e.eval("safeSrc('https://x.test/a.jpg')"))
        assertNull(e.eval("safeSrc('')"))
    }

    @Test
    fun `fileLabel humanises a file name`() {
        val e = helpers()
        mapOf(
            "/wiki/File:Persialainen.jpg" to "Persialainen.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Persian_cat.jpg/330px-Persian_cat.jpg?x=1" to "Persian cat.jpg",
            "/wiki/File:Caf%C3%A9_cat.jpg#top" to "Café cat.jpg",
            "/files/%E0%A4%A.png" to "%E0%A4%A.png",
            "https://x.test/" to "",
            "data:image/png;base64,iVBOR" to "",
        ).forEach { (u, label) -> assertEquals(label, e.eval("fileLabel(${u.asJsString()})"), u) }
        assertEquals(80, (e.eval("fileLabel('/f/' + new Array(200).join('a') + '.jpg').length") as Number).toInt())
    }

    @Test
    fun `fileExt recognises direct file links only`() {
        val e = helpers()
        mapOf(
            "https://x.test/a/report.PDF?dl=1" to "pdf",
            "https://x.test/a.jpeg#frag" to "jpeg",
            "/data/export.csv" to "csv",
            "//cdn.x.test/v/clip.mp4" to "mp4",
        ).forEach { (u, ext) -> assertEquals(ext, e.eval("fileExt(${u.asJsString()})"), u) }
        // A wiki File: page, a page, a .zip TLD with no path, and script URLs are not files.
        listOf("/wiki/File:Persialainen.jpg", "https://x.test/page", "https://example.zip", "https://example.zip/",
            "javascript:x.pdf", "data:application/pdf;base64,x", "").forEach {
            assertNull(e.eval("fileExt(${it.asJsString()})"), it)
        }
    }

    @Test
    fun `download scripts are single expressions`() {
        assertSingleExpression(downloadTargetScript(locateExpression(SelectorInfo("xpath", "//a[@class='x']"))!!))
        assertSingleExpression(downloadStartScript("https://x.test/it's.jpg", "it's.jpg", "t'1"))
        assertSingleExpression(downloadPollScript("t'1"))
        assertSingleExpression(downloadAbortScript("t'1"))
    }

    @Test
    fun `download start never navigates and only falls back same-origin`() {
        val js = downloadStartScript("https://x.test/a.jpg", "a.jpg", "t")
        assertFalse(js.contains("location.href =") || js.contains("location.assign") || js.contains("window.open"))
        assertTrue(js.contains("fetch(url, { mode: 'cors', credentials: 'omit'"))
        assertTrue(js.contains("if (same) { save(url); st.method = 'direct';"))
        assertTrue(js.contains("if (e && e.http) { st.state = 'failed';"), "an HTTP error must not fall back")
        assertTrue(js.contains("if (st.state !== 'pending') { return; } var u = URL.createObjectURL"), "no save after abort")
    }

    @Test
    fun `download target prefers a file href, and a wiki File page is not one`() {
        val js = downloadTargetScript("el0")
        assertTrue(js.indexOf("if (href && fileExt(href))") < js.indexOf("var img = imgIn(el, tag)"))
    }

    @Test
    fun `innerText is gated on non-entry elements`() {
        val js = observeScript(80)
        assertEquals(1, Regex("""innerText""").findAll(js).count())
        assertTrue(js.contains("if (!entry(el, tag, role)) { s = el.querySelector(CONTROLS) ? textOf(el) : norm(el.innerText)"))
    }

    @Test
    fun `max_elements is clamped into the script`() {
        assertTrue(observeScript(5).contains("var MAX = 5;"))
        assertTrue(observeScript(10_000).contains("var MAX = 200;"))
    }

    @Test
    fun `cssq escapes quotes and backslashes`() {
        val e = helpers()
        assertEquals("\"a\\\"b\"", e.eval("cssq('a\"b')"))
        assertEquals("\"a\\\\b\"", e.eval("cssq('a\\\\b')"))
        assertEquals("\"it's\"", e.eval("cssq(\"it's\")"))
    }

    @Test
    fun `attribute values with control characters are not used in selectors`() {
        val e = helpers()
        assertEquals(true, e.eval("usableAttr('q')"))
        assertEquals(false, e.eval("usableAttr('a\\nb')"))
        assertEquals(false, e.eval("usableAttr('')"))
        assertEquals(false, e.eval("usableAttr(null)"))
    }

    @Test
    fun `norm collapses whitespace`() {
        assertEquals("Sign in", helpers().eval("norm('  Sign\\n\\t  in  ')"))
        assertEquals("", helpers().eval("norm(null)"))
    }

    @Test
    fun `generated id pattern matches between Kotlin and JS`() {
        val e = helpers()
        mapOf(
            "search" to false, "login-button" to false, "q" to false,
            "1abc" to true, "id-3fa2b9c1d0" to true, ":r1a:" to true, "react-:r2:" to true,
        ).forEach { (id, generated) ->
            assertEquals(generated, looksGeneratedId(id), id)
            assertEquals(generated, e.eval("generatedId(${id.asJsString()})"), id)
        }
    }

    @Test
    fun `sensitive detection`() {
        val e = helpers()
        assertEquals(true, e.eval("isSensitive('password', null, 'x', 'y')"))
        assertEquals(true, e.eval("isSensitive('text', 'cc-number', null, null)"))
        assertEquals(true, e.eval("isSensitive('text', 'section-a cc-exp', null, null)"))
        assertEquals(true, e.eval("isSensitive('text', 'one-time-code', null, null)"))
        assertEquals(true, e.eval("isSensitive('text', null, 'userPwd', null)"))
        assertEquals(true, e.eval("isSensitive('tel', null, null, 'OTP_input')"))
        assertEquals(false, e.eval("isSensitive('text', 'email', 'q', 'search')"))
        assertEquals(false, e.eval("isSensitive('text', 'account', null, null)"), "ac 'account' is not cc-")
        listOf("password", "cardNumber", "cvv", "ssn", "otp").forEach { assertTrue(looksSensitiveName(it), it) }
        assertFalse(looksSensitiveName("email"))
    }

    @Test
    fun `implicit roles`() {
        val e = helpers()
        mapOf(
            "implicitRole('a', null)" to "link",
            "implicitRole('button', 'submit')" to "button",
            "implicitRole('summary', null)" to "button",
            "implicitRole('select', null)" to "combobox",
            "implicitRole('textarea', null)" to "textbox",
            "implicitRole('input', 'search')" to "searchbox",
            "implicitRole('input', 'checkbox')" to "checkbox",
            "implicitRole('input', 'radio')" to "radio",
            "implicitRole('input', 'submit')" to "button",
            "implicitRole('input', 'email')" to "textbox",
        ).forEach { (expr, role) -> assertEquals(role, e.eval(expr), expr) }
        assertNull(e.eval("implicitRole('div', null)"))
    }

    @Test
    fun `interactive selector skips hidden inputs and covers the listed roles`() {
        assertTrue(INTERACTIVE_SELECTOR.contains("input:not([type=hidden])"))
        listOf("button", "link", "checkbox", "radio", "tab", "menuitem", "option", "switch", "combobox", "searchbox", "textbox")
            .forEach { assertTrue(INTERACTIVE_SELECTOR.contains("[role=$it]"), it) }
    }

    @Test
    fun `highlight and removal scripts are single expressions`() {
        assertSingleExpression(highlightScript(locateExpression(SelectorInfo("css", "input[name='q']"))!!, "tok'1", 600))
        assertSingleExpression(highlightScript("document.activeElement", "t", 0))
        assertSingleExpression(removeHighlightScript("tok'1"))
    }

    @Test
    fun `highlight is non-interactive, removes itself, and honours reduced motion`() {
        val js = highlightScript("document.activeElement", "t", 600)
        assertTrue(js.contains("pointer-events:none"))
        assertTrue(js.contains("outline:2px solid #3592C4"))
        assertTrue(js.contains("'RPA'"))
        assertTrue(js.contains("prefers-reduced-motion: reduce"))
        assertTrue(js.contains("(reduce ? '' : 'opacity:0;transition"), "animation only without reduced motion")
        assertTrue(js.contains("}, 1600);"), "self-removal safety net after ms + 1s")
        assertTrue(js.contains("el === document.body"), "never outlines <body>")
    }

    @Test
    fun `removal targets only its own token`() {
        val js = removeHighlightScript("abc")
        assertTrue(js.contains("var t = 'abc';"))
        assertTrue(js.contains("getAttribute('$HIGHLIGHT_ATTR') === t"))
    }
}
