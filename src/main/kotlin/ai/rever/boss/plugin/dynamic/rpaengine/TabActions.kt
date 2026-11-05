package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    const val TAB_BUSY = "TAB_BUSY"
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

internal data class StepArgs(
    val tabId: String,
    val action: RpaActionConfig,
    val highlightMs: Int,
    val allowSensitive: Boolean = false,
    /** Show an agent pointer at the action point (see [PointerSpec]); null = no overlay. */
    val pointer: PointerSpec? = null,
    /** click_at only: Jev's box for the target, to snap a near-miss onto the interactive element. */
    val snap: SnapBox? = null,
    /** screenshot only: remove the pointer/highlight nodes for the capture (default false: the pointer is left alone). */
    val hideOverlay: Boolean = false,
    /** screenshot only: select the tab first when the page reports itself hidden. */
    val ensureVisible: Boolean = false,
    /** click_at / type_text: a mark id (`m12`) from the tab's latest look. */
    val element: String? = null,
)

internal const val OBSERVE_DEFAULT_MAX = 80
internal const val OBSERVE_MAX_LIMIT = 200
internal const val STEP_DEFAULT_HIGHLIGHT_MS = 600
internal const val STEP_MAX_HIGHLIGHT_MS = 2000
internal const val STEP_MAX_WAIT_MS = 10_000L

/** Verbs that only read (or touch nothing on the page): no post-step URL settle. */
internal val STEP_NO_SETTLE_TYPES = setOf(
    CuTypes.SCREENSHOT, CuTypes.PAGE_TEXT, CuTypes.LOCATE_TEXT, CuTypes.FOCUSED, CuTypes.LOOK, CuTypes.POINTER_HIDE, CuTypes.CU_END,
)

internal val STEP_ALLOWED_TYPES = setOf(
    ActionTypes.CLICK, ActionTypes.INPUT, ActionTypes.SELECT, ActionTypes.KEYPRESS,
    ActionTypes.SUBMIT, ActionTypes.SCROLL, ActionTypes.NAVIGATE, ActionTypes.WAIT, ActionTypes.DOWNLOAD,
) + CuTypes.ALL

/** Plan verbs rpa_step refuses by name; anything else outside [STEP_ALLOWED_TYPES] is refused as unknown. */
internal val STEP_REFUSED_TYPES = setOf(
    ActionTypes.RUN_SCRIPT, ActionTypes.SWITCH_FRAME, ActionTypes.ASSERT,
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

private fun JsonObject.optionalBoolean(key: String): Boolean {
    val p = this[key] ?: return false
    if (p is JsonNull) return false
    return (p as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        ?: invalid("'$key' must be a boolean")
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
    val allowSensitive = o.optionalBoolean("allow_sensitive")
    val pointer = parsePointer(o["pointer"])
    val snap = parseSnap(action0(o)?.get("snap") ?: o["snap"])
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
    val raw = when (val v = action["value"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> invalid("'action.value' must be a string")
    }
    // wait is validated trimmed, so it runs trimmed: the runner parses without trimming.
    val value = if (type == ActionTypes.WAIT) raw?.trim() else raw
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
    val element = (action["element"] ?: o["element"]).let { el ->
        when (el) {
            null, is JsonNull -> null
            is JsonPrimitive -> el.takeIf { it.isString }?.content?.trim()?.takeIf(::isMarkId)
                ?: invalid("'element' must be a mark id like m12 from the latest look")
            else -> invalid("'element' must be a mark id like m12 from the latest look")
        }
    }
    if (element != null && type != CuTypes.CLICK_AT && type != CuTypes.TYPE_TEXT) invalid("'element' applies only to click_at and type_text")
    if (type in SELECTOR_REQUIRED && selector.type == SelectorTypes.NONE) {
        invalid("'$type' requires action.selector")
    }
    when (type) {
        ActionTypes.NAVIGATE -> if (value.isNullOrBlank()) invalid("'navigate' requires action.value (a URL)")
        ActionTypes.SELECT -> if (value == null) invalid("'select' requires action.value (an option value or label)")
        ActionTypes.WAIT -> if (value != null) {
            val ms = value.trim().toLongOrNull()
            if (ms == null || ms !in 0..STEP_MAX_WAIT_MS) invalid("'wait' value must be milliseconds between 0 and $STEP_MAX_WAIT_MS")
        }
        CuTypes.SCREENSHOT -> if (value != null && value.trim().toIntOrNull()?.takeIf { it in SHOT_MIN_SIDE..SHOT_MAX_SIDE_LIMIT } == null) {
            invalid("'screenshot' value must be a max side in px between $SHOT_MIN_SIDE and $SHOT_MAX_SIDE_LIMIT")
        }
        CuTypes.CLICK_AT -> if (parsePoint(value) == null && !(element != null && value == null)) invalid("'click_at' requires action.value 'x,y' (CSS px in the viewport), or an element id with an optional x,y fallback")
        CuTypes.LOOK -> try { LookOptions.parse(value) } catch (e: IllegalArgumentException) { invalid(e.message ?: "invalid look options") }
        CuTypes.TYPE_TEXT -> if (value == null) invalid("'type_text' requires action.value (the text to type)")
        CuTypes.KEY -> if (TabKeys.parse(value) == null) invalid("'key' requires action.value, a key name like Enter, Escape, Tab, ArrowDown or ctrl+a")
        CuTypes.SCROLL_BY -> if (parseScrollDelta(value) == null) invalid("'scroll_by' requires action.value 'dy' or 'dx,dy' (integer px)")
        CuTypes.LOCATE_TEXT -> if (value.isNullOrBlank() || value.trim().length > LOCATE_TEXT_MAX) invalid("'locate_text' requires action.value, a phrase of at most $LOCATE_TEXT_MAX characters")
        CuTypes.DIALOG_ANSWER -> if (DialogAnswer.parse(value) == null) invalid("'dialog_answer' requires action.value 'ok', 'cancel' or 'ok:<text>' (text answers a prompt)")
    }
    return StepArgs(
        tabId,
        RpaActionConfig(name = "rpa_step", type = type, selector = selector, value = value),
        highlight,
        allowSensitive,
        pointer,
        snap?.also { if (type != CuTypes.CLICK_AT) invalid("'snap' applies only to click_at") },
        hideOverlay = (action["hide_overlay"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true",
        ensureVisible = (action["ensure_visible"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true",
        element = element,
    )
}

private fun action0(o: JsonObject): JsonObject? = o["action"] as? JsonObject

private fun parseSnap(el: kotlinx.serialization.json.JsonElement?): SnapBox? {
    if (el == null || el is JsonNull) return null
    val o = el as? JsonObject ?: invalid("'snap' must be an object like {\"box\": \"left,top,right,bottom\"}")
    val box = (o["box"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return SnapBox.parse(box) ?: invalid("'snap.box' must be 'left,top,right,bottom' in viewport CSS px, with right > left and bottom > top")
}

private fun parsePointer(el: kotlinx.serialization.json.JsonElement?): PointerSpec? {
    if (el == null || el is JsonNull) return null
    val o = el as? JsonObject ?: invalid("'pointer' must be an object like {\"label\": \"Jev\", \"color\": \"#6D28D9\"}")
    fun str(key: String): String? = when (val v = o[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.takeIf { it.isString }?.content ?: invalid("'pointer.$key' must be a string")
        else -> invalid("'pointer.$key' must be a string")
    }
    val label = str("label")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: POINTER_DEFAULT_LABEL
    val color = str("color")?.trim()?.also { if (!isHexColor(it)) invalid("'pointer.color' must be a hex color like #6D28D9") } ?: POINTER_DEFAULT_COLOR
    return PointerSpec(label.take(POINTER_LABEL_MAX), color)
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
    /** True when something else is on top of the element's centre (a modal, a sticky bar): clicking it would hit that instead. */
    val covered: Boolean = false,
)

@Serializable
internal data class ObserveResult(
    @SerialName("tab_id") val tabId: String,
    val url: String?,
    val title: String?,
    val truncated: Boolean,
    /** The open overlay dialog, when there is one; its elements come first. */
    val modal: ModalInfo? = null,
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
    /** Set only by a successful `screenshot`. */
    val screenshot: ScreenshotInfo? = null,
    /** Set by `focused`, and by `type_text` (the field after typing). */
    val focused: FocusedInfo? = null,
    /** Set only by `page_text`. */
    val text: String? = null,
    /** The browser dialog waiting for `dialog_answer`; present in every result while one is pending. */
    val dialog: DialogInfo? = null,
    /** Dialogs this plugin answered itself since the previous result. */
    @SerialName("dialog_auto") val dialogAuto: List<AutoDialog>? = null,
    /** Set by a click_at whose point moved onto an interactive element. */
    val snapped: SnapInfo? = null,
    /** Set only by `locate_text`. */
    val located: LocatedInfo? = null,
    /** Set by a click_at / type_text that used `element`: the element that was clicked. */
    val clicked: ClickedInfo? = null,
    /** Set only by `look`. */
    val look: LookResult? = null,
    /** Popup windows opened from the tab (or its popups): open ones, and each one that closed, once. */
    val popups: List<PopupInfo>? = null,
    /** Something the caller should know about how the action went, e.g. focus moving between split code boxes. */
    val note: String? = null,
    /** type_text only: false when the typed text had not fully landed in the field by the time cap. */
    @SerialName("typed_complete") val typedComplete: Boolean? = null,
)

@Serializable
internal data class DownloadInfo(
    val url: String,
    val file: String,
    val bytes: Long?,
    /** `blob-click` (fetched, then a temporary `<a download>` clicked) or `direct` (the URL's own link clicked). */
    val method: String,
    /** Always false: the page can trigger a download but cannot see whether the host saved it. */
    @SerialName("save_verified") val saveVerified: Boolean = false,
    val note: String,
)

/** The result as JSON; the computer-use fields appear only when set, so older verbs keep their exact shape. */
internal fun stepResultJson(result: StepResult): String {
    val o = TabJson.encodeToJsonElement(StepResult.serializer(), result) as JsonObject
    return JsonObject(
        o.filterNot { (k, v) -> (k == "screenshot" || k == "focused" || k == "text" || k == "dialog" || k == "dialog_auto" || k == "snapped" || k == "located" || k == "clicked" || k == "look" || k == "popups" || k == "note" || k == "typed_complete") && v is JsonNull }
            .mapValues { (k, v) ->
                when {
                    k == "popups" && v is kotlinx.serialization.json.JsonArray ->
                        kotlinx.serialization.json.JsonArray(v.map { e -> if (e is JsonObject) JsonObject(e.filterNot { it.value is JsonNull }) else e })
                    k == "screenshot" && v is JsonObject -> JsonObject(v.filterNot { (sk, sv) -> (sk == "scroll" || sk == "content_hash" || sk == "url" || sk == "title" || sk == "visible" || sk == "revealed" || sk == "modal") && sv is JsonNull })
                    k == "look" && v is JsonObject -> pruneNulls(v)
                    k == "focused" && v is JsonObject -> JsonObject(v.filterNot { (fk, fv) -> fk == "frame" && fv is JsonNull })
                    (k == "dialog" || k == "snapped" || k == "located" || k == "clicked") && v is JsonObject -> JsonObject(v.filterNot { it.value is JsonNull })
                    else -> v
                }
            }
            .filterNot { (k, v) -> k == "dialog_auto" && v is kotlinx.serialization.json.JsonArray && v.isEmpty() },
    ).toString()
}

internal fun downloadNote(method: String, bytes: Long?): String =
    if (method == DOWNLOAD_METHOD_BLOB && bytes != null) {
        "The page fetched $bytes bytes and triggered a browser download; whether the host saved the file is not confirmed"
    } else {
        "The fetch failed, so the page clicked the same-origin link directly (the browser sends cookies for it); " +
            "whether the host saved the file is not confirmed"
    }

internal const val DOWNLOAD_METHOD_BLOB = "blob-click"
internal const val DOWNLOAD_METHOD_DIRECT = "direct"

/** What the observe script hands back, before ids and labels are finalised here. */
@Serializable
internal data class PageSnapshot(
    val url: String? = null,
    val title: String? = null,
    /** Interactive candidates kept; the script stops at MAX + 1, so this is a lower bound once past MAX. */
    val total: Int = 0,
    /** Standalone images found, before the image cap. They follow the interactive ones in [items]. */
    val imageTotal: Int = 0,
    val items: List<RawElement> = emptyList(),
    val modal: ModalInfo? = null,
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
    val covered: Boolean = false,
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

/**
 * Field names/ids that mark a credential or payment field, matched against [sensitiveNameKey]'s form.
 * Short tokens only as whole words, so passenger/compass/discard/footprint/classname are not flagged.
 * Shared with the observe script.
 */
internal const val SENSITIVE_NAME_PATTERN =
    "password|passwd|passcode|passphrase|pwd|cvv|cvc|card_?(?:num|no)|one_?time_?(?:code|pass)|" +
        "(?:^|_)(?:pass|pin|otp|ssn|card|csc|cc|cc_?num(?:ber)?)(?:_|$)"

/** camelCase split and every non-alphanumeric run folded to `_`, lowercased: `userPass-1` -> `user_pass_1`. */
internal fun sensitiveNameKey(nameOrId: String): String =
    nameOrId.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().replace(Regex("[^a-z0-9]+"), "_")

internal fun looksSensitiveName(nameOrId: String): Boolean =
    Regex(SENSITIVE_NAME_PATTERN).containsMatchIn(sensitiveNameKey(nameOrId))

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
        modal = snapshot.modal?.let { ModalInfo(normalizeLabel(it.label), normalizeLabel(it.role)) },
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
                covered = e.covered,
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

internal const val TEXT_WALK_CHARS = 1000
internal const val TEXT_WALK_NODES = 2000

/**
 * Text of a subtree without its form controls, scripts or styles, read from text nodes so no field
 * value is touched. Bounded ([TEXT_WALK_CHARS], [TEXT_WALK_NODES]) because aria-labelledby can point
 * at a whole region. ES5, and only node properties, so tests can run it on plain objects.
 */
private const val TEXT_OF_JS: String =
    "function textOf(n) { if (n.matches && n.matches(CONTROLS)) { return ''; } " +
        "var out = '', stack = [n], visits = 0; " +
        "while (stack.length && out.length < $TEXT_WALK_CHARS && visits++ < $TEXT_WALK_NODES) { var c = stack.pop(); " +
        "if (c.nodeType === 3) { out += c.nodeValue + ' '; continue; } " +
        "if (c.nodeType !== 1 || (c !== n && c.matches && c.matches(CONTROLS + ',script,style'))) { continue; } " +
        "for (var k = c.childNodes.length - 1; k >= 0; k--) { stack.push(c.childNodes[k]); } } " +
        "return norm(out); } "

/**
 * One xpath step. Unprefixed name tests never match an SVG/MathML element in an HTML document, so a
 * foreign-namespace node is named through local-name().
 */
private const val XPATH_STEP_JS: String =
    "function xpathStep(ns, localName, nodeName, i) { " +
        "if (!ns || ns === 'http://www.w3.org/1999/xhtml') { return String(nodeName).toLowerCase() + '[' + i + ']'; } " +
        "return \"*[local-name()='\" + localName + \"'][\" + i + ']'; } "

/**
 * Pure helper functions of the observe script (no DOM beyond what each takes as an argument), kept
 * apart so tests can evaluate them. ES5 only.
 *
 * Privacy rule: nothing here reads a form control's current value or a contenteditable's text.
 * Labels come from attributes, <label> text, and the visible text of non-entry elements.
 */
internal val OBSERVE_HELPERS_JS: String =
    "var GEN_ID = new RegExp(${GENERATED_ID_PATTERN.asJsString()}); " +
        "var SENSITIVE = new RegExp(${SENSITIVE_NAME_PATTERN.asJsString()}); " +
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
        "function sensitiveKey(s) { return String(s || '').replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9]+/g, '_'); } " +
        "function isSensitive(type, autocomplete, name, id, label) { " +
        "if (type === 'password') { return true; } " +
        "var ac = ' ' + (autocomplete || '').toLowerCase().replace(/\\s+/g, ' ') + ' '; " +
        "if (/ cc-/.test(ac) || / (one-time-code|current-password|new-password) /.test(ac)) { return true; } " +
        "return SENSITIVE.test(sensitiveKey(name)) || SENSITIVE.test(sensitiveKey(id)) || SENSITIVE.test(sensitiveKey(label)); } " +
        IMAGE_HELPERS_JS + TEXT_OF_JS + XPATH_STEP_JS

/**
 * Whether [isSensitive] flags an element. A text field masked in JS often has only its accessible
 * name to go on (`aria-label="Password"`, a `<label>` saying PIN), so a field's aria-label, label
 * text, placeholder and title count too; a link's or button's never do ("Forgot password?").
 * Shared by the observe script and rpa_step's input guard, so the flag and the gate agree.
 * Expects [OBSERVE_HELPERS_JS].
 */
internal const val SENSITIVE_DOM_JS: String =
    "function sensitiveEl(el) { var tag = el.tagName.toLowerCase(), role = el.getAttribute('role'); " +
        "var type = tag === 'input' ? (el.getAttribute('type') || 'text').toLowerCase() : null; " +
        "var field = (tag === 'input' && type !== 'submit' && type !== 'button' && type !== 'reset' && type !== 'image') || " +
        "tag === 'textarea' || el.isContentEditable || role === 'textbox' || role === 'searchbox'; " +
        "var label = ''; if (field) { var lab = (el.labels && el.labels.length) ? el.labels[0] : (el.closest ? el.closest('label') : null); " +
        "label = [el.getAttribute('aria-label'), lab ? textOf(lab) : '', el.getAttribute('placeholder'), el.getAttribute('title')].join(' '); } " +
        "return isSensitive(type, el.getAttribute('autocomplete'), el.getAttribute('name'), el.getAttribute('id'), label); } "

/** DOM-touching image helpers, shared by the observe and download scripts. Expects [OBSERVE_HELPERS_JS]. */
private const val IMAGE_DOM_JS: String =
    "function imgIn(el, tag) { return tag === 'img' ? el : el.querySelector('img'); } " +
        "function absUrl(u) { if (!u) { return null; } try { return new URL(u, document.baseURI).href; } catch (e) { return null; } } " +
        "function bestSrc(img) { return absUrl(pickSrc(img.getAttribute('srcset'), img.currentSrc, img.getAttribute('src'))); } "

/** DOM predicates and label resolution shared by the observe and look scripts. Expects [OBSERVE_HELPERS_JS] in scope. */
internal val OBSERVE_DOM_JS: String =
    "function hasSize(r) { return r.width > 0 && r.height > 0; } " +
        "function styledVisible(el) { var cs = window.getComputedStyle(el); " +
        "if (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse') { return false; } " +
        "return !el.closest('[hidden],[aria-hidden=\"true\"]'); } " +
        "function rendered(el) { return hasSize(el.getBoundingClientRect()) && styledVisible(el); } " +
        "function disabled(el) { if (el.getAttribute('aria-disabled') === 'true') { return true; } " +
        "try { return el.matches(':disabled'); } catch (e) { return false; } } " +
        "var VW = window.innerWidth || document.documentElement.clientWidth; " +
        "var VH = window.innerHeight || document.documentElement.clientHeight; " +
        "function rectInView(r) { return r.bottom > 0 && r.right > 0 && r.top < VH && r.left < VW; } " +
        "function inView(el) { return rectInView(el.getBoundingClientRect()); } " +
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
        "s = norm(el.getAttribute('name')); return s || null; } "

/** The DOM-walking part of the observe script. Expects [OBSERVE_HELPERS_JS] in scope and `MAX`. */
private val OBSERVE_BODY_JS: String =
    OBSERVE_DOM_JS + IMAGE_DOM_JS + SENSITIVE_DOM_JS +
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
        "parts.unshift(xpathStep(n.namespaceURI, n.localName, n.nodeName, i)); } " +
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
        // An open overlay dialog: its elements are listed first, and any element another node sits on top of is flagged.
        MODAL_JS +
        "var modal = findModal(); " +
        "function topAt(x, y) { var l = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)]; " +
        "for (var q = 0; q < l.length; q++) { if (l[q] && !mOvl(l[q])) { return l[q]; } } return null; } " +
        "function coveredEl(el) { var r = el.getBoundingClientRect(), x = Math.round(r.left + r.width / 2), y = Math.round(r.top + r.height / 2); " +
        "if (x < 0 || y < 0 || x >= VW || y >= VH) { return false; } var t = topAt(x, y); " +
        "if (!t || t === el || el.contains(t)) { return false; } " +
        "var lab = t.closest ? t.closest('label') : null; return !(lab && lab.contains(el)); } " +
        // Rects first (cheap) to split in-viewport from the rest; computed style only on candidates
        // taken in that order, stopping at MAX + 1 so `truncated` stays exact without styling the page.
        "var all = document.querySelectorAll(${'$'}SELECTOR), inv = [], outv = [], minv = [], moutv = []; " +
        "for (var i = 0; i < all.length; i++) { var r = all[i].getBoundingClientRect(); " +
        "if (hasSize(r)) { var inM = !!(modal && modal.el.contains(all[i])); (rectInView(r) ? (inM ? minv : inv) : (inM ? moutv : outv)).push(all[i]); } } " +
        "var seen = []; function take(list, v) { for (var i = 0; i < list.length && seen.length <= MAX; i++) { " +
        "var el = list[i]; if (!disabled(el) && styledVisible(el)) { seen.push({ el: el, v: v, i: seen.length }); } } } " +
        "take(minv, true); take(moutv, false); take(inv, true); take(outv, false); var picked = seen.slice(0, MAX); " +
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
        "sensitive: sensitiveEl(el), " +
        "inViewport: c.v, selector: selectorOf(el, tag), covered: coveredEl(el) }; }); " +
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
        "items: items.concat(images), modal: modal ? { label: modal.label, role: modal.role } : null });"

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
 * The panel lists `*.json` files in ~/Downloads as RPA configurations (and those can carry
 * `run_script`), so a download must never plant one there.
 */
internal fun isRefusedDownloadName(file: String): Boolean = file.trimEnd().endsWith(".json", ignoreCase = true)

internal const val DOWNLOAD_JSON_REFUSED =
    "Refusing to save a .json file: ~/Downloads is scanned for RPA configurations"

/** Image extensions a same-origin image may be clicked directly with, when its fetch failed. */
internal const val IMAGE_EXT_PATTERN = "\\.(jpe?g|png|gif|webp|svg|avif|bmp|ico)$"

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
 *
 * [imageOnly] (the target was an `<img>`, whose src can be any URL): the blob must be `image/`, and
 * the direct fallback needs an image extension, so an "image" cannot be a JSON file in disguise.
 */
internal fun downloadStartScript(url: String, file: String, token: String, imageOnly: Boolean = false): String =
    "(function () { try { var url = ${url.asJsString()}, file = ${file.asJsString()}, imageOnly = $imageOnly; " +
        "var reg = window.__rpaDownloads || (window.__rpaDownloads = {}); " +
        "var st = { state: 'pending', bytes: null, error: null, method: null, ctl: null }; reg[${token.asJsString()}] = st; " +
        "function save(href) { var a = document.createElement('a'); a.href = href; a.download = file; a.rel = 'noopener'; " +
        "a.style.display = 'none'; (document.body || document.documentElement).appendChild(a); a.click(); " +
        "window.setTimeout(function () { if (a.parentNode) { a.parentNode.removeChild(a); } }, 1000); } " +
        "function direct() { var same = false; try { same = new URL(url, location.href).origin === location.origin; } catch (e) {} " +
        "if (same && imageOnly && !new RegExp(${IMAGE_EXT_PATTERN.asJsString()}, 'i').test(String(url).split('#')[0].split('?')[0])) { " +
        "st.state = 'failed'; st.error = 'The image could not be fetched and its URL has no image extension'; return; } " +
        "if (same) { save(url); st.method = '$DOWNLOAD_METHOD_DIRECT'; st.state = 'done'; } " +
        "else { st.state = 'failed'; st.error = ${DOWNLOAD_CROSS_ORIGIN_ERROR.asJsString()}; } } " +
        "if (!window.fetch) { direct(); return true; } " +
        "if (window.AbortController) { st.ctl = new AbortController(); } " +
        "fetch(url, { mode: 'cors', credentials: 'omit', signal: st.ctl ? st.ctl.signal : undefined })" +
        ".then(function (r) { if (!r.ok) { throw { http: r.status }; } return r.blob(); })" +
        ".then(function (b) { if (st.state !== 'pending') { return; } " +
        "if (imageOnly && !/^image\\//i.test(b.type || '')) { st.state = 'failed'; " +
        "st.error = 'The target is not an image (' + (b.type || 'unknown type') + ')'; return; } " +
        "var u = URL.createObjectURL(b); save(u); " +
        "window.setTimeout(function () { URL.revokeObjectURL(u); }, $DOWNLOAD_REVOKE_MS); " +
        "st.bytes = b.size; st.method = '$DOWNLOAD_METHOD_BLOB'; st.state = 'done'; }, " +
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

/** Different documents: a fragment-only change (`#section`) is not a navigation. */
internal fun isNavigation(before: String?, after: String?): Boolean =
    before != null && after != null && before.substringBefore('#') != after.substringBefore('#')

/** Settle after the action before reading the URL again. */
internal const val STEP_URL_SETTLE_MS = 300L

/** The shorter settle of the computer-use action verbs: their quiet wait already stops on a location change. */
internal const val STEP_URL_SETTLE_QUIET_MS = 100L

internal val STEP_QUIET_ACTION_TYPES = setOf(CuTypes.CLICK_AT, CuTypes.TYPE_TEXT, CuTypes.KEY, CuTypes.SCROLL_BY, CuTypes.DIALOG_ANSWER)

/** Post-step wait before the URL is read again: none for read-only verbs, 100 ms after a quiet-waited verb, else 300 ms. */
internal fun stepUrlSettleMs(type: String): Long = when (type) {
    in STEP_NO_SETTLE_TYPES -> 0L
    in STEP_QUIET_ACTION_TYPES -> STEP_URL_SETTLE_QUIET_MS
    else -> STEP_URL_SETTLE_MS
}

/**
 * rpa_step's `input` guard: a JS expression over `el`, run by [ActionRunner] in the same script as the
 * typing and before it, so the element checked is the element typed into.
 */
internal val SENSITIVE_GUARD_JS: String =
    "(function (el) { " + OBSERVE_HELPERS_JS + SENSITIVE_DOM_JS + "return sensitiveEl(el); })(el)"

/** [block]'s value, or null when it throws - except cancellation, which always propagates. */
internal suspend inline fun <T> orNullUnlessCancelled(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * [busyTabIds] names the tabs an RPA Engine panel run is currently driving; rpa_step refuses those,
 * since a step landing mid-plan interleaves with the run the way a second rpa_run would.
 */
internal class TabActions(
    private val downloadTimeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
    private val busyTabIds: () -> Set<String> = { emptySet() },
    private val computerUse: ComputerUse = ComputerUse(),
    /** The host's UI thread: selecting a tab changes Compose state. */
    private val uiDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main,
    private val activeTabsProvider: () -> ActiveTabsProvider?,
) {

    // rpa_step results carry the outcome; the runner's fallback warning has nowhere to go.
    private val runner = ActionRunner { _, _ -> }

    // One step at a time per tab: two concurrent steps interleave their awaitElement polls and a
    // highlight overlay. Never evicted: a waiter may hold the old Mutex, and a fresh one would let a
    // second step interleave. One Mutex per tab id ever stepped is cheap.
    private val tabLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(tabId: String): Mutex = tabLocks.getOrPut(tabId) { Mutex() }

    fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "rpa_observe",
            description = OBSERVE_DESCRIPTION,
            inputSchema = OBSERVE_SCHEMA,
            // Not read-only: it reads page content (link text, hrefs, captions) from any tab, including
            // logged-in ones, so the host should ask rather than auto-approve.
            readOnly = false,
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
        val browser = if (splitPopupId(args.tabId) != null) {
            computerUse.popupTarget(args.tabId)?.integration
                ?: throw TabToolException(TabErrorCodes.TAB_NOT_FOUND, "No open popup window '${args.tabId}'")
        } else {
            resolveTab(args.tabId)
        }
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TabToolException(TabErrorCodes.SCRIPT_FAILED, "rpa_observe failed: ${e.message}").toResult()
    }

    suspend fun step(raw: String): McpToolResult = try {
        val args = parseStepArgs(raw)
        val popupOf = splitPopupId(args.tabId)
        val browser = if (popupOf != null) {
            if (args.action.type !in CuTypes.ALL) {
                throw TabToolException(TabErrorCodes.UNSUPPORTED_ACTION, "'${args.action.type}' needs a tab's DOM; use computer-use verbs (screenshot, look, click_at, type_text, key, scroll_by, focused, page_text, locate_text, dialog_answer, cu_end) on popup windows")
            }
            computerUse.popupTarget(args.tabId)?.integration
                ?: throw TabToolException(TabErrorCodes.TAB_NOT_FOUND, "No open popup window '${args.tabId}'")
        } else {
            resolveTab(args.tabId)
        }
        val busyId = popupOf?.first ?: args.tabId
        refuseIfEngineRunning(busyId)
        val result = lockFor(args.tabId).withLock {
            // Re-checked under the lock: a run may have started while this step waited. Best-effort,
            // since a panel run takes no tab lock - but a run creates its own tab, so an agent cannot
            // know that id before the run starts.
            refuseIfEngineRunning(busyId)
            runStep(browser, args)
        }
        McpToolResult(stepResultJson(result))
    } catch (e: TabToolException) {
        e.toResult()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Backstop: a bridge failure outside the runner (a tab closing mid-step) is still structured.
        TabToolException(TabErrorCodes.SCRIPT_FAILED, "rpa_step failed: ${e.message}").toResult()
    }

    /** Brings the tab to the front of its panel so its page is painted and visible. */
    private suspend fun revealTab(tabId: String) {
        val provider = activeTabsProvider() ?: return
        val panel = provider.activeTabs.value.firstOrNull { it.tabId == tabId }?.panelId ?: return
        withContext(uiDispatcher) { provider.selectTab(tabId, panel) }
    }

    private fun refuseIfEngineRunning(tabId: String) {
        if (tabId in runCatching(busyTabIds).getOrDefault(emptySet())) {
            throw TabToolException(
                TabErrorCodes.TAB_BUSY,
                "An RPA Engine run is driving tab '$tabId'; call rpa_stop first or step a different tab",
            )
        }
    }

    private suspend fun runStep(browser: BrowserIntegration, args: StepArgs): StepResult {
        val started = Clock.System.now().toEpochMilliseconds()
        val before = currentUrl(browser)
        val action = args.action
        val highlight = if (args.highlightMs > 0) highlightHook(args.highlightMs) else null
        val glide = args.pointer?.let { computerUse.pointerHook(args.tabId, it, ripple = action.type == ActionTypes.CLICK) }
        val hook: ElementHook? = if (glide == null) highlight else { b, locate -> glide(b, locate); highlight?.invoke(b, locate) }
        // execute() already maps script errors and thrown exceptions to (false, reason).
        // input without the opt-in is guarded in the typing script itself; a guard that throws fails
        // the step without typing.
        val guard = if (args.action.type == ActionTypes.INPUT && !args.allowSensitive) SENSITIVE_GUARD_JS else null
        var cu: CuOutcome? = null
        // A page blocked in alert()/confirm() would hang any script we send; only an answer or cu_end may proceed.
        val waiting = if (action.type != CuTypes.DIALOG_ANSWER && action.type != CuTypes.CU_END) computerUse.pendingDialog(args.tabId) else null
        if (waiting != null) {
            return StepResult(
                ok = false,
                error = "A browser dialog (${waiting.type}) is waiting; call dialog_answer with 'ok', 'cancel' or 'ok:<text>' first",
                urlBefore = before, urlAfter = before, navigated = false,
                durationMs = Clock.System.now().toEpochMilliseconds() - started,
                dialog = waiting,
                popups = computerUse.popupsFor(args.tabId).ifEmpty { null },
                dialogAuto = computerUse.drainAutoDialogs(args.tabId).ifEmpty { null },
            )
        }
        val (ok, error, download) =
            when (action.type) {
                in CuTypes.ALL -> {
                    val lookWantsShown = action.type == CuTypes.LOOK && runCatching { LookOptions.parse(action.value).ensureVisible }.getOrDefault(false)
                    cu = computerUse.run(browser, args.tabId, action, args.pointer, args.allowSensitive, args.snap, args.hideOverlay,
                        reveal = if ((args.ensureVisible || lookWantsShown) && splitPopupId(args.tabId) == null) ({ revealTab(args.tabId) }) else null,
                        element = args.element)
                    Triple(cu.ok, cu.error, null)
                }
                ActionTypes.DOWNLOAD -> download(browser, action.selector, hook)
                else -> runner.execute(browser, action, hook, guard, quiet = { b -> computerUse.quiet(b, args.tabId) }).let { Triple(it.first, it.second, null) }
            }
        if (guard != null && error == GUARD_REFUSED) {
            throw TabToolException(
                TabErrorCodes.INVALID_INPUT,
                "'${args.action.selector.value}' is a password, payment or one-time-code field; " +
                    "pass allow_sensitive: true to type into it",
            )
        }
        stepUrlSettleMs(action.type).let { if (it > 0) delay(it) }
        val after = currentUrl(browser)
        val popupsNow = computerUse.popupsFor(args.tabId).ifEmpty { null }
        val dialogNow = computerUse.pendingDialog(args.tabId)
        return StepResult(
            ok = ok,
            error = error,
            urlBefore = before,
            urlAfter = after,
            navigated = isNavigation(before, after),
            durationMs = Clock.System.now().toEpochMilliseconds() - started,
            download = download,
            screenshot = cu?.screenshot,
            focused = cu?.focused,
            text = cu?.text,
            dialog = dialogNow,
            dialogAuto = computerUse.drainAutoDialogs(args.tabId).ifEmpty { null },
            snapped = cu?.snapped,
            located = cu?.located,
            clicked = cu?.clicked,
            look = cu?.look?.copy(popups = popupsNow, dialog = dialogNow),
            popups = popupsNow,
            note = cu?.note,
            typedComplete = cu?.typedComplete,
        )
    }

    /** Plugin dispose: give every captured tab its host dialog callbacks back. */
    fun dispose() = computerUse.dispose()

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
            if (isRefusedDownloadName(file)) return fail(DOWNLOAD_JSON_REFUSED)
            val token = "d" + UUID.randomUUID().toString().replace("-", "")
            val started = browser.executeJavaScript(downloadStartScript(url, file, token, imageOnly = target.source != "link"))
            if (!started.isJsTrue()) return fail("Could not start the download: $started")
            val status =
                try {
                    awaitDownload(browser, token)
                } catch (e: CancellationException) {
                    abortDownload(browser, token)
                    throw e
                }
            if (status.state != "done") return fail(status.error ?: "The download failed")
            val method = status.method ?: DOWNLOAD_METHOD_BLOB
            Triple(true, null, DownloadInfo(url, file, status.bytes, method, note = downloadNote(method, status.bytes)))
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
        withContext(NonCancellable) { orNullUnlessCancelled { browser.executeJavaScript(downloadAbortScript(token)) } }
    }

    private fun <T> decodeOrNull(result: Any?, serializer: KSerializer<T>): T? =
        (result as? String)?.let { runCatching { TabJson.decodeFromString(serializer, it) }.getOrNull() }

    private fun highlightHook(ms: Int): ElementHook = { b, locate -> highlight(b, locate, ms) }

    private suspend fun highlight(browser: BrowserIntegration, locate: String, ms: Int) {
        val token = "h" + UUID.randomUUID().toString().replace("-", "")
        val drawn = orNullUnlessCancelled { browser.executeJavaScript(highlightScript(locate, token, ms)) }.isJsTrue()
        if (!drawn) return
        try {
            delay(ms.toLong())
        } finally {
            withContext(NonCancellable) {
                orNullUnlessCancelled { browser.executeJavaScript(removeHighlightScript(token)) }
            }
        }
    }

    private suspend fun currentUrl(browser: BrowserIntegration): String? =
        orNullUnlessCancelled { browser.getCurrentUrl() }?.takeIf { it.isNotBlank() }
            ?: (orNullUnlessCancelled { browser.executeJavaScript("location.href") } as? String)

    private fun resolveTab(tabId: String): BrowserIntegration {
        val provider = activeTabsProvider()
            ?: throw TabToolException(TabErrorCodes.NO_BROWSER, "This BOSS build exposes no browser tabs to plugins")
        val browser = runCatching { provider.getBrowserIntegration(tabId) }.getOrNull()
        if (browser == null) {
            val known = runCatching { provider.activeTabs.value.any { it.tabId == tabId } }.getOrDefault(false)
            if (known) throw TabToolException(TabErrorCodes.NO_BROWSER, "Tab '$tabId' is not a browser tab")
            throw TabToolException(TabErrorCodes.TAB_NOT_FOUND, "No tab with id '$tabId'")
        }
        if (!runCatching { browser.isBrowserAvailable() }.getOrDefault(false)) {
            throw TabToolException(TabErrorCodes.NO_BROWSER, "Tab '$tabId' has no live browser")
        }
        return browser
    }

    companion object {
        const val OBSERVE_DESCRIPTION =
            "List the interactive elements (links, buttons, fields, selects, ARIA widgets) currently rendered " +
                "in a browser tab, in-viewport first, then up to 20 large standalone images (role img). Each element " +
                "has a label, role and a selector that rpa_step accepts as-is, and covered true when another node sits on top of its centre; " +
                "modal {label, role} is set when an overlay dialog is open, and its elements come first (act inside it before anything behind it); and an image {src, alt, width, height} " +
                "when it is or contains one. Typed text and selected options are never returned (checkbox/radio " +
                "checked state is); password/payment/OTP fields are flagged sensitive. Shadow DOM and iframe " +
                "contents are not observed. Works on any browser tab by id, including logged-in ones; " +
                "no RPA Engine panel needed."

        const val STEP_DESCRIPTION =
            "Perform exactly one action in a browser tab by id: click, input, select, keypress, submit, scroll, " +
                "navigate, wait or download, with the same semantics as an RPA Engine plan step. download " +
                "(rpa_step only, not a plan verb) fetches the element's image or linked file without cookies " +
                "(so a file behind a login answers HTTP 401/403; only a same-origin link clicked after a failed " +
                "fetch goes with cookies) and triggers a browser download; the result " +
                "reports save_verified false, since the page cannot see whether the host saved it. Selectors are " +
                "{type: id|css|xpath|text, value}; use the ones rpa_observe returns. The target element is briefly " +
                "outlined first (highlight_ms, 0 to disable). Returns ok/error plus the URL before and after; " +
                "navigated ignores a fragment-only change. input into a field rpa_observe flags sensitive is " +
                "refused unless allow_sensitive is true. A tab an RPA Engine run is driving is refused (TAB_BUSY). " +
                "run_script, switch_frame and assert are refused. " +
                "Computer-use verbs act on what is on screen, in viewport CSS px, with real browser input: " +
                "screenshot (value: max side px, default 1100, JPEG quality 0.7) saves a JPEG and returns screenshot " +
                "{path, width, height, css_width, css_height, dpr, scroll, content_hash, url, title, visible, revealed, modal} (the pointer overlay is left as is; action.hide_overlay true removes it for the capture; visible is document.visibilityState: a tab that is not the front one in its panel gives a STALE image, so action.ensure_visible true selects it first (revealed: true) and waits until it is visible; content_hash changes when the page's text or form state changes, not for video or animation); " +
                "click_at (value 'x,y'; optional snap {box: 'left,top,right,bottom'} in viewport CSS px: when no interactive element " +
                "is under the point, the click moves to the centre of the visible interactive element overlapping the box most, and the result adds " +
                "snapped {from, to, tag, label} only when it moved; canvas or no candidate = the raw point); type_text (value: text; selects the focused field's content first, replaces it; " +
                "on a <select> it type-aheads the option); key (value: Enter, Escape, Tab, ArrowDown, ctrl+a, ...); " +
                "scroll_by (value 'dy' or 'dx,dy' px, wheel at the viewport centre); focused (read-only: returns focused " +
                "{tag, editable, label, type, options, sensitive, frame?}, never a value; focus inside an iframe is followed into that frame; type_text adds note when the page moved focus while typing, e.g. split code boxes, returns once the typed text has landed in the field and adds typed_complete: false when it had not by the cap; after a click on a field element, keys wait until it has focus); pointer_hide (removes the overlay); " +
                "page_text (read-only, value: max chars, default 20000, max 100000: returns text = the page's rendered innerText, never field values). " +
                "look (read-only; value: a JSON string of options {max_side: 1100, quality: 0.7, locate: [phrases], elements: true, ensure_visible: false, page_text: false}) returns everything for one decision in one call: " +
                "look {screenshot {...as above}, located {phrase: {found, count, in_view, direction, screens, rect?}}, elements [{id: 'm1'.., role, tag, label, value_kind?: text|empty|checked|unchecked|selected:<option>, rect {x,y,w,h}, disabled, covered, context, editable, href_host?, frame?}], page_text?, popups?, dialog?}; " +
                "look, page_text, locate_text and content_hash also cover iframes (cross-origin and nested, up to 10 frames, 3 deep): a frame element carries frame (a path like '0' or '0.1') and its rect in top-viewport px, elements of a frame scrolled out of view or hidden are left out, page_text adds each frame's text as '[frame <host>]' blocks; " +
                "elements are the interactive ones in the viewport (modal ones first, at most 120; disabled and covered ones are kept and flagged); click_at and type_text accept element: '<id>' from the tab's latest look " +
                "(clicks its current visible centre after a hit-test; ok false with '<label> is disabled' or '<label> is covered by <x>'; a vanished element falls back to value 'x,y' when given; the result adds clicked {id, label, tag}; type_text clicks it first). " +
                "Read-only verbs return without settling; action verbs wait until the page is quiet (no DOM change for 100 ms, at most 800 ms). " +
                "locate_text (read-only, value: a phrase up to 100 chars) finds where the phrase is on the page, case-insensitive, whole words preferred ('item 87' never matches 'Item 870'): " +
                "located {found, count, in_view, direction: above|below|in_view|null, screens (nearest match's distance from the viewport in viewport heights), rect? {x,y,w,h} when in view}; never field values. " +
                "Optional top-level pointer {label, color} shows a labelled agent cursor that glides to the action point " +
                "(the element centre for selector actions) and ripples on click; it works in any tab and stays until pointer_hide. " +
                "Browser dialogs: from a tab's first computer-use verb its alert/confirm/prompt/beforeunload dialogs are held " +
                "(one per tab); while one is pending every result carries dialog {type: alert|confirm|prompt|beforeunload, message, " +
                "default_value?, ok_text?, cancel_text?, url?} and every other verb is refused until dialog_answer (value 'ok', " +
                "'cancel' or 'ok:<text>' for a prompt) answers it. A dialog unanswered for 120 s is cancelled (alert: ok); " +
                "dialog_auto lists dialogs answered automatically. cu_end (value ignored) ends computer use on the tab: " +
                "the host's dialog handlers are restored and the pointer is removed. " +
                "Popup windows (window.open with a size, e.g. sign-in) opened while a tab is in computer use get the tab id '<tabId>#popup-<n>': " +
                "every result for the tab or its popups carries popups [{id, open: true, url, title}] (a closed one appears once as {id, open: false}); " +
                "pass that id as tab_id to use computer-use verbs (screenshot, look, click_at, type_text, key, scroll_by, focused, page_text, locate_text, dialog_answer, cu_end) on the popup. " +
                "Selector verbs (click, input, ...) are not available on popups."

        const val OBSERVE_SCHEMA =
            """{"type":"object","properties":{""" +
                """"tab_id":{"type":"string","description":"Browser tab id (from tabs_list), or a popup id '<tabId>#popup-<n>' from an rpa_step result."},""" +
                """"max_elements":{"type":"integer","minimum":1,"maximum":200,"default":80,"description":"Cap on elements returned; truncated is true when more exist."}""" +
                """},"required":["tab_id"]}"""

        const val STEP_SCHEMA =
            """{"type":"object","properties":{""" +
                """"tab_id":{"type":"string","description":"Browser tab id (from tabs_list)."},""" +
                """"action":{"type":"object","properties":{""" +
                """"type":{"type":"string","enum":["click","input","select","keypress","submit","scroll","navigate","wait","download","screenshot","click_at","type_text","key","scroll_by","focused","pointer_hide","page_text","locate_text","look","cu_end","dialog_answer"]},""" +
                """"selector":{"type":"object","properties":{"type":{"type":"string","enum":["id","css","xpath","text"]},"value":{"type":"string"}},"required":["type","value"],"description":"Required for click, input, select, submit, download; optional for keypress (defaults to the focused element)."},""" +
                """"element":{"type":"string","description":"click_at and type_text: a mark id such as m12 from the tab's latest look; clicks that element's current visible centre."},"ensure_visible":{"type":"boolean","default":false,"description":"screenshot only: when the page reports itself hidden, select its tab first (not for popup windows)."},"hide_overlay":{"type":"boolean","default":false,"description":"screenshot only: remove the pointer overlay while capturing (default: leave it)."},"value":{"type":"string","description":"input: text to type (empty or absent clears the field); select (required): option value or label; keypress: key name (default Enter); navigate: http(s)/about URL; scroll: 'y' or 'x,y'; wait: milliseconds (max 10000); screenshot: max side px (280-4096, default 1100); look: JSON options string (optional); click_at (required unless action.element is given): 'x,y' CSS px in the viewport (the fallback when element is gone); type_text (required): text typed with real key events into the focused element; key (required): Enter|Escape|Tab|ArrowDown|ctrl+a|...; scroll_by (required): 'dy' or 'dx,dy' px, positive = down/right; locate_text (required): phrase; dialog_answer (required): 'ok' | 'cancel' | 'ok:<text>' (text answers a prompt)."}""" +
                """},"required":["type"]},""" +
                """"highlight_ms":{"type":"integer","minimum":0,"maximum":2000,"default":600,"description":"How long to outline the target element before acting; 0 disables."},""" +
                """"allow_sensitive":{"type":"boolean","default":false,"description":"input and type_text: allow typing into a password, payment or one-time-code field. Without it such an input is refused with INVALID_INPUT."},"snap":{"type":"object","properties":{"box":{"type":"string","description":"left,top,right,bottom in viewport CSS px: where the target is believed to be."}},"required":["box"],"description":"click_at only: snap a near-miss onto the interactive element the box overlaps most. Accepted inside action or at top level."},"pointer":{"type":"object","properties":{"label":{"type":"string","description":"Text on the cursor pill, e.g. 'Jev · Research'."},"color":{"type":"string","description":"Hex color, e.g. #6D28D9."}},"description":"Show an agent cursor that glides to the action point first. Absent = no overlay."}""" +
                """},"required":["tab_id","action"]}"""
    }
}
