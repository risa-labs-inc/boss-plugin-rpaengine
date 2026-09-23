package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/*
 * rpa_observe / rpa_step: act on any browser tab by id, with no RPA Engine panel open.
 * Step execution goes through the same ActionRunner the panel's run loop uses.
 */

internal object TabErrorCodes {
    const val INVALID_INPUT = "INVALID_INPUT"
    const val TAB_NOT_FOUND = "TAB_NOT_FOUND"
    const val NO_BROWSER = "NO_BROWSER"
    const val SCRIPT_FAILED = "SCRIPT_FAILED"
    const val UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION"
}

internal class TabToolException(val code: String, message: String) : Exception(message)

internal fun toolErrorJson(code: String, message: String): String =
    buildJsonObject {
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

private fun TabToolException.toResult() = McpToolResult(toolErrorJson(code, message.orEmpty()), isError = true)

// ---------------------------------------------------------------------------------------------
// Argument parsing
// ---------------------------------------------------------------------------------------------

internal data class ObserveArgs(val tabId: String, val maxElements: Int)

internal data class StepArgs(val tabId: String, val action: RpaActionConfig, val highlightMs: Int)

internal const val OBSERVE_DEFAULT_MAX = 80
internal const val OBSERVE_MAX_LIMIT = 200
internal const val STEP_DEFAULT_HIGHLIGHT_MS = 600
internal const val STEP_MAX_HIGHLIGHT_MS = 2000
internal const val STEP_MAX_WAIT_MS = 10_000L

internal val STEP_ALLOWED_TYPES = setOf(
    ActionTypes.CLICK, ActionTypes.INPUT, ActionTypes.SELECT, ActionTypes.KEYPRESS,
    ActionTypes.SUBMIT, ActionTypes.SCROLL, ActionTypes.NAVIGATE, ActionTypes.WAIT,
)

/** Plan verbs rpa_step refuses by name; anything else outside [STEP_ALLOWED_TYPES] is refused as unknown. */
internal val STEP_REFUSED_TYPES = setOf(
    ActionTypes.RUN_SCRIPT, ActionTypes.SCREENSHOT, ActionTypes.SWITCH_FRAME, ActionTypes.ASSERT,
)

private val STEP_SELECTOR_TYPES = setOf(SelectorTypes.ID, SelectorTypes.CSS, SelectorTypes.XPATH, SelectorTypes.TEXT)

private val SELECTOR_REQUIRED = setOf(ActionTypes.CLICK, ActionTypes.INPUT, ActionTypes.SELECT, ActionTypes.SUBMIT)

private fun invalid(message: String): Nothing = throw TabToolException(TabErrorCodes.INVALID_INPUT, message)

private fun parseObject(raw: String): JsonObject =
    runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }) }.getOrNull() as? JsonObject
        ?: invalid("Arguments must be a JSON object")

private fun JsonObject.requiredString(key: String): String {
    val p = this[key] as? JsonPrimitive
    if (p == null || p is JsonNull || !p.isString) invalid("'$key' is required and must be a string")
    return p.content.trim().takeIf { it.isNotEmpty() } ?: invalid("'$key' must not be empty")
}

/** Integer in [range], or [default] when absent/null. Accepts numeric strings; rejects fractions. */
private fun JsonObject.optionalInt(key: String, default: Int, range: IntRange): Int {
    val el = this[key] ?: return default
    if (el is JsonNull) return default
    val n = (el as? JsonPrimitive)?.content?.toIntOrNull()
        ?: invalid("'$key' must be an integer")
    if (n !in range) invalid("'$key' must be between ${range.first} and ${range.last}")
    return n
}

internal fun parseObserveArgs(raw: String): ObserveArgs {
    val o = parseObject(raw)
    return ObserveArgs(
        tabId = o.requiredString("tab_id"),
        maxElements = o.optionalInt("max_elements", OBSERVE_DEFAULT_MAX, 1..OBSERVE_MAX_LIMIT),
    )
}

internal fun parseStepArgs(raw: String): StepArgs {
    val o = parseObject(raw)
    val tabId = o.requiredString("tab_id")
    val highlight = o.optionalInt("highlight_ms", STEP_DEFAULT_HIGHLIGHT_MS, 0..STEP_MAX_HIGHLIGHT_MS)
    val action = o["action"] as? JsonObject ?: invalid("'action' is required and must be an object")
    val type = (action["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
        ?: invalid("'action.type' is required and must be a string")
    if (type in STEP_REFUSED_TYPES) {
        throw TabToolException(TabErrorCodes.UNSUPPORTED_ACTION, "'$type' is not available through rpa_step")
    }
    if (type !in STEP_ALLOWED_TYPES) {
        throw TabToolException(
            TabErrorCodes.UNSUPPORTED_ACTION,
            "Unknown action type '$type'; expected one of ${STEP_ALLOWED_TYPES.joinToString("|")}",
        )
    }
    val value = when (val v = action["value"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> invalid("'action.value' must be a string")
    }
    val selector = when (val s = action["selector"]) {
        null, is JsonNull -> SelectorInfo(type = SelectorTypes.NONE)
        is JsonObject -> {
            val sType = (s["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
            if (sType !in STEP_SELECTOR_TYPES) invalid("'action.selector.type' must be one of id|css|xpath|text")
            val sValue = (s["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (sValue.isNullOrBlank()) invalid("'action.selector.value' is required and must be a non-empty string")
            SelectorInfo(type = sType!!, value = sValue)
        }
        else -> invalid("'action.selector' must be an object")
    }
    if (type in SELECTOR_REQUIRED && selector.type == SelectorTypes.NONE) {
        invalid("'$type' requires action.selector")
    }
    when (type) {
        ActionTypes.NAVIGATE -> if (value.isNullOrBlank()) invalid("'navigate' requires action.value (a URL)")
        ActionTypes.WAIT -> if (value != null) {
            val ms = value.trim().toLongOrNull()
            if (ms == null || ms !in 0..STEP_MAX_WAIT_MS) invalid("'wait' value must be milliseconds between 0 and $STEP_MAX_WAIT_MS")
        }
    }
    return StepArgs(tabId, RpaActionConfig(name = "rpa_step", type = type, selector = selector, value = value), highlight)
}

// ---------------------------------------------------------------------------------------------
// Result shapes
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class ObservedSelector(val type: String, val value: String)

@Serializable
internal data class ObservedElement(
    val id: String,
    val role: String?,
    val tag: String,
    val label: String?,
    val type: String?,
    val placeholder: String?,
    val href: String?,
    val options: List<String>?,
    val checked: Boolean?,
    val sensitive: Boolean,
    @SerialName("in_viewport") val inViewport: Boolean,
    val selector: ObservedSelector,
)

@Serializable
internal data class ObserveResult(
    @SerialName("tab_id") val tabId: String,
    val url: String?,
    val title: String?,
    val truncated: Boolean,
    val elements: List<ObservedElement>,
)

@Serializable
internal data class StepResult(
    val ok: Boolean,
    val error: String?,
    @SerialName("url_before") val urlBefore: String?,
    @SerialName("url_after") val urlAfter: String?,
    val navigated: Boolean,
    @SerialName("duration_ms") val durationMs: Long,
)

/** What the observe script hands back, before ids and labels are finalised here. */
@Serializable
internal data class PageSnapshot(
    val url: String? = null,
    val title: String? = null,
    val total: Int = 0,
    val items: List<RawElement> = emptyList(),
    val error: String? = null,
)

@Serializable
internal data class RawElement(
    val tag: String,
    val role: String? = null,
    val label: String? = null,
    val type: String? = null,
    val placeholder: String? = null,
    val href: String? = null,
    val options: List<String>? = null,
    val checked: Boolean? = null,
    val sensitive: Boolean = false,
    val inViewport: Boolean = false,
    val selector: ObservedSelector,
)

internal val TabJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

internal const val LABEL_MAX = 80

/** Whitespace-collapsed, at most [LABEL_MAX] chars, null when nothing is left. */
internal fun normalizeLabel(raw: String?): String? {
    val collapsed = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (collapsed.isEmpty()) return null
    if (collapsed.length <= LABEL_MAX) return collapsed
    var cut = collapsed.take(LABEL_MAX)
    if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut.trimEnd()
}

/** Ids that look framework-generated and will not survive a reload. Shared with the observe script. */
internal const val GENERATED_ID_PATTERN = "^[0-9]|[0-9a-f]{8,}|:r[0-9a-z]+:"

internal fun looksGeneratedId(id: String): Boolean = Regex(GENERATED_ID_PATTERN).containsMatchIn(id)

/** Field names/ids that mark a credential or payment field. Shared with the observe script (case-insensitive). */
internal const val SENSITIVE_NAME_PATTERN = "pass|pwd|card|cvv|ssn|otp"

internal fun looksSensitiveName(nameOrId: String): Boolean =
    Regex(SENSITIVE_NAME_PATTERN, RegexOption.IGNORE_CASE).containsMatchIn(nameOrId)

internal fun buildObserveResult(tabId: String, snapshot: PageSnapshot): ObserveResult =
    ObserveResult(
        tabId = tabId,
        url = snapshot.url,
        title = snapshot.title?.replace(Regex("\\s+"), " ")?.trim(),
        truncated = snapshot.total > snapshot.items.size,
        elements = snapshot.items.mapIndexed { i, e ->
            ObservedElement(
                id = "e${i + 1}",
                role = e.role,
                tag = e.tag,
                label = normalizeLabel(e.label),
                type = e.type,
                placeholder = e.placeholder,
                href = e.href,
                options = e.options?.mapNotNull(::normalizeLabel),
                checked = e.checked,
                sensitive = e.sensitive,
                inViewport = e.inViewport,
                selector = e.selector,
            )
        },
    )

// ---------------------------------------------------------------------------------------------
// Injected scripts
// ---------------------------------------------------------------------------------------------

/** Elements the observe script considers interactive. */
internal const val INTERACTIVE_SELECTOR =
    "a[href],button,input:not([type=hidden]),textarea,select,summary," +
        "[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem]," +
        "[role=option],[role=switch],[role=combobox],[role=searchbox],[role=textbox]," +
        "[contenteditable=true],[contenteditable='']"

/**
 * Pure helper functions of the observe script (no DOM beyond what each takes as an argument), kept
 * apart so tests can evaluate them. ES5 only.
 *
 * Privacy rule: nothing here reads a form control's current value or a contenteditable's text.
 * Labels come from attributes, <label> text, and the visible text of non-entry elements.
 */
internal val OBSERVE_HELPERS_JS: String =
    "var GEN_ID = new RegExp(${GENERATED_ID_PATTERN.asJsString()}); " +
        "var SENSITIVE = new RegExp(${SENSITIVE_NAME_PATTERN.asJsString()}, 'i'); " +
        "var CONTROLS = 'input,textarea,select,[contenteditable]'; " +
        "function norm(s) { return s == null ? '' : String(s).replace(/\\s+/g, ' ').replace(/^ | $/g, '').slice(0, 200); } " +
        // A CSS attribute value in double quotes. Values with control characters are not used at all.
        "function cssq(s) { return '\"' + String(s).replace(/\\\\/g, '\\\\\\\\').replace(/\"/g, '\\\\\"') + '\"'; } " +
        "function usableAttr(s) { return s != null && s !== '' && s.length <= 120 && !/[\\u0000-\\u001f]/.test(s); } " +
        "function generatedId(id) { return GEN_ID.test(id); } " +
        "function implicitRole(tag, type, multiple) { " +
        "if (tag === 'a') { return 'link'; } " +
        "if (tag === 'button' || tag === 'summary') { return 'button'; } " +
        "if (tag === 'select') { return 'combobox'; } " +
        "if (tag === 'textarea') { return 'textbox'; } " +
        "if (tag === 'input') { switch (type) { " +
        "case 'checkbox': return 'checkbox'; case 'radio': return 'radio'; " +
        "case 'button': case 'submit': case 'reset': case 'image': case 'file': return 'button'; " +
        "case 'search': return 'searchbox'; case 'range': return 'slider'; " +
        "case 'number': return 'spinbutton'; default: return 'textbox'; } } " +
        "return null; } " +
        "function isSensitive(type, autocomplete, name, id) { " +
        "if (type === 'password') { return true; } " +
        "var ac = (autocomplete || '').toLowerCase(); " +
        "if (/(^|\\s)cc-/.test(ac) || ac.indexOf('one-time-code') !== -1) { return true; } " +
        "return SENSITIVE.test(name || '') || SENSITIVE.test(id || ''); } "

/** The DOM-walking part of the observe script. Expects [OBSERVE_HELPERS_JS] in scope and `MAX`. */
private val OBSERVE_BODY_JS: String =
    "function textOf(n) { if (n.matches && n.matches(CONTROLS)) { return ''; } var c = n.cloneNode(true); " +
        "var drop = c.querySelectorAll(CONTROLS + ',script,style'); " +
        "for (var i = 0; i < drop.length; i++) { if (drop[i].parentNode) { drop[i].parentNode.removeChild(drop[i]); } } " +
        "return norm(c.textContent); } " +
        "function rendered(el) { var r = el.getBoundingClientRect(); " +
        "if (!(r.width > 0 && r.height > 0)) { return false; } " +
        "var cs = window.getComputedStyle(el); " +
        "if (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse') { return false; } " +
        "return !el.closest('[hidden],[aria-hidden=\"true\"]'); } " +
        "function disabled(el) { if (el.getAttribute('aria-disabled') === 'true') { return true; } " +
        "try { return el.matches(':disabled'); } catch (e) { return false; } } " +
        "function inView(el) { var r = el.getBoundingClientRect(); " +
        "var w = window.innerWidth || document.documentElement.clientWidth; " +
        "var h = window.innerHeight || document.documentElement.clientHeight; " +
        "return r.bottom > 0 && r.right > 0 && r.top < h && r.left < w; } " +
        "function entry(el, tag, role) { return tag === 'input' || tag === 'textarea' || tag === 'select' || " +
        "el.isContentEditable || role === 'textbox' || role === 'searchbox' || role === 'combobox'; } " +
        "function labelOf(el, tag, role, type) { var s; " +
        "s = norm(el.getAttribute('aria-label')); if (s) { return s; } " +
        "var lb = el.getAttribute('aria-labelledby'); if (lb) { " +
        "s = norm(lb.split(/\\s+/).map(function (id) { var n = document.getElementById(id); " +
        "return n ? textOf(n) : ''; }).join(' ')); if (s) { return s; } } " +
        "var lab = (el.labels && el.labels.length) ? el.labels[0] : el.closest('label'); " +
        "if (lab) { s = textOf(lab); if (s) { return s; } } " +
        "s = norm(el.getAttribute('placeholder')); if (s) { return s; } " +
        "s = norm(el.getAttribute('alt') || el.getAttribute('title')); if (s) { return s; } " +
        // An input button's value attribute is its visible caption, not user data.
        "if (tag === 'input' && (type === 'submit' || type === 'button' || type === 'reset')) { " +
        "s = norm(el.getAttribute('value')); if (s) { return s; } } " +
        "if (!entry(el, tag, role)) { " +
        "s = el.querySelector(CONTROLS) ? textOf(el) : norm(el.innerText); if (s) { return s; } " +
        "var img = el.querySelector('img[alt]'); if (img) { s = norm(img.getAttribute('alt')); if (s) { return s; } } } " +
        "s = norm(el.getAttribute('name')); return s || null; } " +
        "function uniqueCss(sel, el) { try { var m = document.querySelectorAll(sel); " +
        "return m.length === 1 && m[0] === el; } catch (e) { return false; } } " +
        "function xpathOf(el) { var parts = []; " +
        "for (var n = el; n && n.nodeType === 1; n = n.parentNode) { var i = 1; " +
        "for (var s = n.previousSibling; s; s = s.previousSibling) { " +
        "if (s.nodeType === 1 && s.nodeName === n.nodeName) { i++; } } " +
        "parts.unshift(n.nodeName.toLowerCase() + '[' + i + ']'); } " +
        "return '/' + parts.join('/'); } " +
        "function selectorOf(el, tag) { var id = el.getAttribute('id'); " +
        "if (usableAttr(id) && !generatedId(id) && document.getElementById(id) === el && " +
        "uniqueCss('[id=' + cssq(id) + ']', el)) { return { type: 'id', value: id }; } " +
        "var attrs = ['data-testid', 'name', 'aria-label', 'placeholder']; " +
        "for (var i = 0; i < attrs.length; i++) { var v = el.getAttribute(attrs[i]); " +
        "if (usableAttr(v)) { var sel = tag + '[' + attrs[i] + '=' + cssq(v) + ']'; " +
        "if (uniqueCss(sel, el)) { return { type: 'css', value: sel }; } } } " +
        "var ty = el.getAttribute('type'), nm = el.getAttribute('name'); " +
        "if (usableAttr(ty) && usableAttr(nm)) { var tn = tag + '[type=' + cssq(ty) + '][name=' + cssq(nm) + ']'; " +
        "if (uniqueCss(tn, el)) { return { type: 'css', value: tn }; } } " +
        "return { type: 'xpath', value: xpathOf(el) }; } " +
        "var all = Array.prototype.slice.call(document.querySelectorAll(${'$'}SELECTOR)); " +
        "var seen = []; for (var i = 0; i < all.length; i++) { var el = all[i]; " +
        "if (!disabled(el) && rendered(el)) { seen.push({ el: el, v: inView(el), i: seen.length }); } } " +
        "seen.sort(function (a, b) { return (a.v === b.v) ? a.i - b.i : (a.v ? -1 : 1); }); " +
        "var items = seen.slice(0, MAX).map(function (c) { var el = c.el; " +
        "var tag = el.tagName.toLowerCase(); " +
        "var type = (tag === 'input' || tag === 'button') ? (el.getAttribute('type') || (tag === 'button' ? 'submit' : 'text')).toLowerCase() : null; " +
        "var explicit = norm(el.getAttribute('role')).split(' ')[0]; " +
        "var role = explicit || (el.isContentEditable && tag !== 'input' && tag !== 'textarea' ? 'textbox' : implicitRole(tag, type)); " +
        "var checked = null; " +
        "if (tag === 'input' && (type === 'checkbox' || type === 'radio')) { checked = !!el.checked; } " +
        "else if (role === 'checkbox' || role === 'radio') { checked = el.getAttribute('aria-checked') === 'true'; } " +
        "var options = null; if (tag === 'select') { options = Array.prototype.slice.call(el.options || [], 0, 30)" +
        ".map(function (o) { return norm(o.label || o.text); }); } " +
        "return { tag: tag, role: role || null, label: labelOf(el, tag, role, type), type: type, " +
        "placeholder: el.getAttribute('placeholder'), href: tag === 'a' ? (typeof el.href === 'string' ? el.href : el.getAttribute('href')) : null, " +
        "options: options, checked: checked, " +
        "sensitive: isSensitive(type, el.getAttribute('autocomplete'), el.getAttribute('name'), el.getAttribute('id')), " +
        "inViewport: c.v, selector: selectorOf(el, tag) }; }); " +
        "return JSON.stringify({ url: location.href, title: document.title, total: seen.length, items: items });"

/**
 * One JS expression that snapshots the interactive elements of the page, as `JSON.stringify`
 * output matching [PageSnapshot]. Never reads field values.
 */
internal fun observeScript(maxElements: Int): String =
    "(function () { try { var MAX = ${maxElements.coerceIn(1, OBSERVE_MAX_LIMIT)}; " +
        OBSERVE_HELPERS_JS +
        OBSERVE_BODY_JS.replace("\$SELECTOR", INTERACTIVE_SELECTOR.asJsString()) +
        " } catch (e) { return JSON.stringify({ error: String(e && e.message || e) }); } })()"

internal const val HIGHLIGHT_COLOR = "#3592C4"
internal const val HIGHLIGHT_ATTR = "data-rpa-highlight"

/**
 * Draws a fixed-position outline + "RPA" tag over the element [locate] resolves to. Returns true
 * when drawn. The overlay ignores pointer events, and removes itself after [ms] + 1s even if the
 * explicit removal never arrives.
 */
internal fun highlightScript(locate: String, token: String, ms: Int): String =
    "(function () { try { var el = $locate; " +
        "if (!el || el === document.body || el === document.documentElement || !el.getBoundingClientRect) { return false; } " +
        "var r = el.getBoundingClientRect(); if (!(r.width > 0 || r.height > 0)) { return false; } " +
        "var reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); " +
        "var box = document.createElement('div'); box.setAttribute('$HIGHLIGHT_ATTR', ${token.asJsString()}); " +
        "box.style.cssText = 'position:fixed;margin:0;padding:0;box-sizing:border-box;background:transparent;" +
        "pointer-events:none;z-index:2147483647;outline:2px solid $HIGHLIGHT_COLOR;outline-offset:0;" +
        "left:' + (r.left - 2) + 'px;top:' + (r.top - 2) + 'px;width:' + (r.width + 4) + 'px;height:' + (r.height + 4) + 'px;' + " +
        "(reduce ? '' : 'opacity:0;transition:opacity 120ms ease-out;'); " +
        "var tag = document.createElement('div'); tag.textContent = 'RPA'; " +
        "tag.style.cssText = 'position:absolute;left:-2px;' + (r.top < 20 ? 'top:0;' : 'top:-18px;') + " +
        "'margin:0;padding:0 4px;font:600 11px/16px system-ui,sans-serif;color:#fff;background:$HIGHLIGHT_COLOR;border-radius:2px;'; " +
        "box.appendChild(tag); document.documentElement.appendChild(box); " +
        "if (!reduce) { window.requestAnimationFrame(function () { box.style.opacity = '1'; }); } " +
        "window.setTimeout(function () { if (box.parentNode) { box.parentNode.removeChild(box); } }, ${ms + 1000}); " +
        "return true; } catch (e) { return false; } })()"

internal fun removeHighlightScript(token: String): String =
    "(function () { var n = document.querySelectorAll('[$HIGHLIGHT_ATTR]'); var t = ${token.asJsString()}; " +
        "for (var i = 0; i < n.length; i++) { if (n[i].getAttribute('$HIGHLIGHT_ATTR') === t && n[i].parentNode) { " +
        "n[i].parentNode.removeChild(n[i]); } } return true; })()"

// ---------------------------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------------------------

/** Settle after the action before reading the URL again. */
internal const val STEP_URL_SETTLE_MS = 300L

internal class TabActions(private val activeTabsProvider: () -> ActiveTabsProvider?) {

    // rpa_step results carry the outcome; the runner's fallback warning has nowhere to go.
    private val runner = ActionRunner { _, _ -> }

    fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "rpa_observe",
            description = OBSERVE_DESCRIPTION,
            inputSchema = OBSERVE_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args -> observe(args.raw) },
        ),
        McpToolDefinition(
            name = "rpa_step",
            description = STEP_DESCRIPTION,
            inputSchema = STEP_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> step(args.raw) },
        ),
    )

    suspend fun observe(raw: String): McpToolResult = try {
        val args = parseObserveArgs(raw)
        val browser = resolveTab(args.tabId)
        val result = try {
            browser.executeJavaScript(observeScript(args.maxElements))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TabToolException(TabErrorCodes.SCRIPT_FAILED, "Observe script threw: ${e.message}")
        }
        val text = (result as? String)?.takeIf { it.isNotBlank() }
            ?: throw TabToolException(
                TabErrorCodes.SCRIPT_FAILED,
                "Observe script returned nothing (the page may be navigating); retry",
            )
        val snapshot = runCatching { TabJson.decodeFromString(PageSnapshot.serializer(), text) }.getOrElse {
            throw TabToolException(TabErrorCodes.SCRIPT_FAILED, "Observe script returned unreadable output")
        }
        snapshot.error?.let { throw TabToolException(TabErrorCodes.SCRIPT_FAILED, "Observe script failed: $it") }
        McpToolResult(TabJson.encodeToString(ObserveResult.serializer(), buildObserveResult(args.tabId, snapshot)))
    } catch (e: TabToolException) {
        e.toResult()
    }

    suspend fun step(raw: String): McpToolResult = try {
        val args = parseStepArgs(raw)
        val browser = resolveTab(args.tabId)
        McpToolResult(TabJson.encodeToString(StepResult.serializer(), runStep(browser, args)))
    } catch (e: TabToolException) {
        e.toResult()
    }

    private suspend fun runStep(browser: BrowserIntegration, args: StepArgs): StepResult {
        val started = Clock.System.now().toEpochMilliseconds()
        val before = currentUrl(browser)
        val hook = if (args.highlightMs > 0) highlightHook(args.highlightMs) else null
        // execute() already maps script errors and thrown exceptions to (false, reason).
        val (ok, error) = runner.execute(browser, args.action, hook)
        delay(STEP_URL_SETTLE_MS)
        val after = currentUrl(browser)
        return StepResult(
            ok = ok,
            error = error,
            urlBefore = before,
            urlAfter = after,
            navigated = before != null && after != null && before != after,
            durationMs = Clock.System.now().toEpochMilliseconds() - started,
        )
    }

    private fun highlightHook(ms: Int): ElementHook = { b, locate -> highlight(b, locate, ms) }

    private suspend fun highlight(browser: BrowserIntegration, locate: String, ms: Int) {
        val token = "h" + Clock.System.now().toEpochMilliseconds().toString(36) + (0..9999).random()
        val drawn = runCatching { browser.executeJavaScript(highlightScript(locate, token, ms)) }
            .getOrNull().isJsTrue()
        if (!drawn) return
        try {
            delay(ms.toLong())
        } finally {
            withContext(NonCancellable) {
                runCatching { browser.executeJavaScript(removeHighlightScript(token)) }
            }
        }
    }

    private suspend fun currentUrl(browser: BrowserIntegration): String? =
        runCatching { browser.getCurrentUrl() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: (runCatching { browser.executeJavaScript("location.href") }.getOrNull() as? String)

    private fun resolveTab(tabId: String): BrowserIntegration {
        val provider = activeTabsProvider()
            ?: throw TabToolException(TabErrorCodes.NO_BROWSER, "This BOSS build exposes no browser tabs to plugins")
        val browser = runCatching { provider.getBrowserIntegration(tabId) }.getOrNull()
        if (browser == null) {
            val known = runCatching { provider.activeTabs.value.any { it.tabId == tabId } }.getOrDefault(false)
            if (known) throw TabToolException(TabErrorCodes.NO_BROWSER, "Tab '$tabId' is not a browser tab")
            throw TabToolException(TabErrorCodes.TAB_NOT_FOUND, "No tab with id '$tabId'")
        }
        if (!browser.isBrowserAvailable()) {
            throw TabToolException(TabErrorCodes.NO_BROWSER, "Tab '$tabId' has no live browser")
        }
        return browser
    }

    companion object {
        const val OBSERVE_DESCRIPTION =
            "List the interactive elements (links, buttons, fields, selects, ARIA widgets) currently rendered " +
                "in a browser tab, in-viewport first. Each element has a label, role and a selector that rpa_step " +
                "accepts as-is. Field values are never returned; password/payment/OTP fields are flagged sensitive. " +
                "Works on any browser tab by id; no RPA Engine panel needed."

        const val STEP_DESCRIPTION =
            "Perform exactly one action in a browser tab by id: click, input, select, keypress, submit, scroll, " +
                "navigate or wait, with the same semantics as an RPA Engine plan step. Selectors are " +
                "{type: id|css|xpath|text, value}; use the ones rpa_observe returns. The target element is briefly " +
                "outlined first (highlight_ms, 0 to disable). Returns ok/error plus the URL before and after. " +
                "run_script, screenshot, switch_frame and assert are refused."

        const val OBSERVE_SCHEMA =
            """{"type":"object","properties":{""" +
                """"tab_id":{"type":"string","description":"Browser tab id (from tabs_list)."},""" +
                """"max_elements":{"type":"integer","minimum":1,"maximum":200,"default":80,"description":"Cap on elements returned; truncated is true when more exist."}""" +
                """},"required":["tab_id"]}"""

        const val STEP_SCHEMA =
            """{"type":"object","properties":{""" +
                """"tab_id":{"type":"string","description":"Browser tab id (from tabs_list)."},""" +
                """"action":{"type":"object","properties":{""" +
                """"type":{"type":"string","enum":["click","input","select","keypress","submit","scroll","navigate","wait"]},""" +
                """"selector":{"type":"object","properties":{"type":{"type":"string","enum":["id","css","xpath","text"]},"value":{"type":"string"}},"required":["type","value"],"description":"Required for click, input, select, submit; optional for keypress (defaults to the focused element)."},""" +
                """"value":{"type":"string","description":"input: text to type; select: option value or label; keypress: key name (default Enter); navigate: http(s)/about URL; scroll: 'y' or 'x,y'; wait: milliseconds (max 10000)."}""" +
                """},"required":["type"]},""" +
                """"highlight_ms":{"type":"integer","minimum":0,"maximum":2000,"default":600,"description":"How long to outline the target element before acting; 0 disables."}""" +
                """},"required":["tab_id","action"]}"""
    }
}
