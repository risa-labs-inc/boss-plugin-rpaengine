package ai.rever.boss.plugin.dynamic.rpaengine

import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Execution status for RPA actions
 */
enum class ExecutionStatus {
    IDLE,
    LOADING,
    EXECUTING,
    PAUSED,
    COMPLETED,
    ERROR
}

/**
 * Execution result for tracking action outcomes
 */
data class ActionExecutionResult(
    val actionIndex: Int,
    val actionName: String,
    val success: Boolean,
    val error: String? = null,
    val duration: Long = 0,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

/**
 * Configuration file info
 */
@Serializable
data class ConfigFileInfo(
    val name: String,
    val path: String,
    val lastModified: Long,
    val actionCount: Int = 0
)

/**
 * Selector information for locating elements
 */
@Serializable
data class SelectorInfo(
    val type: String = "xpath", // css, xpath, text, id, none
    val value: String? = null,
    val isUnique: Boolean? = null
)

/**
 * RPA configuration for execution
 */
@Serializable
data class RpaConfiguration(
    val name: String,
    val description: String = "",
    val actions: List<RpaActionConfig>
)

/**
 * RPA action configuration
 */
@Serializable
data class RpaActionConfig(
    val name: String = "",
    val actionType: String = "default", // default, assertion, screenshot, network, custom
    val type: String, // click, input, navigate, wait, select, scroll, switch_frame, run_script, screenshot, assert
    val selector: SelectorInfo,
    val value: String? = null,
    val meta: Map<String, String>? = null
)

/**
 * Execution settings
 */
data class ExecutionSettings(
    val speed: Float = 1.0f, // 0.5 = slow, 1.0 = normal, 2.0 = fast
    val humanLikeMode: Boolean = true,
    val stopOnError: Boolean = true,
    val screenshotOnError: Boolean = false,
    val retryFailedActions: Boolean = false,
    val maxRetries: Int = 3
)

/**
 * Execution log entry
 */
data class ExecutionLogEntry(
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val level: LogLevel,
    val message: String,
    val actionIndex: Int? = null
)

/**
 * Log levels
 */
enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
    DEBUG
}

/**
 * Execution summary
 */
data class ExecutionSummary(
    val totalActions: Int,
    val completedActions: Int,
    val failedActions: Int,
    val skippedActions: Int,
    val totalDuration: Long,
    val startTime: Long,
    val endTime: Long? = null
)

/**
 * Action types
 */
object ActionTypes {
    const val CLICK = "click"
    const val INPUT = "input"
    const val SELECT = "select"
    const val NAVIGATE = "navigate"
    const val WAIT = "wait"
    const val SCROLL = "scroll"
    const val SCREENSHOT = "screenshot"
    const val ASSERT = "assert"
    const val SWITCH_FRAME = "switch_frame"
    const val RUN_SCRIPT = "run_script"

    /** Pressing a key in a focused field - how a search box is submitted on most of the web. */
    const val KEYPRESS = "keypress"

    /** Submitting the form an element belongs to. */
    const val SUBMIT = "submit"

    fun getDisplayName(type: String): String = when (type) {
        CLICK -> "Click"
        INPUT -> "Type Input"
        SELECT -> "Select Option"
        NAVIGATE -> "Navigate"
        WAIT -> "Wait"
        SCROLL -> "Scroll"
        SCREENSHOT -> "Screenshot"
        ASSERT -> "Assert"
        SWITCH_FRAME -> "Switch Frame"
        RUN_SCRIPT -> "Run Script"
        else -> type.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Speed presets
 */
object SpeedPresets {
    val SLOW = 0.5f
    val NORMAL = 1.0f
    val FAST = 1.5f
    val VERY_FAST = 2.0f

    fun getLabel(speed: Float): String = when {
        speed <= 0.5f -> "Slow"
        speed <= 1.0f -> "Normal"
        speed <= 1.5f -> "Fast"
        else -> "Very Fast"
    }
}

/**
 * Selector types
 */
/**
 * Renders [this] as a JavaScript string literal, quotes and all.
 *
 * Selectors and typed values are interpolated into injected script, and both routinely contain
 * quotes: `input[name='q']` and `//div[@role='tab']` are ordinary output from a generated plan and
 * both produced a syntax error when pasted in raw. Escaping backslashes first matters - doing it
 * after would double-escape the ones this adds.
 */
/**
 * Find the configuration [name] refers to: an exact name wins, otherwise the first
 * case-insensitive substring match.
 *
 * Exact-first is the point. The generated plans share a long common prefix
 * (`llm-rpa-open-google-...`), so a substring-only match silently runs a *different*
 * plan than the one that was asked for.
 */
/** A leading tag name in front of an attribute predicate: the `input` of `input[name=q]`. */
private val TAG_QUALIFIED_ATTRIBUTE = Regex("""^[a-zA-Z][a-zA-Z0-9]*(?=\[)""")

/**
 * Drop a leading tag name from a CSS selector that also carries an attribute predicate,
 * so `input[name='q']` becomes `[name='q']`. Returns the selector unchanged when there is
 * no tag to drop, when the tag is not followed by an attribute predicate, or when the
 * selector is a descendant/compound expression where dropping it would change the meaning.
 */
/**
 * JS that presses [key] on `el`, and submits the enclosing form when the key is Enter.
 *
 * A `KeyboardEvent` built in script is untrusted, so the browser runs page handlers but
 * never the *default* action - a synthetic Enter in a search box fires every listener and
 * still leaves the form unsubmitted. `requestSubmit` supplies the missing default, skipped
 * when a handler called `preventDefault` (the page implements Enter itself) and when there
 * is no form.
 */
/**
 * JS expression resolving the element whose visible label is [value].
 *
 * There is no DOM API for "the element with this text", so this scans - and the naive scan
 * picks the wrong node. An ancestor shares its descendant's `innerText`, and `querySelectorAll`
 * returns document order, so a wrapper `div` matches *before* the `a` inside it. Clicking that
 * wrapper does nothing at all and still looks like a success: this is how a plan that clicked
 * Google's "Images" tab reported ok while staying on the web-results page.
 *
 * So: among the matches, prefer a genuinely clickable one, else take the deepest, then step to
 * its nearest enclosing or contained anchor/button.
 */
internal fun textSelectorScript(value: String): String =
    "(function () { var t = ${value.asJsString()}; " +
        "var all = Array.prototype.slice.call(" +
        "document.querySelectorAll('a,button,input,span,div')).filter(function (n) { " +
        "return (n.innerText || n.value || '').trim() === t; }); " +
        "if (!all.length) { return null; } " +
        "var clickable = all.filter(function (n) { " +
        "return n.tagName === 'A' || n.tagName === 'BUTTON' || n.tagName === 'INPUT'; }); " +
        "var el = clickable.length ? clickable[clickable.length - 1] : all[all.length - 1]; " +
        "return (el.closest && el.closest('a,button')) || el.querySelector('a,button') || el; })()"

internal fun keyPressScript(key: String): String {
    val press =
        "var ev = new KeyboardEvent('keydown', { key: ${key.asJsString()}, bubbles: true, " +
            "cancelable: true }); el.dispatchEvent(ev); " +
            "['keypress','keyup'].forEach(function (t) { " +
            "el.dispatchEvent(new KeyboardEvent(t, { key: ${key.asJsString()}, bubbles: true, " +
            "cancelable: true })); });"
    if (key != "Enter") return press
    return press +
        " if (!ev.defaultPrevented && el.form) { " +
        "if (el.form.requestSubmit) { el.form.requestSubmit(); } else { el.form.submit(); } }"
}

internal fun String.stripTagQualifier(): String {
    val trimmed = trim()
    if (trimmed.contains(' ') || trimmed.contains('>') || trimmed.contains(',')) return this
    val stripped = trimmed.replaceFirst(TAG_QUALIFIED_ATTRIBUTE, "")
    return if (stripped == trimmed) this else stripped
}

internal fun List<ConfigFileInfo>.matchByName(name: String): ConfigFileInfo? =
    firstOrNull { it.name == name } ?: firstOrNull { it.name.contains(name, ignoreCase = true) }

internal fun String.asJsString(): String =
    "'" +
        replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r") +
        "'"

object SelectorTypes {
    const val ID = "id"
    const val CSS = "css"
    const val XPATH = "xpath"
    const val TEXT = "text"
    const val NONE = "none"
}
