package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four-outcome policy, table-driven.
 *
 * This is the decision that made a navigating click stop the run at the step that had just
 * succeeded, reported as "No element matched" - a selector problem that was not one. Keeping it in
 * a pure function is what makes the policy readable and pinnable.
 */
class InterpretOutcomeTest {

    private val failure = "No element matched [name='q']"

    @Test
    fun `a truthy completion value is a success`() {
        assertEquals(Pair(true, null), interpretOutcome(true, failure))
        assertEquals(Pair(true, null), interpretOutcome("true", failure))
    }

    @Test
    fun `a thrown error names the exception, not the selector`() {
        val (ok, message) = interpretOutcome("threw: f is not a function", failure)

        assertEquals(false, ok)
        assertEquals("threw: f is not a function", message)
    }

    @Test
    fun `an explicit false is the body's own check failing`() {
        assertEquals(Pair(false, failure), interpretOutcome(false, failure))
        assertEquals(Pair(false, failure), interpretOutcome("false", failure))
    }

    @Test
    fun `an absent completion value is a success`() {
        // Existence was proven by polling immediately before, so nothing coming back means the
        // click or submit navigated and tore the frame down before the value returned.
        assertEquals(Pair(true, null), interpretOutcome(null, failure))
        assertEquals(Pair(true, null), interpretOutcome("", failure))
    }

    @Test
    fun `an unrecognised value is not treated as the body's check failing`() {
        // Anything that is not an explicit false has not told us the check failed.
        assertEquals(Pair(true, null), interpretOutcome(42, failure))
    }
}
