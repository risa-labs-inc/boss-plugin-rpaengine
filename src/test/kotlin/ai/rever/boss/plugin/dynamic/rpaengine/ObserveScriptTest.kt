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
    }

    @Test
    fun `observe script never reads a field value`() {
        val js = observeScript(80)
        assertFalse(Regex("""\.value\b""").containsMatchIn(js), "observe must not read .value")
        assertFalse(js.contains("valueAsNumber") || js.contains("selectedIndex") || js.contains("selectedOptions"))
        // textContent only ever comes from the scrubbed clone in textOf.
        assertEquals(1, Regex("""textContent""").findAll(js).count(), js)
        assertTrue(js.contains("var drop = c.querySelectorAll(CONTROLS"), "textOf must strip form controls from the clone")
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
