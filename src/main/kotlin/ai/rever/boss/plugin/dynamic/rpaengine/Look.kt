package ai.rever.boss.plugin.dynamic.rpaengine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The `look` verb: one rpa_step that returns what one decision needs (screenshot + page meta + located phrases +
 * numbered in-viewport elements + optional page text), read with one page script plus the bitmap.
 * Also the mark resolver behind click_at / type_text `element: "m12"`.
 */

internal const val LOOK_MAX_ELEMENTS = 120
internal const val LOOK_MAX_PHRASES = 20
internal const val LOOK_DEFAULT_QUALITY = SHOT_JPEG_QUALITY

/** The options string of a `look` step (its `value`). Every key is optional. */
internal data class LookOptions(
    val maxSide: Int = SHOT_DEFAULT_MAX_SIDE,
    val quality: Double = LOOK_DEFAULT_QUALITY,
    val locate: List<String> = emptyList(),
    val elements: Boolean = true,
    val ensureVisible: Boolean = false,
    val pageText: Boolean = false,
) {
    companion object {
        /** Blank = defaults. Throws IllegalArgumentException with a caller-readable message when malformed. */
        fun parse(value: String?): LookOptions {
            if (value.isNullOrBlank()) return LookOptions()
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value) }.getOrNull() as? JsonObject
                ?: throw IllegalArgumentException("'look' value must be a JSON object like {\"max_side\":1100,\"quality\":0.7,\"locate\":[\"phrase\"],\"elements\":true,\"ensure_visible\":true,\"page_text\":false}")
            fun prim(k: String): JsonPrimitive? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
            fun bool(k: String, d: Boolean): Boolean {
                val p = prim(k) ?: return d
                return p.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: throw IllegalArgumentException("look '$k' must be a boolean")
            }
            val side = prim("max_side")?.let {
                it.takeIf { p -> !p.isString }?.content?.toIntOrNull()?.takeIf { n -> n in SHOT_MIN_SIDE..SHOT_MAX_SIDE_LIMIT }
                    ?: throw IllegalArgumentException("look 'max_side' must be an integer between $SHOT_MIN_SIDE and $SHOT_MAX_SIDE_LIMIT")
            } ?: SHOT_DEFAULT_MAX_SIDE
            val quality = prim("quality")?.let {
                it.takeIf { p -> !p.isString }?.content?.toDoubleOrNull()?.takeIf { q -> q > 0.0 && q <= 1.0 }
                    ?: throw IllegalArgumentException("look 'quality' must be a number in (0, 1]")
            } ?: LOOK_DEFAULT_QUALITY
            val locate = when (val l = o["locate"]) {
                null, is JsonNull -> emptyList()
                is kotlinx.serialization.json.JsonArray -> l.map { e ->
                    (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= LOCATE_TEXT_MAX }
                        ?: throw IllegalArgumentException("look 'locate' entries must be non-empty strings of at most $LOCATE_TEXT_MAX characters")
                }.distinct().also { if (it.size > LOOK_MAX_PHRASES) throw IllegalArgumentException("look 'locate' takes at most $LOOK_MAX_PHRASES phrases") }
                else -> throw IllegalArgumentException("look 'locate' must be an array of strings")
            }
            return LookOptions(side, quality, locate, bool("elements", true), bool("ensure_visible", false), bool("page_text", false))
        }
    }
}

@Serializable
internal data class LookRect(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable
internal data class LookElement(
    val id: String,
    val role: String,
    val tag: String,
    val label: String,
    /** text | empty | checked | unchecked | selected:<option label>; never a typed value. */
    @SerialName("value_kind") val valueKind: String? = null,
    val rect: LookRect,
    val disabled: Boolean,
    val covered: Boolean,
    val context: String,
    val editable: Boolean,
    @SerialName("href_host") val hrefHost: String? = null,
    /** The frame path ("0", "0.1") of an element inside an iframe; absent for the top document. */
    val frame: String? = null,
    /** True on the element that has keyboard focus (in its frame, and that frame is the focused one); absent otherwise. */
    val focused: Boolean? = null,
)

@Serializable
internal data class LookResult(
    val screenshot: ScreenshotInfo,
    val located: Map<String, LocatedInfo>,
    val elements: List<LookElement>,
    @SerialName("page_text") val pageText: String? = null,
    val popups: List<PopupInfo>? = null,
    val dialog: DialogInfo? = null,
    /** Every child frame seen: its url, matched iframe, and why it is not in the look (null when it is). */
    @SerialName("frames_debug") val framesDebug: List<FrameDebug>? = null,
)

/** What the page script hands back per element; [xp] is the stable path kept for re-resolution and never returned. */
@Serializable
internal data class RawLookElement(
    val id: String,
    val role: String = "",
    val tag: String,
    val label: String = "",
    @SerialName("value_kind") val valueKind: String? = null,
    val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0,
    val disabled: Boolean = false,
    val covered: Boolean = false,
    val context: String = "",
    val editable: Boolean = false,
    @SerialName("href_host") val hrefHost: String? = null,
    val xp: String? = null,
    /** Inside its document's open modal. */
    val mod: Boolean = false,
    /** Its document's activeElement. */
    val focused: Boolean = false,
)

private val MARK_ID = Regex("m[0-9]{1,4}")
internal fun isMarkId(s: String): Boolean = MARK_ID.matches(s)

internal fun RawLookElement.toElement(): LookElement = LookElement(
    id = id, role = role.ifEmpty { tag }, tag = tag, label = normalizeLabel(label).orEmpty(),
    valueKind = valueKind?.take(70), rect = LookRect(x, y, w, h), disabled = disabled, covered = covered,
    context = normalizeLabel(context).orEmpty(), editable = editable, hrefHost = hrefHost?.take(100),
)

/**
 * What rpaengine remembers about one element of the latest look, to find it again: [localId] is its key in its own
 * document's window.__rpaMarks, [frame] the frame it lives in (null for the top document).
 */
internal class MarkRef(val xpath: String?, val label: String, val tag: String, val localId: String? = null, val frame: FrameView? = null)

// ---------------------------------------------------------------------------------------------
// Page scripts
// ---------------------------------------------------------------------------------------------

private val NO_TEXT_TYPES = "{button:1,submit:1,reset:1,image:1,file:1,checkbox:1,radio:1,range:1,color:1,hidden:1}"

/** JS shared by the elements and mark scripts. Expects [OBSERVE_HELPERS_JS], [OBSERVE_DOM_JS] and [MODAL_JS] in scope. */
private const val MARK_COMMON_JS: String = SHADOW_JS +
    "function visRect(r) { var l = Math.max(r.left, 0), t = Math.max(r.top, 0), rr = Math.min(r.right, VW), b = Math.min(r.bottom, VH); " +
        "return (rr > l && b > t) ? { l: l, t: t, r: rr, b: b } : null; } " +
        // The top node at a point, descending into open shadow roots (the document only answers with the host).
        "function firstOf(l) { for (var q = 0; q < l.length; q++) { if (l[q] && !mOvl(l[q])) { return l[q]; } } return null; } " +
        "function topAt(x, y) { var t = firstOf(document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)]); " +
        "for (var d = 0; t && t.shadowRoot && d < 8; d++) { var s = t.shadowRoot; " +
        "var inner = firstOf(s.elementsFromPoint ? s.elementsFromPoint(x, y) : (s.elementFromPoint ? [s.elementFromPoint(x, y)] : [])); " +
        "if (!inner || inner === t) { break; } t = inner; } return t; } " +
        "function blocks(el, t) { if (!t || t === el || ccontains(el, t)) { return false; } " +
        "var lab = t.closest ? t.closest('label') : null; return !(lab && ccontains(lab, el)); } " +
        "function roleOf(el, tag, type) { var explicit = norm(el.getAttribute('role')).split(' ')[0]; " +
        "var ce = el.isContentEditable === true && tag !== 'input' && tag !== 'textarea'; " +
        "return explicit || (ce ? 'textbox' : implicitRole(tag, type)) || tag; } " +
        "function typeOf(el, tag) { return (tag === 'input' || tag === 'button') ? (el.getAttribute('type') || (tag === 'button' ? 'submit' : 'text')).toLowerCase() : null; } " +
        "function labelNow(el) { var tag = el.tagName.toLowerCase(), type = typeOf(el, tag); " +
        "return (labelOf(el, tag, roleOf(el, tag, type), type) || '').slice(0, 80); } " +
        // Inside an open shadow root: the host's path, '/#shadow', then the path from the root.
        "function xpathOf(el) { var parts = [], n = el; for (; n && n.nodeType === 1; n = n.parentNode) { var i = 1; " +
        "for (var s = n.previousSibling; s; s = s.previousSibling) { if (s.nodeType === 1 && s.nodeName === n.nodeName) { i++; } } " +
        "parts.unshift(xpathStep(n.namespaceURI, n.localName, n.nodeName, i)); } var p = '/' + parts.join('/'); " +
        "return (n && n.nodeType === 11 && n.host) ? xpathOf(n.host) + '/#shadow' + p : p; } " +
        "function byXp(xp) { var segs = String(xp).split('/#shadow'), n = document.evaluate(segs[0], document, null, 9, null).singleNodeValue; " +
        "for (var g = 1; n && g < segs.length; g++) { if (!n.shadowRoot) { return null; } n = n.shadowRoot; var st = segs[g].split('/'); " +
        "for (var j = 1; n && j < st.length; j++) { var mm = /^([a-z0-9-]+)\\[(\\d+)\\]$/.exec(st[j]); if (!mm) { return null; } " +
        "var c = n.children || [], k = 0, hit = null; for (var q = 0; q < c.length; q++) { if (c[q].localName === mm[1] && ++k === +mm[2]) { hit = c[q]; break; } } n = hit; } } return n; } "

/**
 * The in-viewport interactive elements, modal ones first, at most [max], as a JSON array string (null on failure).
 * Disabled and covered ones are kept and flagged; this plugin's overlay nodes are skipped. Registers each element
 * in window.__rpaMarks under its id (a Map where the page has one) so click_at can find it again.
 */
internal fun lookElementsScript(max: Int = LOOK_MAX_ELEMENTS): String =
    "(function () { /*rpa-look-elements*/ try { " + OBSERVE_HELPERS_JS + OBSERVE_DOM_JS + MODAL_JS + MARK_COMMON_JS +
        "var MAX = ${max.coerceIn(1, LOOK_MAX_ELEMENTS)}, SEL = ${INTERACTIVE_SELECTOR.asJsString()}, NOTEXT = $NO_TEXT_TYPES; " +
        "var modal = findModal(); " +
        "function ctxOf(el, own) { var body = document.body; " +
        "for (var n = el.parentElement, hops = 0; n && n !== body && n !== document.documentElement && hops < 6; n = n.parentElement, hops++) { " +
        "if (n.querySelectorAll(SEL).length > 8) { break; } " +
        "var t = textOf(n); if (own) { var at = t.indexOf(own); if (at >= 0) { t = t.slice(0, at) + ' ' + t.slice(at + own.length); } } " +
        "t = norm(t); if (t.length >= 4) { return t.slice(0, $LABEL_MAX); } } " +
        "for (var a = el, h2 = 0; a && a !== body && h2 < 6; a = a.parentElement, h2++) { " +
        "for (var s = a.previousElementSibling; s; s = s.previousElementSibling) { " +
        "if (/^H[1-6]$/.test(s.tagName) || s.getAttribute('role') === 'heading') { var ht = norm(s.innerText || s.textContent); if (ht) { return ht.slice(0, $LABEL_MAX); } } } } " +
        "return ''; } " +
        "var all = deepAll(SEL), mod = [], rest = []; " +
        "for (var i = 0; i < all.length; i++) { var el0 = all[i]; if (mOvl(el0)) { continue; } var r0 = el0.getBoundingClientRect(); " +
        "if (!hasSize(r0) || !visRect(r0)) { continue; } ((modal && ccontains(modal.el, el0)) ? mod : rest).push(el0); } " +
        "var picked = []; function take(list) { for (var j = 0; j < list.length && picked.length < MAX; j++) { if (styledVisible(list[j])) { picked.push(list[j]); } } } " +
        "take(mod); take(rest); " +
        "var M = (typeof Map === 'function') ? new Map() : null, O = {}; var out = []; " +
        "var AE = document.activeElement; while (AE && AE.shadowRoot && AE.shadowRoot.activeElement) { AE = AE.shadowRoot.activeElement; } " +
        "for (var k = 0; k < picked.length; k++) { var el = picked[k], id = 'm' + (k + 1); " +
        "var tag = el.tagName.toLowerCase(), type = typeOf(el, tag), role = roleOf(el, tag, type), label = labelNow(el); " +
        "var ce = el.isContentEditable === true && tag !== 'input' && tag !== 'textarea'; " +
        "var vk = null; " +
        "if (tag === 'input' && (type === 'checkbox' || type === 'radio')) { vk = el.checked ? 'checked' : 'unchecked'; } " +
        "else if ((role === 'checkbox' || role === 'radio' || role === 'switch') && el.getAttribute('aria-checked') !== null) { vk = el.getAttribute('aria-checked') === 'true' ? 'checked' : 'unchecked'; } " +
        "else if (tag === 'select') { var op = el.options && el.options[el.selectedIndex]; vk = op ? 'selected:' + norm(op.label || op.text).slice(0, 60) : 'empty'; } " +
        "else if (tag === 'textarea' || (tag === 'input' && !NOTEXT[type])) { vk = (typeof el.value === 'string' && el.value.length > 0) ? 'text' : 'empty'; } " +
        "else if (ce) { vk = ((el.textContent || '').length > 0) ? 'text' : 'empty'; } " +
        "var editable = tag === 'textarea' || ce || (tag === 'input' && !NOTEXT[type]) || role === 'textbox' || role === 'searchbox'; " +
        "var rr = el.getBoundingClientRect(), vr = visRect(rr); " +
        "var cx = Math.round((vr.l + vr.r) / 2), cy = Math.round((vr.t + vr.b) / 2); " +
        "var host = null; if (tag === 'a') { try { host = new URL(el.href, document.baseURI).host || null; } catch (e2) { host = null; } } " +
        "if (M) { M.set(id, el); } else { O[id] = el; } " +
        "out.push({ id: id, role: role, tag: tag, label: label, value_kind: vk, x: Math.round(vr.l), y: Math.round(vr.t), " +
        "w: Math.round(vr.r - vr.l), h: Math.round(vr.b - vr.t), disabled: disabled(el), covered: blocks(el, topAt(cx, cy)), " +
        "context: ctxOf(el, label), editable: editable, href_host: host, xp: xpathOf(el), mod: !!(modal && ccontains(modal.el, el)), focused: !!AE && el === AE }); } " +
        "window.__rpaMarks = M || O; " +
        "return JSON.stringify(out); } catch (e) { return null; } })()"

/**
 * The whole look read in one call: JSON {meta, vp, scroll, hash, located:{phrase:json}, els, text, u, f}; each part is
 * the existing script's own answer, u is location.href and f the document's iframes ([framesListScript]). In a child
 * frame ([child]) meta and scroll are skipped.
 */
internal fun lookScript(opts: LookOptions, child: Boolean = false): String {
    val located = opts.locate.joinToString("") { "out.located[${it.asJsString()}] = ${locateTextScript(it)}; " }
    return "(function(){/*rpa-look*/try{var out={located:{}}; " +
        (if (child) "" else "out.meta = ${PageMeta.SCRIPT}; out.scroll = ${ScrollInfo.SCRIPT}; ") +
        "out.vp = ${Viewport.SCRIPT}; out.hash = ${contentHashScript()}; out.u = String(location.href); out.f = ${framesListScript()}; " +
        located +
        "out.els = ${if (opts.elements) lookElementsScript() else "null"}; " +
        "out.text = ${if (opts.pageText) pageTextScript(PAGE_TEXT_DEFAULT) else "null"}; " +
        "return JSON.stringify(out);}catch(e){return null;}})()"
}

/**
 * The document's iframe/frame elements (at most [FRAME_LIST_MAX]) as a JSON array of [IframeInfo]: content-box
 * offset and size in this viewport, on-screen part, a 4x4 sample of where something else covers it, its
 * window.frames index, name, src (about:srcdoc for srcdoc frames) and title/aria-label. Never reads into the frame.
 */
internal fun framesListScript(): String =
    "(function () { /*rpa-frames*/ try { " +
        OBSERVE_HELPERS_JS + OBSERVE_DOM_JS + MODAL_JS + MARK_COMMON_JS +
        "var fl = deepAll('iframe,frame'); if (!fl.length) { return '[]'; } " +
        "var modal = findModal(), out = [], grids = 0; " +
        "for (var i = 0; i < fl.length && i < $FRAME_LIST_MAX; i++) { var el = fl[i], r = el.getBoundingClientRect(), cs = window.getComputedStyle(el); " +
        "var pl = parseFloat(cs.paddingLeft) || 0, pt = parseFloat(cs.paddingTop) || 0, pr = parseFloat(cs.paddingRight) || 0, pb = parseFloat(cs.paddingBottom) || 0; " +
        // A transformed iframe (a modal's zoom animation) is drawn scaled: rect / layout size is the scale.
        "var sx = (el.offsetWidth > 0 && r.width > 0) ? r.width / el.offsetWidth : 1, sy = (el.offsetHeight > 0 && r.height > 0) ? r.height / el.offsetHeight : 1; " +
        "var x = r.left + ((el.clientLeft || 0) + pl) * sx, y = r.top + ((el.clientTop || 0) + pt) * sy; " +
        "var lw = Math.max(0, (el.clientWidth || 0) - pl - pr), lh = Math.max(0, (el.clientHeight || 0) - pt - pb), w = lw * sx, h = lh * sy; " +
        "var why = cs.display === 'none' ? 'display:none' : (cs.visibility === 'hidden' || cs.visibility === 'collapse') ? 'visibility:' + cs.visibility : " +
        "(el.closest && el.closest('[hidden]')) ? 'hidden attribute' : !(w > 1 && h > 1) ? 'zero size' : null; " +
        "var shown = why === null; " +
        "var vr = shown ? visRect({ left: x, top: y, right: x + w, bottom: y + h }) : null, cov = null; " +
        "if (vr && grids++ < 12) { cov = ''; for (var gy = 0; gy < 4; gy++) { for (var gx = 0; gx < 4; gx++) { " +
        "var t = topAt(vr.l + (gx + 0.5) * (vr.r - vr.l) / 4, vr.t + (gy + 0.5) * (vr.b - vr.t) / 4); cov += (t && t !== el && !ccontains(el, t)) ? '1' : '0'; } } } " +
        "var fi = -1; try { for (var k = 0; k < window.frames.length; k++) { if (window.frames[k] === el.contentWindow) { fi = k; break; } } } catch (e1) {} " +
        "var src = el.hasAttribute('srcdoc') ? 'about:srcdoc' : String(el.src || 'about:blank'); " +
        "out.push({ i: i, fi: fi, name: el.getAttribute('name') || '', src: src, x: x, y: y, w: w, h: h, shown: shown, " +
        "vis: vr ? [vr.l, vr.t, vr.r, vr.b] : null, cov: cov, mod: !!(modal && ccontains(modal.el, el)), " +
        "lab: norm(el.getAttribute('title') || el.getAttribute('aria-label') || el.getAttribute('name') || '').slice(0, 80), " +
        "lw: lw, lh: lh, sx: sx, sy: sy, why: why, act: el === document.activeElement }); } " +
        "return JSON.stringify(out); } catch (e) { return null; } })()"

/**
 * Wraps [payload] (an expression) for a frame walk: JSON {u: location.href, f: [framesListScript], p: payload, v: "w,h"}.
 */
internal fun framedScript(payload: String): String =
    "(function(){/*rpa-framed*/try{return JSON.stringify({u:String(location.href),f:${framesListScript()},p:$payload,v:${Viewport.SCRIPT}});}catch(e){return null;}})()"

/**
 * In a parent frame: where the point ([px],[py]) of the child frame shown by its [idx]-th iframe/frame element lies in
 * this frame's viewport, scrolling the iframe into view when the point is off screen, and whether something else sits
 * on top of the iframe there. Answers JSON [MarkAnswer] (ok | covered | gone).
 */
internal fun frameHitScript(idx: Int, px: Int, py: Int): String =
    "(function () { /*rpa-frame-hit*/ try { " + OBSERVE_HELPERS_JS + OBSERVE_DOM_JS + MODAL_JS + MARK_COMMON_JS +
        "var el = deepAll('iframe,frame')[$idx]; if (!el) { return JSON.stringify({ state: 'gone' }); } " +
        "function at() { var r = el.getBoundingClientRect(), cs = window.getComputedStyle(el); " +
        "var sx = (el.offsetWidth > 0 && r.width > 0) ? r.width / el.offsetWidth : 1, sy = (el.offsetHeight > 0 && r.height > 0) ? r.height / el.offsetHeight : 1; " +
        "return [r.left + ((el.clientLeft || 0) + (parseFloat(cs.paddingLeft) || 0) + $px) * sx, r.top + ((el.clientTop || 0) + (parseFloat(cs.paddingTop) || 0) + $py) * sy]; } " +
        "function inside(p) { return p[0] >= 0 && p[0] < VW && p[1] >= 0 && p[1] < VH; } " +
        "var p = at(); " +
        "if (!inside(p)) { try { el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' }); } catch (e1) {} p = at(); } " +
        "if (!inside(p)) { try { window.scrollBy((p[0] < 0 || p[0] >= VW) ? p[0] - VW / 2 : 0, (p[1] < 0 || p[1] >= VH) ? p[1] - VH / 2 : 0); } catch (e2) {} p = at(); } " +
        "if (!inside(p)) { return JSON.stringify({ state: 'gone' }); } " +
        "var x = Math.round(p[0]), y = Math.round(p[1]), t = topAt(x, y); " +
        "if (t && t !== el && !ccontains(el, t)) { var modal = findModal(), cover; " +
        "if (modal && ccontains(modal.el, t) && !ccontains(modal.el, el)) { cover = modal.label || modal.role || 'dialog'; } " +
        "else { cover = labelNow(t) || norm(t.getAttribute('aria-label')) || t.tagName.toLowerCase(); } " +
        "return JSON.stringify({ state: 'covered', cover: cover }); } " +
        "return JSON.stringify({ state: 'ok', x: x, y: y }); } catch (e) { return null; } })()"

/** The page's answer to [markScript]. */
@Serializable
internal data class MarkAnswer(
    /** ok | gone | disabled | covered */
    val state: String,
    val x: Int = 0,
    val y: Int = 0,
    val label: String? = null,
    val tag: String? = null,
    val cover: String? = null,
)

/**
 * Finds mark [id] again and hit-tests it: the node kept in window.__rpaMarks when still attached, else the xpath
 * stored at look time (accepted only when tag and label still match). Scrolls it into view when it left the viewport.
 * Answers JSON [MarkAnswer]: the visible centre (or another inner point that is not covered) when clickable.
 */
/** Finds mark [id] into `el` (null when gone): the node kept in window.__rpaMarks, else the xpath when tag and label still match. */
private fun markFindJs(id: String, mark: MarkRef): String =
    "var ID = ${id.asJsString()}, XP = ${(mark.xpath ?: "").asJsString()}, LAB = ${mark.label.asJsString()}, TAG = ${mark.tag.asJsString()}; " +
        "var m = window.__rpaMarks, el = m ? (m.get ? m.get(ID) : m[ID]) : null; " +
        "if (el && !(el.tagName && el.tagName.toLowerCase() === TAG && (el.isConnected === undefined ? document.documentElement.contains(el) : el.isConnected))) { el = null; } " +
        "if (!el && XP) { try { var n = byXp(XP); " +
        "if (n && n.tagName.toLowerCase() === TAG && labelNow(n) === LAB) { el = n; } } catch (e0) {} } "

internal fun markScript(id: String, mark: MarkRef): String =
    "(function () { /*rpa-mark*/ try { " + OBSERVE_HELPERS_JS + OBSERVE_DOM_JS + MODAL_JS + MARK_COMMON_JS + markFindJs(id, mark) +
        "if (!el) { return JSON.stringify({ state: 'gone' }); } " +
        "var label = labelNow(el) || LAB || TAG; " +
        "if (disabled(el)) { return JSON.stringify({ state: 'disabled', label: label, tag: TAG }); } " +
        "var r = el.getBoundingClientRect(), vr = hasSize(r) ? visRect(r) : null; " +
        "if (hasSize(r) && !vr) { try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e1) {} r = el.getBoundingClientRect(); vr = hasSize(r) ? visRect(r) : null; } " +
        "if (!vr) { return JSON.stringify({ state: 'gone' }); } " +
        "var fr = [[0.5, 0.5], [0.25, 0.5], [0.75, 0.5], [0.5, 0.25], [0.5, 0.75]], first = null; " +
        "for (var i = 0; i < fr.length; i++) { " +
        "var px = Math.min(Math.max(Math.round(vr.l + (vr.r - vr.l) * fr[i][0]), 0), VW - 1), py = Math.min(Math.max(Math.round(vr.t + (vr.b - vr.t) * fr[i][1]), 0), VH - 1); " +
        "var t = topAt(px, py); if (i === 0) { first = t; } " +
        "if (!blocks(el, t)) { return JSON.stringify({ state: 'ok', x: px, y: py, label: label, tag: TAG }); } } " +
        "var modal = findModal(), cover; " +
        "if (modal && first && ccontains(modal.el, first) && !ccontains(modal.el, el)) { cover = modal.label || modal.role || 'dialog'; } " +
        "else { cover = first ? (labelNow(first) || norm(first.getAttribute('aria-label')) || first.tagName.toLowerCase()) : 'another element'; } " +
        "return JSON.stringify({ state: 'covered', label: label, tag: TAG, cover: cover }); " +
        "} catch (e) { return null; } })()"

/** The page's answer to [markFocusScript]. */
@Serializable
internal data class MarkFocus(
    /** The element takes text focus (field, select, contenteditable, textbox role); others are not waited for. */
    val can: Boolean = false,
    /** It (or something inside it, or the control it labels) is the document's active element. */
    val f: Boolean = false,
)

/** Whether mark [id] holds focus in its document: JSON [MarkFocus]. Run in the mark's own frame. */
internal fun markFocusScript(id: String, mark: MarkRef): String =
    "(function () { /*rpa-mark-focus*/ try { " + OBSERVE_HELPERS_JS + OBSERVE_DOM_JS + MODAL_JS + MARK_COMMON_JS + markFindJs(id, mark) +
        "if (!el) { return JSON.stringify({ can: false, f: false }); } " +
        "var a = document.activeElement; while (a && a.shadowRoot && a.shadowRoot.activeElement) { a = a.shadowRoot.activeElement; } " +
        "var tag = el.tagName.toLowerCase(), role = el.getAttribute('role'); " +
        "var can = tag === 'textarea' || tag === 'select' || (tag === 'input' && ['button','submit','reset','image','checkbox','radio','file','range','color','hidden'].indexOf((el.getAttribute('type') || 'text').toLowerCase()) === -1) || " +
        "el.isContentEditable === true || role === 'textbox' || role === 'searchbox' || role === 'combobox' || (tag === 'label' && !!el.control); " +
        "var f = !!a && (a === el || ccontains(el, a) || (tag === 'label' && el.control === a)); " +
        "return JSON.stringify({ can: can, f: f }); } catch (e) { return null; } })()"

/** Drops null members (recursively) so optional fields vanish from the result, keeping empty lists and objects. */
internal fun pruneNulls(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.filterNot { it.value is JsonNull }.mapValues { pruneNulls(it.value) })
    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { pruneNulls(it) })
    else -> e
}
