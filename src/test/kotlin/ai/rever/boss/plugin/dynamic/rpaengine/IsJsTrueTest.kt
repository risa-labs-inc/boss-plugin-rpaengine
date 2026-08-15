package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The browser bridge returns `true` for a boolean and `"true"` for a stringified one, so a call
 * site testing only `== true` reads every success as a failure.
 */
class IsJsTrueTest {

    @Test
    fun `accepts both shapes the bridge returns`() {
        assertTrue(true.isJsTrue())
        assertTrue("true".isJsTrue())
    }

    @Test
    fun `rejects falsehood, null and anything else`() {
        assertFalse(false.isJsTrue())
        assertFalse("false".isJsTrue())
        assertFalse(null.isJsTrue())
        assertFalse("".isJsTrue())
        assertFalse(1.isJsTrue())
    }
}
