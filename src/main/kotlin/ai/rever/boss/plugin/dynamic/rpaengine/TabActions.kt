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
import kotlinx.serialization.KSerializer
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
    ActionTypes.SUBMIT, ActionTypes.SCROLL, ActionTypes.NAVIGATE, ActionTypes.WAIT, ActionTypes.DOWNLOAD,
)

/** Plan verbs rpa_step refuses by name; anything else outside [STEP_ALLOWED_TYPES] is refused as unknown. */
internal val STEP_REFUSED_TYPES = setOf(
    ActionTypes.RUN_SCRIPT, ActionTypes.SCREENSHOT, ActionTypes.SWITCH_FRAME, ActionTypes.ASSERT,
)

private val STEP_SELECTOR_TYPES = setOf(SelectorTypes.ID, SelectorTypes.CSS, SelectorTypes.XPATH, SelectorTypes.TEXT)

private val SELECTOR_REQUIRED =
    setOf(ActionTypes.CLICK, ActionTypes.INPUT, ActionTypes.SELECT, ActionTypes.SUBMIT, ActionTypes.DOWNLOAD)

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
internal data class ObservedImage(val src: String?, val alt: String?, val width: Int, val height: Int)

@Serializable
internal data class ObservedElement(
    val id: String,
    val role: String?,
    val tag: String,
    val label: String?,
    val type: String?,
    val placeholder: String?,
    val href: String?,
    val image: ObservedImage?,
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
    /** Set only by a successful `download`. */
    val download: DownloadInfo? = null,
)

@Serializable
internal data class DownloadInfo(val url: String, val file: String, val bytes: Long?, val method: String)

/** What the observe script hands back, before ids and labels are finalised here. */
@Serializable
internal data class PageSnapshot(
    val url: String? = null,
    val title: String? = null,
    val total: Int = 0,
    /** Standalone images found, before the image cap. They follow the interactive ones in [items]. */
    val imageTotal: Int = 0,
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
    val image: RawImage? = null,
    val options: List<String>? = null,
    val checked: Boolean? = null,
    val sensitive: Boolean = false,
    val inViewport: Boolean = false,
    val selector: ObservedSelector,
)

@Serializable
internal data class RawImage(val src: String? = null, val alt: String? = null, val width: Int = 0, val height: Int = 0)

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

/** Longest data: URI reported as an image source; past it the source is null. Shared with the observe script. */
internal const val DATA_URI_MAX = 200

internal fun sanitizeImageSrc(src: String?): String? =
    src?.takeIf { it.isNotBlank() && !(it.startsWith("data:", ignoreCase = true) && it.length > DATA_URI_MAX) }

internal fun buildObserveResult(tabId: String, snapshot: PageSnapshot): ObserveResult =
    ObserveResult(
        tabId = tabId,
        url = snapshot.url,
        title = snapshot.title?.replace(Regex("\\s+"), " ")?.trim(),
        truncated = snapshot.total + snapshot.imageTotal > snapshot.items.size,
        elements = snapshot.items.mapIndexed { i, e ->
            ObservedElement(
                id = "e${i + 1}",
                role = e.role,
                tag = e.tag,
                label = normalizeLabel(e.label),
                type = e.type,
                placeholder = e.placeholder,
                href = e.href,
                image = e.image?.let {
                    ObservedImage(sanitizeImageSrc(it.src), normalizeLabel(it.alt), it.width, it.height)
                },
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

/** An interactive element's image is reported from this rendered size (both sides, CSS px). */
internal const val IMAGE_MIN_PX = 48

/** A standalone `<img>` is reported from this rendered size, up to [OBSERVE_IMAGE_LIMIT] of them. */
internal const val STANDALONE_IMAGE_MIN_PX = 100
internal const val OBSERVE_IMAGE_LIMIT = 20

/** Elements the observe script considers interactive. */
internal const val INTERACTIVE_SELECTOR =
    "a[href],button,input:not([type=hidden]),textarea,select,summary," +
        "[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem]," +
        "[role=option],[role=switch],[role=combobox],[role=searchbox],[role=textbox]," +
        "[contenteditable=true],[contenteditable='']"

/** A path ending in one of these is a file `download` may take from a link's href. Shared with the scripts. */
internal const val DOWNLOAD_EXT_PATTERN = "\\.(jpe?g|png|gif|webp|svg|pdf|zip|csv|xlsx|docx|txt|mp4|mp3)$"

/**
 * Pure URL/srcset helpers for images and downloads, part of [OBSERVE_HELPERS_JS] (`fileLabel` needs
 * its `norm`). ES5 only. A URL segment containing ':' is a wiki namespace page (`File:X.jpg`), never
 * a file.
 */
private val IMAGE_HELPERS_JS: String =
    "var FILE_EXT = new RegExp(${DOWNLOAD_EXT_PATTERN.asJsString()}, 'i'); " +
        "function urlPath(u) { return String(u == null ? '' : u).split('#')[0].split('?')[0]" +
        ".replace(/^[a-z][a-z0-9+.-]*:\\/\\/[^\\/]*/i, '').replace(/^\\/\\/[^\\/]*/, ''); } " +
        "function lastSegment(u) { var p = urlPath(u).replace(/\\/+$/, ''); return p.slice(p.lastIndexOf('/') + 1); } " +
        "function decodeSeg(s) { try { return decodeURIComponent(s); } catch (e) { return s; } } " +
        "function opaqueUrl(u) { return /^\\s*(data|blob|javascript):/i.test(String(u == null ? '' : u)); } " +
        "function fileExt(u) { if (!u || opaqueUrl(u)) { return null; } var seg = decodeSeg(lastSegment(u)); " +
        "if (seg.indexOf(':') !== -1) { return null; } var m = FILE_EXT.exec(seg); return m ? m[1].toLowerCase() : null; } " +
        // File:Persian_cat.jpg -> "Persian cat.jpg"; 330px-X.jpg -> "X.jpg".
        "function fileLabel(u) { if (!u || opaqueUrl(u)) { return ''; } var seg = decodeSeg(lastSegment(u)); " +
        "seg = seg.slice(seg.indexOf(':') + 1).replace(/^\\d+px-/, '').replace(/_/g, ' '); return norm(seg).slice(0, $LABEL_MAX); } " +
        // Largest srcset candidate (w beats x; no descriptor is 1x), else currentSrc, else src.
        "function pickSrc(srcset, currentSrc, src) { var re = /\\s*(\\S+?)(?:\\s+([^,]*))?(?:,|$)/g, set = String(srcset || ''), m, " +
        "w = null, wn = -1, x = null, xn = -1; while ((m = re.exec(set)) !== null) { " +
        "var d = /([\\d.]+)([wx])/.exec(m[2] || ''), n = d ? parseFloat(d[1]) : 1; " +
        "if (d && d[2] === 'w') { if (n > wn) { wn = n; w = m[1]; } } else if (n > xn) { xn = n; x = m[1]; } } " +
        "return w || x || currentSrc || src || null; } " +
        "function safeSrc(u) { if (!u) { return null; } return (/^data:/i.test(u) && u.length > $DATA_URI_MAX) ? null : u; } "

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
        "return SENSITIVE.test(name || '') || SENSITIVE.test(id || ''); } " +
        IMAGE_HELPERS_JS

/** DOM-touching image helpers, shared by the observe and download scripts. Expects [OBSERVE_HELPERS_JS]. */
private const val IMAGE_DOM_JS: String =
    "function imgIn(el, tag) { return tag === 'img' ? el : el.querySelector('img'); } " +
        "function absUrl(u) { if (!u) { return null; } try { return new URL(u, document.baseURI).href; } catch (e) { return null; } } " +
        "function bestSrc(img) { return absUrl(pickSrc(img.getAttribute('srcset'), img.currentSrc, img.getAttribute('src'))); } "

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
        IMAGE_DOM_JS +
        "function imageOf(img, min) { if (!img) { return null; } var r = img.getBoundingClientRect(); " +
        "if (r.width < min || r.height < min) { return null; } " +
        "return { src: safeSrc(bestSrc(img)), alt: norm(img.getAttribute('alt')) || null, " +
        "width: Math.round(r.width), height: Math.round(r.height) }; } " +
        // Only once the accessible-name chain is empty: alt, title, figure caption, then a file name.
        "function imageLabel(el, tag, img) { if (!img) { return null; } " +
        "var s = norm(img.getAttribute('alt')) || norm(img.getAttribute('title')); if (s) { return s; } " +
        "var fig = img.closest('figure'), cap = fig ? fig.querySelector('figcaption') : null; " +
        "if (cap) { s = textOf(cap); if (s) { return s; } } " +
        "s = tag === 'a' ? fileLabel(el.getAttribute('href')) : ''; " +
        "return s || fileLabel(img.currentSrc || img.getAttribute('src')) || null; } " +
        "function byView(a, b) { return (a.v === b.v) ? a.i - b.i : (a.v ? -1 : 1); } " +
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
        "seen.sort(byView); var picked = seen.slice(0, MAX); " +
        "var items = picked.map(function (c) { var el = c.el; " +
        "var tag = el.tagName.toLowerCase(); var img = imgIn(el, tag); " +
        "var type = (tag === 'input' || tag === 'button') ? (el.getAttribute('type') || (tag === 'button' ? 'submit' : 'text')).toLowerCase() : null; " +
        "var explicit = norm(el.getAttribute('role')).split(' ')[0]; " +
        "var role = explicit || (el.isContentEditable && tag !== 'input' && tag !== 'textarea' ? 'textbox' : implicitRole(tag, type)); " +
        "var checked = null; " +
        "if (tag === 'input' && (type === 'checkbox' || type === 'radio')) { checked = !!el.checked; } " +
        "else if (role === 'checkbox' || role === 'radio') { checked = el.getAttribute('aria-checked') === 'true'; } " +
        "var options = null; if (tag === 'select') { options = Array.prototype.slice.call(el.options || [], 0, 30)" +
        ".map(function (o) { return norm(o.label || o.text); }); } " +
        "return { tag: tag, role: role || null, label: labelOf(el, tag, role, type) || imageLabel(el, tag, img), type: type, " +
        "placeholder: el.getAttribute('placeholder'), href: tag === 'a' ? (typeof el.href === 'string' ? el.href : el.getAttribute('href')) : null, " +
        "image: imageOf(img, $IMAGE_MIN_PX), options: options, checked: checked, " +
        "sensitive: isSensitive(type, el.getAttribute('autocomplete'), el.getAttribute('name'), el.getAttribute('id')), " +
        "inViewport: c.v, selector: selectorOf(el, tag) }; }); " +
        // Standalone images come after, with their own cap, so they never displace an interactive element.
        "var pics = [], imgs = document.querySelectorAll('img'); " +
        "for (var k = 0; k < imgs.length; k++) { var im = imgs[k], ir = im.getBoundingClientRect(); " +
        "if (ir.width < $STANDALONE_IMAGE_MIN_PX || ir.height < $STANDALONE_IMAGE_MIN_PX || !rendered(im)) { continue; } " +
        "var inside = false; for (var j = 0; j < picked.length && !inside; j++) { inside = picked[j].el.contains(im); } " +
        "if (!inside) { pics.push({ el: im, v: inView(im), i: pics.length }); } } " +
        "pics.sort(byView); " +
        "var images = pics.slice(0, $OBSERVE_IMAGE_LIMIT).map(function (c) { var el = c.el; " +
        "return { tag: 'img', role: 'img', label: labelOf(el, 'img', 'img', null) || imageLabel(el, 'img', el), type: null, " +
        "placeholder: null, href: null, image: imageOf(el, 0), options: null, checked: null, sensitive: false, " +
        "inViewport: c.v, selector: selectorOf(el, 'img') }; }); " +
        "return JSON.stringify({ url: location.href, title: document.title, total: seen.length, imageTotal: pics.length, " +
        "items: items.concat(images) });"

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
// Download
// ---------------------------------------------------------------------------------------------

internal const val DOWNLOAD_POLL_MS = 200L
internal const val DOWNLOAD_TIMEOUT_MS = 10_000L

/** The blob URL outlives the click by this long, so the browser has read it before it is revoked. */
internal const val DOWNLOAD_REVOKE_MS = 60_000
internal const val DOWNLOAD_FALLBACK_NAME = "download"

internal const val DOWNLOAD_CROSS_ORIGIN_ERROR =
    "The site does not allow downloading this file from script; open it instead"

/** What the target script resolves: the URL to fetch, or why there is none. */
@Serializable
internal data class DownloadTarget(val url: String? = null, val source: String? = null, val error: String? = null)

/** One poll of `window.__rpaDownloads[token]`; state is pending|done|failed, or missing after a navigation. */
@Serializable
internal data class DownloadStatus(
    val state: String,
    val bytes: Long? = null,
    val error: String? = null,
    val method: String? = null,
)

private val DOWNLOADABLE_SCHEME = Regex("(?:https?://|blob:|data:)\\S*", RegexOption.IGNORE_CASE)

internal fun isDownloadableUrl(url: String): Boolean = DOWNLOADABLE_SCHEME.matchEntire(url.trim()) != null

/**
 * The saved file's name: the URL path's last segment, percent-decoded, without a thumbnail size
 * prefix (`330px-`) and with characters no file system accepts replaced. [DOWNLOAD_FALLBACK_NAME]
 * for data:/blob: URLs or when nothing is left.
 */
internal fun downloadFileName(url: String): String {
    val u = url.trim()
    if (Regex("^(data|blob|javascript):", RegexOption.IGNORE_CASE).containsMatchIn(u)) return DOWNLOAD_FALLBACK_NAME
    val path = u.substringBefore('#').substringBefore('?')
        .replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*"), "")
        .replace(Regex("^//[^/]*"), "")
    val name = percentDecode(path.trimEnd('/').substringAfterLast('/'))
        .replace(Regex("^\\d+px-"), "")
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
        .trim().trim('.')
    return name.take(120).ifEmpty { DOWNLOAD_FALLBACK_NAME }
}

/** %XX sequences as UTF-8; a malformed escape is kept as written. `+` stays `+` (this is a path). */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val hi = if (s[i] == '%' && i + 2 <= s.lastIndex) s[i + 1].digitToIntOrNull(16) else null
        val lo = if (hi != null) s[i + 2].digitToIntOrNull(16) else null
        if (hi != null && lo != null) {
            out.write(hi * 16 + lo)
            i += 3
        } else {
            out.write(s[i].toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return out.toString(Charsets.UTF_8)
}

/**
 * Resolves what `download` would fetch from the element [locate] names, side-effect free. A link
 * whose href is a file wins over its image (the href is the full file, the image a thumbnail);
 * otherwise the element's own or first descendant `<img>`, best source.
 */
internal fun downloadTargetScript(locate: String): String =
    "(function () { try { " + OBSERVE_HELPERS_JS + IMAGE_DOM_JS +
        "var el = $locate; if (!el) { return JSON.stringify({ error: 'The element is gone' }); } " +
        "var tag = el.tagName.toLowerCase(), link = el.closest('a[href]'); " +
        "var href = link ? absUrl(link.getAttribute('href')) : null; " +
        "if (href && fileExt(href)) { return JSON.stringify({ url: href, source: 'link' }); } " +
        "var img = imgIn(el, tag), src = img ? safeSrc(bestSrc(img)) : null; " +
        "if (src) { return JSON.stringify({ url: src, source: 'image' }); } " +
        "return JSON.stringify({ error: img ? 'The image has no downloadable source' : 'No image or file link at the target' }); " +
        "} catch (e) { return JSON.stringify({ error: String(e && e.message || e) }); } })()"

/**
 * Starts the download and returns at once: `executeJavaScript` does not await a promise, so
 * progress goes to `window.__rpaDownloads[token]` for [downloadPollScript]. fetch -> blob -> a
 * temporary `<a download>`; when fetch fails outright (CORS), a same-origin URL is clicked directly
 * instead and a cross-origin one fails. Never navigates the page. An HTTP error does not fall back:
 * the server answered, and the direct link would only save its error page.
 */
internal fun downloadStartScript(url: String, file: String, token: String): String =
    "(function () { try { var url = ${url.asJsString()}, file = ${file.asJsString()}; " +
        "var reg = window.__rpaDownloads || (window.__rpaDownloads = {}); " +
        "var st = { state: 'pending', bytes: null, error: null, method: null, ctl: null }; reg[${token.asJsString()}] = st; " +
        "function save(href) { var a = document.createElement('a'); a.href = href; a.download = file; a.rel = 'noopener'; " +
        "a.style.display = 'none'; (document.body || document.documentElement).appendChild(a); a.click(); " +
        "window.setTimeout(function () { if (a.parentNode) { a.parentNode.removeChild(a); } }, 1000); } " +
        "function direct() { var same = false; try { same = new URL(url, location.href).origin === location.origin; } catch (e) {} " +
        "if (same) { save(url); st.method = 'direct'; st.state = 'done'; } " +
        "else { st.state = 'failed'; st.error = ${DOWNLOAD_CROSS_ORIGIN_ERROR.asJsString()}; } } " +
        "if (!window.fetch) { direct(); return true; } " +
        "if (window.AbortController) { st.ctl = new AbortController(); } " +
        "fetch(url, { mode: 'cors', credentials: 'omit', signal: st.ctl ? st.ctl.signal : undefined })" +
        ".then(function (r) { if (!r.ok) { throw { http: r.status }; } return r.blob(); })" +
        ".then(function (b) { if (st.state !== 'pending') { return; } var u = URL.createObjectURL(b); save(u); " +
        "window.setTimeout(function () { URL.revokeObjectURL(u); }, $DOWNLOAD_REVOKE_MS); " +
        "st.bytes = b.size; st.method = 'blob'; st.state = 'done'; }, " +
        "function (e) { if (st.state !== 'pending') { return; } " +
        "if (e && e.http) { st.state = 'failed'; st.error = 'The server answered HTTP ' + e.http; } else { direct(); } }); " +
        "return true; } catch (e) { return 'threw: ' + e.message; } })()"

/** Reads the download's state; a settled entry is removed as it is read. */
internal fun downloadPollScript(token: String): String =
    "(function () { var reg = window.__rpaDownloads, t = ${token.asJsString()}, st = reg && reg[t]; " +
        "if (!st) { return JSON.stringify({ state: 'missing' }); } " +
        "if (st.state !== 'pending') { delete reg[t]; } " +
        "return JSON.stringify({ state: st.state, bytes: st.bytes, error: st.error, method: st.method }); })()"

/** Gives up on a pending download: aborts the fetch, and a late blob is not saved. */
internal fun downloadAbortScript(token: String): String =
    "(function () { var reg = window.__rpaDownloads, t = ${token.asJsString()}, st = reg && reg[t]; " +
        "if (st) { st.state = 'failed'; st.error = 'timeout'; try { if (st.ctl) { st.ctl.abort(); } } catch (e) {} " +
        "delete reg[t]; } return true; })()"

// ---------------------------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------------------------

/** Settle after the action before reading the URL again. */
internal const val STEP_URL_SETTLE_MS = 300L

internal class TabActions(
    private val downloadTimeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
    private val activeTabsProvider: () -> ActiveTabsProvider?,
) {

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
        val (ok, error, download) =
            if (args.action.type == ActionTypes.DOWNLOAD) {
                download(browser, args.action.selector, hook)
            } else {
                runner.execute(browser, args.action, hook).let { Triple(it.first, it.second, null) }
            }
        delay(STEP_URL_SETTLE_MS)
        val after = currentUrl(browser)
        return StepResult(
            ok = ok,
            error = error,
            urlBefore = before,
            urlAfter = after,
            navigated = before != null && after != null && before != after,
            durationMs = Clock.System.now().toEpochMilliseconds() - started,
            download = download,
        )
    }

    /** Same failure semantics as a runner verb: an exception is (false, reason), cancellation rethrows. */
    private suspend fun download(
        browser: BrowserIntegration,
        selector: SelectorInfo,
        hook: ElementHook?,
    ): Triple<Boolean, String?, DownloadInfo?> {
        fun fail(reason: String) = Triple(false, reason, null)
        return try {
            val locate =
                when (val target = runner.findTarget(browser, selector, hook)) {
                    TargetLookup.Unsupported -> return fail(runner.unsupportedSelector(selector))
                    TargetLookup.Missing -> return fail("No element matched ${selector.value}")
                    is TargetLookup.Found -> target.locate
                }
            val target = decodeOrNull(browser.executeJavaScript(downloadTargetScript(locate)), DownloadTarget.serializer())
                ?: return fail("Could not read the download target (the page may be navigating)")
            target.error?.let { return fail(it) }
            val url = target.url?.takeIf(::isDownloadableUrl)
                ?: return fail("The target's URL is not http(s), blob or data")
            val file = downloadFileName(url)
            val token = "d" + Clock.System.now().toEpochMilliseconds().toString(36) + (0..9999).random()
            val started = browser.executeJavaScript(downloadStartScript(url, file, token))
            if (!started.isJsTrue()) return fail("Could not start the download: $started")
            val status =
                try {
                    awaitDownload(browser, token)
                } catch (e: CancellationException) {
                    abortDownload(browser, token)
                    throw e
                }
            if (status.state != "done") return fail(status.error ?: "The download failed")
            Triple(true, null, DownloadInfo(url, file, status.bytes, status.method ?: "blob"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Error executing action: ${e.message}")
        }
    }

    /** Polls until the download settles; a navigation or the timeout comes back as a failed status. */
    private suspend fun awaitDownload(browser: BrowserIntegration, token: String): DownloadStatus {
        val deadline = Clock.System.now().toEpochMilliseconds() + downloadTimeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            delay(DOWNLOAD_POLL_MS)
            val status = decodeOrNull(browser.executeJavaScript(downloadPollScript(token)), DownloadStatus.serializer())
            when (status?.state) {
                "done", "failed" -> return status
                "missing" -> return DownloadStatus("failed", error = "The page navigated before the download finished")
            }
        }
        abortDownload(browser, token)
        return DownloadStatus("failed", error = "The download did not finish within ${downloadTimeoutMs / 1000}s")
    }

    private suspend fun abortDownload(browser: BrowserIntegration, token: String) {
        withContext(NonCancellable) { runCatching { browser.executeJavaScript(downloadAbortScript(token)) } }
    }

    private fun <T> decodeOrNull(result: Any?, serializer: KSerializer<T>): T? =
        (result as? String)?.let { runCatching { TabJson.decodeFromString(serializer, it) }.getOrNull() }

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
                "in a browser tab, in-viewport first, then up to 20 large standalone images (role img). Each element " +
                "has a label, role and a selector that rpa_step accepts as-is, and an image {src, alt, width, height} " +
                "when it is or contains one. Field values are never returned; password/payment/OTP fields are flagged sensitive. " +
                "Works on any browser tab by id; no RPA Engine panel needed."

        const val STEP_DESCRIPTION =
            "Perform exactly one action in a browser tab by id: click, input, select, keypress, submit, scroll, " +
                "navigate, wait or download, with the same semantics as an RPA Engine plan step; download saves " +
                "the element's image or linked file through the browser. Selectors are " +
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
                """"type":{"type":"string","enum":["click","input","select","keypress","submit","scroll","navigate","wait","download"]},""" +
                """"selector":{"type":"object","properties":{"type":{"type":"string","enum":["id","css","xpath","text"]},"value":{"type":"string"}},"required":["type","value"],"description":"Required for click, input, select, submit, download; optional for keypress (defaults to the focused element)."},""" +
                """"value":{"type":"string","description":"input: text to type; select: option value or label; keypress: key name (default Enter); navigate: http(s)/about URL; scroll: 'y' or 'x,y'; wait: milliseconds (max 10000)."}""" +
                """},"required":["type"]},""" +
                """"highlight_ms":{"type":"integer","minimum":0,"maximum":2000,"default":600,"description":"How long to outline the target element before acting; 0 disables."}""" +
                """},"required":["tab_id","action"]}"""
    }
}
