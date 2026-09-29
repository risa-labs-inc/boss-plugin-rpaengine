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
        // Text comes only from text nodes reached by textOf's walk, which never enters a control.
        assertEquals(0, Regex("""textContent""").findAll(js).count(), js)
        assertEquals(1, Regex("""nodeValue""").findAll(js).count(), js)
        assertTrue(js.contains("c.matches(CONTROLS + ',script,style')"), "textOf must not descend into form controls")
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

    /**
     * A page stub for the download scripts: a synchronous thenable stands in for fetch's promise
     * (Nashorn has none), and `settle` delivers the fetch outcome when the test chooses.
     */
    private fun downloadPage(origin: String = "https://x.test"): ScriptEngine = engine().also {
        it.eval(
            "var clicks = [], aborted = false, pendingFetch = null; " +
                "function P(ok, v) { return { then: function (f, r) { var h = ok ? f : r; if (!h) { return this; } " +
                "try { var x = h(v); return (x && x.then) ? x : P(true, x); } catch (e) { return P(false, e); } } }; } " +
                "function Deferred() { var cbs = []; return { then: function (f, r) { var d = Deferred(); cbs.push([f, r, d]); return d; }, " +
                "settle: function (ok, v) { cbs.forEach(function (c) { var h = ok ? c[0] : c[1]; if (!h) { c[2].settle(ok, v); return; } " +
                "try { var x = h(v); if (x && x.then) { x.then(function (y) { c[2].settle(true, y); }, function (y) { c[2].settle(false, y); }); } " +
                "else { c[2].settle(true, x); } } catch (e) { c[2].settle(false, e); } }); } }; } " +
                "function fetch(u, opts) { pendingFetch = Deferred(); pendingFetch.opts = opts; return pendingFetch; } " +
                "function AbortController() { this.signal = {}; this.abort = function () { aborted = true; }; } " +
                "function URL(u, base) { var m = /^([a-z]+:\\/\\/[^\\/]+)/.exec(u); this.origin = m ? m[1] : base; } " +
                "URL.createObjectURL = function () { return 'blob:x'; }; URL.revokeObjectURL = function () {}; " +
                "var location = { href: '$origin/page', origin: '$origin' }; " +
                "var document = { body: { appendChild: function () {} }, createElement: function () { " +
                "return { style: {}, click: function () { clicks.push(this.href); } }; } }; " +
                "var window = { fetch: fetch, AbortController: AbortController, setTimeout: function () {} }; " +
                "function ok(bytes) { return { ok: true, status: 200, blob: function () { return P(true, { size: bytes }); } }; } ",
        )
    }

    private fun ScriptEngine.poll(): String = eval(downloadPollScript("t")) as String

    @Test
    fun `download blob path reports blob-click with the fetched size`() {
        val e = downloadPage()
        assertEquals(true, e.eval(downloadStartScript("https://cdn.test/a.jpg", "a.jpg", "t")))
        assertEquals("omit", e.eval("pendingFetch.opts.credentials"))
        assertTrue(e.poll().contains("\"state\":\"pending\""))
        e.eval("pendingFetch.settle(true, ok(20481))")
        val done = e.poll()
        assertTrue(done.contains("\"state\":\"done\"") && done.contains("\"bytes\":20481") && done.contains("\"method\":\"$DOWNLOAD_METHOD_BLOB\""), done)
        assertEquals("blob:x", e.eval("clicks[0]"))
        assertTrue(e.poll().contains("missing"), "a settled entry is removed as it is read")
    }

    @Test
    fun `download HTTP error fails without clicking anything`() {
        val e = downloadPage()
        e.eval(downloadStartScript("https://x.test/a.pdf", "a.pdf", "t"))
        e.eval("pendingFetch.settle(true, { ok: false, status: 403 })")
        val st = e.poll()
        assertTrue(st.contains("\"state\":\"failed\"") && st.contains("HTTP 403"), st)
        assertEquals(0, (e.eval("clicks.length") as Number).toInt(), "an HTTP error must not fall back to the direct link")
    }

    @Test
    fun `download network failure falls back to the direct link same-origin only`() {
        val same = downloadPage()
        same.eval(downloadStartScript("https://x.test/a.pdf", "a.pdf", "t"))
        same.eval("pendingFetch.settle(false, new Error('cors'))")
        assertTrue(same.poll().contains("\"method\":\"$DOWNLOAD_METHOD_DIRECT\""))
        assertEquals("https://x.test/a.pdf", same.eval("clicks[0]"))

        val cross = downloadPage()
        cross.eval(downloadStartScript("https://cdn.test/a.pdf", "a.pdf", "t"))
        cross.eval("pendingFetch.settle(false, new Error('cors'))")
        val st = cross.poll()
        assertTrue(st.contains("\"state\":\"failed\"") && st.contains(DOWNLOAD_CROSS_ORIGIN_ERROR), st)
        assertEquals(0, (cross.eval("clicks.length") as Number).toInt())
    }

    @Test
    fun `an aborted download aborts the fetch and never saves a late blob`() {
        val e = downloadPage()
        e.eval(downloadStartScript("https://cdn.test/a.jpg", "a.jpg", "t"))
        e.eval(downloadAbortScript("t"))
        assertEquals(true, e.eval("aborted"))
        assertTrue(e.poll().contains("missing"))
        e.eval("pendingFetch.settle(true, ok(5))")
        assertEquals(0, (e.eval("clicks.length") as Number).toInt(), "a blob arriving after abort must not be saved")
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

    /** A plain-object DOM node: enough for textOf, which only reads node properties. */
    private val nodeJs =
        "function el(tag, kids) { return { nodeType: 1, tag: tag, childNodes: kids || [], " +
            "matches: function (sel) { return sel.split(',').some(function (s) { " +
            "return s === this.tag || (s === '[contenteditable]' && this.tag === 'editable'); }, this); } }; } " +
            "function tx(s) { return { nodeType: 3, nodeValue: s }; } "

    @Test
    fun `textOf skips controls, scripts and styles and reads in document order`() {
        val e = helpers().also { it.eval(nodeJs) }
        assertEquals(
            "Card number ends 42",
            e.eval("textOf(el('div', [tx('Card'), el('span', [tx(' number ')]), el('input', [tx('SECRET')]), " +
                "el('textarea', [tx('TYPED')]), el('editable', [tx('DRAFT')]), el('script', [tx('x=1')]), tx('ends 42')]))"),
        )
        assertEquals("", e.eval("textOf(el('input', [tx('SECRET')]))"), "a control itself has no text")
    }

    @Test
    fun `textOf is bounded on a huge subtree`() {
        val e = helpers().also { it.eval(nodeJs) }
        e.eval("var kids = []; for (var i = 0; i < 50000; i++) { kids.push(tx('word ')); } var big = el('div', kids);")
        val t = e.eval("textOf(big)") as String
        assertTrue(t.length <= 200, "norm caps the label")
        // The walk stops at the char cap long before visiting every node.
        e.eval("var visited = 0; for (var j = 0; j < kids.length; j++) { (function (k) { var v = k.nodeValue; " +
            "Object.defineProperty(k, 'nodeValue', { get: function () { visited++; return v; } }); })(kids[j]); }")
        e.eval("textOf(big)")
        assertTrue((e.eval("visited") as Number).toInt() <= TEXT_WALK_CHARS, "visited ${e.eval("visited")}")
    }

    @Test
    fun `xpath steps name foreign-namespace elements by local-name`() {
        val e = helpers()
        assertEquals("div[2]", e.eval("xpathStep('http://www.w3.org/1999/xhtml', 'div', 'DIV', 2)"))
        assertEquals("a[1]", e.eval("xpathStep(null, 'a', 'A', 1)"))
        assertEquals("*[local-name()='svg'][1]", e.eval("xpathStep('http://www.w3.org/2000/svg', 'svg', 'svg', 1)"))
        assertEquals(
            "*[local-name()='linearGradient'][3]",
            e.eval("xpathStep('http://www.w3.org/2000/svg', 'linearGradient', 'linearGradient', 3)"),
        )
        assertTrue(observeScript(80).contains("parts.unshift(xpathStep(n.namespaceURI, n.localName, n.nodeName, i))"))
    }

    @Test
    fun `computed style only runs on candidates taken, stopping past MAX`() {
        val js = observeScript(80)
        // Rects bucket every candidate; styledVisible runs inside take(), which stops at MAX + 1.
        assertTrue(js.contains("if (hasSize(r)) { (rectInView(r) ? inv : outv).push(all[i]); }"))
        assertTrue(js.contains("i < list.length && seen.length <= MAX; i++) { var el = list[i]; if (!disabled(el) && styledVisible(el))"))
        assertTrue(js.contains("take(inv, true); take(outv, false);"), "in-viewport first")
        assertEquals(1, Regex("""getComputedStyle""").findAll(js).count(), "one getComputedStyle, in styledVisible")
    }

    @Test
    fun `sensitive names match whole words, not substrings of ordinary words`() {
        val e = helpers()
        val flagged = listOf(
            "password", "passwd", "user_pass", "userPass", "loginPwd", "new-password", "passcode",
            "cardNumber", "card_no", "credit-card", "cc", "cc_number", "ccNumber", "cvv", "cvc", "csc",
            "ssn", "otp", "OTP_input", "one_time_code", "oneTimeCode", "pin",
        )
        val clean = listOf(
            "passenger", "compass", "discard", "footprint", "hotpot", "classname", "email", "q", "search",
            "cardholder", "pinterest", "spinner", "account", "accnum", "bypass_cache", "success",
        )
        flagged.forEach {
            assertTrue(looksSensitiveName(it), it)
            assertEquals(true, e.eval("isSensitive('text', null, ${it.asJsString()}, null)"), it)
            assertEquals(true, e.eval("isSensitive('text', null, null, ${it.asJsString()})"), it)
        }
        clean.forEach {
            assertFalse(looksSensitiveName(it), it)
            assertEquals(false, e.eval("isSensitive('text', null, ${it.asJsString()}, ${it.asJsString()})"), it)
        }
    }

    @Test
    fun `sensitive autocomplete tokens`() {
        val e = helpers()
        listOf("current-password", "new-password", "cc-number", "cc-csc", "section-a cc-exp", "one-time-code", "shipping  cc-name")
            .forEach { assertEquals(true, e.eval("isSensitive('text', ${it.asJsString()}, null, null)"), it) }
        listOf("email", "username", "account", "off", "tel", "password-hint")
            .forEach { assertEquals(false, e.eval("isSensitive('text', ${it.asJsString()}, null, null)"), it) }
    }

    @Test
    fun `sensitive target script checks the resolved element with the observe rules`() {
        val js = sensitiveTargetScript("document.getElementById('pw')")
        assertSingleExpression(js)
        assertTrue(js.contains("return isSensitive(type, el.getAttribute('autocomplete'), el.getAttribute('name'), el.getAttribute('id'));"))
        assertFalse(Regex("""\.value\b""").containsMatchIn(js), "the check must not read the field")
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
