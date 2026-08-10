package ai.rever.boss.plugin.dynamic.rpaengine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [textSelectorScript] is JS, so string assertions can only hold its shape. The behaviour is
 * verified by running the dumped script in a real page (see AGENTS.md) - which is the only way
 * the ancestor-vs-anchor bug it fixes is observable at all.
 */
class TextSelectorScriptTest {

    @Test
    fun `prefers a clickable match over a wrapper`() {
        val js = textSelectorScript("Images")
        assertTrue(js.contains("'A'") && js.contains("'BUTTON'"), "no clickable preference: $js")
    }

    @Test
    fun `drops matches that contain another match`() {
        // The wrapper bug is ancestors, and ancestors are exactly the matches containing another
        // match. Taking the *last* match would also drop them, but a label occurring twice on the
        // page (nav and card) makes "last" a different element rather than a deeper one - so the
        // ancestors are excluded explicitly and first-occurrence order is kept.
        val js = textSelectorScript("Images")
        assertTrue(js.contains("n.contains(m)"), "does not exclude ancestors: $js")
        assertTrue(js.contains("leaves[0]"), "does not keep first-occurrence order: $js")
        assertTrue(js.contains("clickable[0]"), "does not prefer the first clickable leaf: $js")
    }

    @Test
    fun `steps to the enclosing anchor or button`() {
        assertTrue(textSelectorScript("Images").contains("closest('a,button')"))
    }

    @Test
    fun `matches on exact trimmed text`() {
        val js = textSelectorScript("Images")
        assertTrue(js.contains(".trim() === t"), "must be an exact match, not a substring: $js")
    }

    @Test
    fun `escapes the target text`() {
        val js = textSelectorScript("it's")
        assertTrue(js.contains("\\'"), "target text must be escaped: $js")
    }

    @Test
    fun `dumps the script for in-page verification`() {
        val out = File("build/tmp/text-selector-images.js")
        out.parentFile.mkdirs()
        out.writeText(textSelectorScript("Images"))
        assertTrue(out.length() > 0)
    }
}

/**
 * The cheap-prefilter half of [textSelectorScript].
 *
 * `innerText` is layout-dependent, so reading it per candidate forces style and layout across the
 * whole set, and the expression is re-evaluated every poll for up to five seconds - so a `text`
 * selector that misses would mean roughly fifty full-page layout passes.
 */
class TextSelectorCostTest {

    @Test
    fun `prefilters on textContent before consulting innerText`() {
        val js = textSelectorScript("Images")
        val cheapAt = js.indexOf("textContent")
        val exactAt = js.indexOf("innerText")
        assertTrue(cheapAt in 0 until exactAt, "innerText is not gated behind textContent: $js")
    }

    @Test
    fun `still confirms with innerText`() {
        // textContent alone would match hidden text, which is not a visible label.
        assertTrue(textSelectorScript("Images").contains("innerText"))
    }

    @Test
    fun `considers the tags a plan actually names`() {
        val js = textSelectorScript("Some result title")
        // A search result title is an h3; omitting the heading and list tags made "click the
        // result titled X" unresolvable.
        listOf("h3", "li", "textarea", "label").forEach {
            assertTrue(js.contains(it), "candidate tags omit $it: $js")
        }
    }
}
