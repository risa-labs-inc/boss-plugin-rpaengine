package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.BrowserIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Called with the JS expression resolving an element that is about to be acted on. */
internal typealias ElementHook = suspend (browser: BrowserIntegration, locate: String) -> Unit

/**
 * Executes one plan action against a real browser. Shared by the panel's run loop and the
 * `rpa_step` MCP tool so both have the same verbs and the same failure semantics.
 *
 * [log] receives engine warnings (a selector fallback); it never receives typed values.
 */
internal class ActionRunner(private val log: (LogLevel, String) -> Unit) {

    /**
     * Execute a real action in the browser.
     *
     * [beforeAct] runs once the target element is known to exist and immediately before the body
     * touches it, with the JS expression that resolves it. The panel passes none; `rpa_step` uses
     * it to highlight the element.
     */
    suspend fun execute(
        browser: BrowserIntegration,
        action: RpaActionConfig,
        beforeAct: ElementHook? = null,
    ): Pair<Boolean, String?> {
        return try {
            when (action.type) {
                ActionTypes.NAVIGATE -> {
                    val url = action.value ?: return Pair(false, "No URL specified")
                    // Assigning location.href to a javascript: URL executes it in the page.
                    // `run_script` is a declared verb, so this is not a new capability - but a
                    // navigate step should navigate, and nothing should smuggle script through it.
                    if (!isNavigableUrl(url)) {
                        return Pair(false, "Refusing to navigate to '$url' (http, https or about only)")
                    }
                    // Was discarding the result, so a dead tab or a throwing assignment still
                    // reported ok - in the verb that is action #0 of every plan.
                    //
                    // The assignment is all that is checked. Comparing location.href before and
                    // after looks stronger and is not: the execution tab is *created at* the first
                    // navigate's URL, so that navigation is legitimately a no-op, and
                    // "https://www.google.com" vs the browser's "https://www.google.com/" differ by
                    // a slash - which reported the working step as a failure and stopped the run at
                    // action #0. A redirect breaks the comparison from the other side.
                    val assigned =
                        browser.executeJavaScript(
                            "(function () { try { window.location.href = ${url.asJsString()}; " +
                                "return true; } catch (e) { return 'threw: ' + e.message; } })();",
                        )
                    delay(ActionTiming.NAVIGATE_SETTLE_MS)
                    when {
                        assigned is String && assigned.startsWith("threw: ") -> Pair(false, assigned)
                        assigned.isJsTrue() -> Pair(true, null)
                        // No completion value: the assignment navigated and tore the frame down
                        // before it could return, which is what a successful navigate looks like.
                        assigned == null || assigned == "" -> Pair(true, null)
                        else -> Pair(false, "Could not navigate to '$url': $assigned")
                    }
                }
                ActionTypes.CLICK -> {
                    val outcome =
                        browser.runOn(
                            action.selector,
                            beforeAct,
                            "el.click();",
                            "No element matched ${action.selector.value}",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(ActionTiming.ACTION_SETTLE_MS)
                    outcome
                }
                ActionTypes.INPUT -> {
                    val value = action.value ?: ""
                    // Reports whether the value actually took. `el.value = x` on a contenteditable
                    // div or any non-form element a plan guessed sets an expando property: nothing
                    // visible happens and existence alone would call that a success.
                    val outcome =
                        browser.runOn(
                            action.selector,
                            beforeAct,
                            typeValueScript(value),
                            "Could not type into '${action.selector.value}' (no match, or it has no value)",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(ActionTiming.INPUT_SETTLE_MS)
                    outcome
                }
                ActionTypes.SELECT -> {
                    val value = action.value ?: ""
                    // Assigning an unmatched value to a <select> leaves it unchanged per spec, and
                    // `change` still fires - so existence reported ok for a selection that never
                    // happened. Matches by label as well as value, because a plan names what the
                    // user sees (same reasoning as TEXT_CANDIDATE_TAGS).
                    val outcome =
                        browser.runOn(
                            action.selector,
                            beforeAct,
                            selectOptionScript(value),
                            "No option '$value' in '${action.selector.value}'",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(ActionTiming.INPUT_SETTLE_MS)
                    outcome
                }
                // Enter in a search field is how most of the web is driven, so this is not an
                // exotic verb - its absence is why a generated "search for X" plan stopped at
                // typing. `value` names the key, defaulting to Enter.
                ActionTypes.KEYPRESS -> {
                    val key = action.value?.takeIf { it.isNotBlank() } ?: "Enter"
                    val dispatch = "el.focus(); " + keyPressScript(key)
                    val outcome =
                        if (action.selector.type == SelectorTypes.NONE || action.selector.value.isNullOrBlank()) {
                            beforeAct?.invoke(browser, "document.activeElement")
                            // No selector means "whatever has focus". Nothing is focused right
                            // after a navigation, which is exactly when a plan says "press
                            // Enter", so the script reports whether it had a real target
                            // instead of this branch assuming success.
                            val landed =
                                browser.executeJavaScript(
                                    // `landed` is checked BEFORE dispatching: firing all three
                                    // key events at <body> and only then deciding there was no
                                    // real target left side effects on the page for an action that
                                    // reports failure.
                                    "(function () { var el = document.activeElement; " +
                                        "if (!el || el === document.body) { return false; } " +
                                        "$dispatch return landed; })();",
                                ).isJsTrue()
                            if (landed) {
                                Pair(true, null)
                            } else {
                                Pair(false, "Nothing was focused to receive the '$key' key")
                            }
                        } else {
                            browser.runOn(
                                action.selector,
                                beforeAct,
                                dispatch,
                                "No element matched ${action.selector.value}",
                            ) ?: return Pair(false, unsupportedSelector(action.selector))
                        }
                    delay(ActionTiming.ACTION_SETTLE_MS)
                    outcome
                }
                ActionTypes.SUBMIT -> {
                    // requestSubmit fires validation and submit handlers the way a real click
                    // does; submit() skips both, so it is only the fallback.
                    val script =
                        "var f = el.form || el; " +
                            "if (f.requestSubmit) { f.requestSubmit(); } else { f.submit(); }"
                    val outcome =
                        browser.runOn(
                            action.selector,
                            beforeAct,
                            script,
                            "No element matched ${action.selector.value}",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(ActionTiming.NAVIGATE_SETTLE_MS)
                    outcome
                }
                ActionTypes.RUN_SCRIPT -> {
                    val script = action.value ?: return Pair(false, "No script specified")
                    // Wrapped so a throwing script is a failure rather than a silent pass: the
                    // bridge may surface an eval error as a returned value rather than an
                    // exception, and this branch used to discard the result entirely.
                    val outcome =
                        browser.executeJavaScript(
                            "(function () { try { $script; return true; } " +
                                "catch (e) { return 'threw: ' + e.message; } })();",
                        )
                    delay(ActionTiming.ACTION_SETTLE_MS)
                    // Deliberately NOT interpretOutcome's last branch: that one is justified by
                    // awaitElement having already proven the element exists, and a bare script has
                    // no such proof - "nothing came back" is genuinely ambiguous here. It stays a
                    // failure, but says which ambiguity rather than printing `null`.
                    when {
                        outcome.isJsTrue() -> Pair(true, null)
                        outcome == null || outcome == "" ->
                            Pair(false, "Script returned no completion value; it may have navigated")
                        else -> Pair(false, "Script failed: $outcome")
                    }
                }
                ActionTypes.WAIT -> {
                    val raw = action.value
                    val waitTime = raw?.toLongOrNull()
                    if (raw != null && waitTime == null) {
                        // A warning was not enough: the plan asked for a specific settle and got a
                        // different one, and reporting ok says it got what it asked for. "3s" and
                        // "3000ms" are the realistic cases.
                        return Pair(false, "Wait value '$raw' is not a number of milliseconds")
                    }
                    delay(waitTime ?: ActionTiming.DEFAULT_WAIT_MS)
                    Pair(true, null)
                }
                ActionTypes.SCROLL -> {
                    val coords = action.value?.split(",")?.map { it.trim().toIntOrNull() }
                    if (coords?.any { it == null } == true) {
                        // Same reasoning as wait: scrolling somewhere other than where the plan
                        // said, and reporting ok, is the failure this engine exists to remove.
                        return Pair(false, "Scroll value '${action.value}' is not a coordinate or 'x,y'")
                    }
                    // One coordinate means the y axis. Read as x it parsed cleanly, fired no
                    // warning, and scrolled the page *sideways* to 500 while staying at the top -
                    // and "scroll to 500" almost always means down.
                    val x = if (coords != null && coords.size > 1) coords[0] ?: 0 else 0
                    val y = (if (coords != null && coords.size > 1) coords[1] else coords?.get(0)) ?: 0
                    val scrolled =
                        browser.executeJavaScript(
                            "(function () { try { window.scrollTo($x, $y); return true; } " +
                                "catch (e) { return 'threw: ' + e.message; } })();",
                        )
                    delay(ActionTiming.ACTION_SETTLE_MS)
                    if (scrolled.isJsTrue()) Pair(true, null) else Pair(false, "Could not scroll: $scrolled")
                }
                ActionTypes.ASSERT -> {
                    // An assert used to pass on mere existence, dropping `value` silently - so a
                    // plan asserting the wrong text passed, which is the same silent success as
                    // everything else here.
                    val expected = action.value?.takeIf { it.isNotBlank() }
                    val body = if (expected == null) "" else assertTextScript(expected)
                    val failure =
                        if (expected == null) {
                            "Assertion failed: element not found"
                        } else {
                            "Assertion failed: '${action.selector.value}' does not contain '$expected'"
                        }
                    browser.runOn(action.selector, beforeAct, body, failure)
                        ?: return Pair(false, unsupportedSelector(action.selector))
                }
                // Declared in ActionTypes but not doable through `executeJavaScript`, which is the
                // only thing BrowserIntegration offers: a screenshot needs host capture, and
                // frame switching needs a target this api cannot express. Failing says so; the
                // previous `else` slept 500ms and reported success, so an unimplemented verb was
                // indistinguishable from a working one in the log.
                ActionTypes.SCREENSHOT, ActionTypes.SWITCH_FRAME ->
                    Pair(false, "'${action.type}' is not supported when driving a real browser")
                // Needs async polling and reports extra fields, so it lives in TabActions.
                ActionTypes.DOWNLOAD -> Pair(false, "'download' is only available through rpa_step")
                else -> Pair(false, "Unknown action type '${action.type}'")
            }
        } catch (e: CancellationException) {
            // Must come first: CancellationException is a RuntimeException, and every verb has a
            // delay() inside this try - so a cancelled job almost always resumes *into* the catch
            // below. Swallowed, `rpa_stop` appended a phantom FAILED action and left the status
            // ERROR, and a resume was killed by the cancelled job's resumption setting ERROR
            // before the new one could start.
            throw e
        } catch (e: Exception) {
            Pair(false, "Error executing action: ${e.message}")
        }
    }

    /**
     * Run [body] against the first element [selector] names, and report what happened.
     *
     * Returns null when the selector kind is one this engine cannot resolve, so the caller fails
     * with a message rather than silently doing nothing - which is what the old
     * `else -> "// No selector specified"` did, while still reporting success.
     *
     * Otherwise the outcome distinguishes four cases, because collapsing them sends the reader
     * hunting for the wrong thing:
     *  - the body ran: success.
     *  - the body threw: the failure names the exception, not the selector.
     *  - the body returned false, meaning its own check failed (the value did not take, no such
     *    option): [onFailure].
     *  - no completion value came back at all: success. Existence was just proven by
     *    [awaitElement], so this is a click or submit that navigated and tore the frame down
     *    before the value returned - failing it would fail the step that actually worked and stop
     *    the run right at the point it succeeded.
     *
     * Every value crosses into JavaScript through [asJsString]. That is not defensive tidying: a
     * generated plan's very first selector is typically `input[name='q']`, and interpolating it
     * raw produced `document.querySelector('input[name='q']')` - a syntax error. XPath is worse,
     * since `//div[@role='tab']` is full of quotes.
     */
    private suspend fun BrowserIntegration.runOn(
        selector: SelectorInfo,
        beforeAct: ElementHook?,
        body: String,
        onFailure: String,
    ): Pair<Boolean, String?>? {
        val locate =
            when (val target = findTarget(this, selector, beforeAct)) {
                TargetLookup.Unsupported -> return null
                TargetLookup.Missing -> return Pair(false, onFailure)
                is TargetLookup.Found -> target.locate
            }
        // Wrapped: `var` at eval top level lands on the page's global object, so `el` would
        // clobber a page global of that name.
        val outcome =
            executeJavaScript(
                "(function () { var el = $locate; if (!el) { return false; } " +
                    "try { $body } catch (e) { return 'threw: ' + e.message; } return true; })();",
            )
        return interpretOutcome(outcome, onFailure)
    }

    /**
     * Wait for [selector]'s element, then run [beforeAct] on it. The lookup half of [runOn], for
     * verbs that act through more than one script (`download`).
     */
    suspend fun findTarget(
        browser: BrowserIntegration,
        selector: SelectorInfo,
        beforeAct: ElementHook?,
    ): TargetLookup {
        val primary = locateExpression(selector) ?: return TargetLookup.Unsupported
        // A tag-qualified CSS selector gets its tag dropped as an alternative in the SAME probe.
        // Two sequential deadlines made every miss cost 10s and let the fallback win only after
        // the primary had exhausted its own - so it is an alternative, not a retry.
        val stripped = selector.value.orEmpty().stripTagQualifier()
        val fallback =
            if (selector.type == SelectorTypes.CSS && stripped != selector.value) {
                visibleQuerySelector(stripped)
            } else {
                null
            }
        val locate = if (fallback == null) primary else "($primary) || ($fallback)"
        if (!browser.awaitElement(locate)) return TargetLookup.Missing
        beforeAct?.invoke(browser, locate)
        if (fallback != null && !browser.matches(primary)) {
            log(
                LogLevel.WARNING,
                "Selector '${selector.value}' matched nothing; used '$stripped' instead",
            )
        }
        return TargetLookup.Found(locate)
    }

    /**
     * Poll until [locate] resolves to an element, or the timeout expires.
     *
     * Existence only: this must never carry the action's body. Probe and mutation used to be one
     * script, so an eval whose completion value did not come back - a body that threw, or a click
     * that navigated and tore down the frame - re-ran the mutation on every poll, turning one
     * submit into fifty.
     *
     * The interval backs off. A `text` selector resolves by scanning twelve tag names, so a miss
     * at a flat 100ms was fifty full sweeps of the document; backing off costs a little latency on
     * a slow-appearing element and roughly quarters that.
     */
    private suspend fun BrowserIntegration.awaitElement(locate: String): Boolean {
        val deadline = Clock.System.now().toEpochMilliseconds() + ActionTiming.ELEMENT_TIMEOUT_MS
        var interval = ActionTiming.ELEMENT_POLL_MS
        while (true) {
            if (matches(locate)) return true
            if (Clock.System.now().toEpochMilliseconds() >= deadline) return false
            delay(interval)
            interval = (interval * 2).coerceAtMost(ActionTiming.ELEMENT_POLL_MAX_MS)
        }
    }

    /** True when [locate] currently resolves to an element. Side-effect free. */
    private suspend fun BrowserIntegration.matches(locate: String): Boolean =
        executeJavaScript("(function () { return !!($locate); })();").isJsTrue()

    fun unsupportedSelector(selector: SelectorInfo): String =
        "Cannot resolve a '${selector.type}' selector" +
            if (selector.value.isNullOrBlank()) " with no value" else ""
}

/** [ActionRunner.findTarget]'s outcome: the same unresolvable / no-match split as `runOn`. */
internal sealed interface TargetLookup {
    data object Unsupported : TargetLookup
    data object Missing : TargetLookup
    data class Found(val locate: String) : TargetLookup
}

internal object ActionTiming {
    /** After a navigation or form submit, before the next action reads the new page. */
    const val NAVIGATE_SETTLE_MS = 1000L

    /** After a click, keypress or scroll, so handlers run before the next action. */
    const val ACTION_SETTLE_MS = 300L

    /** After typing, which fires input and change handlers. */
    const val INPUT_SETTLE_MS = 200L

    /** A `wait` action with no parseable value. */
    const val DEFAULT_WAIT_MS = 1000L

    /**
     * How long to keep looking for an element before calling the action failed.
     *
     * Deliberately NOT scaled by the execution speed, unlike the inter-action delays: speed
     * is about pacing a run for a watching human, while this is how long a page is given to
     * produce an element. Scaling it would make a fast run fail on a slow site.
     */
    const val ELEMENT_TIMEOUT_MS = 5_000L
    const val ELEMENT_POLL_MS = 100L

    /** The poll interval doubles up to this, so a miss is not fifty full document sweeps. */
    const val ELEMENT_POLL_MAX_MS = 500L
}
