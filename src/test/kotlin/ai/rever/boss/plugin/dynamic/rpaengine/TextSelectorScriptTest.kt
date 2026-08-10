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
    fun `takes the deepest match, not the first`() {
        // Ancestors come first in document order, so indexing from the front is the bug.
        val js = textSelectorScript("Images")
        assertTrue(js.contains("all[all.length - 1]"), "does not take the deepest match: $js")
        assertTrue(
            js.contains("clickable[clickable.length - 1]"),
            "does not take the deepest clickable match: $js",
        )
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
