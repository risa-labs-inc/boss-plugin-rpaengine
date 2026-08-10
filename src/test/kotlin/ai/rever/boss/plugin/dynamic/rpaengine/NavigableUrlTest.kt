package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isNavigableUrl] is one of the two gates between an agent-resolved plan and the user's live
 * browser session. It had no test, and the case-insensitivity in particular is load-bearing.
 */
class NavigableUrlTest {

    @Test
    fun `ordinary web urls pass`() {
        assertTrue(isNavigableUrl("https://example.com/"))
        assertTrue(isNavigableUrl("http://example.com/a?b=c"))
        assertTrue(isNavigableUrl("about:blank"))
    }

    @Test
    fun `script urls are refused`() {
        // location.href = 'javascript:...' executes in the page.
        assertFalse(isNavigableUrl("javascript:alert(1)"))
        assertFalse(isNavigableUrl("JavaScript:alert(1)"))
        assertFalse(isNavigableUrl("  javascript:alert(1)"), "leading space must not smuggle it")
    }

    @Test
    fun `data and file urls are refused`() {
        assertFalse(isNavigableUrl("data:text/html,<script>alert(1)</script>"))
        assertFalse(isNavigableUrl("file:///etc/passwd"))
    }

    @Test
    fun `surrounding whitespace does not reject a real url`() {
        // What the trim actually buys: rejecting "  javascript:..." works without it (the match
        // simply fails), so only the positive direction pins it.
        assertTrue(isNavigableUrl("  https://example.com/  "))
    }

    @Test
    fun `an uppercase scheme is still allowed`() {
        // IGNORE_CASE cuts both ways: it must not let javascript: through, and must not reject a
        // perfectly ordinary HTTPS:// URL.
        assertTrue(isNavigableUrl("HTTPS://example.com/"))
    }

    @Test
    fun `a scheme-prefixed lookalike is refused`() {
        assertFalse(isNavigableUrl("nothttps://example.com"))
        assertFalse(isNavigableUrl(""))
    }
}
