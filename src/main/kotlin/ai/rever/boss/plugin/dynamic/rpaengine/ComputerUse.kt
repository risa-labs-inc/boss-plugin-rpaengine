package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.BrowserIntegration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * Computer-use verbs of rpa_step: screenshot, click_at, type_text, key, scroll_by, focused, pointer_hide,
 * and the optional `pointer` overlay. Real input and the bitmap come from the host's JxBrowser Browser,
 * found by reflection behind the tab's BrowserIntegration (the plugin API offers only executeJavaScript).
 * The technique (reflection, event building, EDT dispatch, pointer overlay) follows boss-plugin-max's
 * harness/space/{JxBrowserTab,TabInput,AgentCursor}.kt.
 */

internal object CuTypes {
    const val SCREENSHOT = "screenshot"
    const val CLICK_AT = "click_at"
    const val TYPE_TEXT = "type_text"
    const val KEY = "key"
    const val SCROLL_BY = "scroll_by"
    const val FOCUSED = "focused"
    const val POINTER_HIDE = "pointer_hide"
    const val PAGE_TEXT = "page_text"
    const val LOCATE_TEXT = "locate_text"
    const val CU_END = "cu_end"
    const val DIALOG_ANSWER = "dialog_answer"
    const val LOOK = "look"

    val ALL = setOf(SCREENSHOT, CLICK_AT, TYPE_TEXT, KEY, SCROLL_BY, FOCUSED, POINTER_HIDE, PAGE_TEXT, LOCATE_TEXT, LOOK, CU_END, DIALOG_ANSWER)
}

// ---------------------------------------------------------------------------------------------
// Values: parsing and geometry (pure)
// ---------------------------------------------------------------------------------------------

internal const val SHOT_DEFAULT_MAX_SIDE = 1100
internal const val SHOT_MIN_SIDE = 280
internal const val SHOT_MAX_SIDE_LIMIT = 4096
internal const val SHOT_JPEG_QUALITY = 0.7
internal const val SHOT_KEEP = 200
internal const val SCROLL_MAX_PX = 6000
internal const val PAGE_TEXT_DEFAULT = 20_000
internal const val PAGE_TEXT_LIMIT = 100_000

/** The page's visible text (innerText: rendered text only, never form field values), cut to [max] chars. */
internal fun pageTextScript(max: Int): String =
    "(function(){var hid=[];try{" +
        "var n=document.querySelectorAll('[data-rpa-pointer],[data-rpa-highlight]');" +
        "for(var i=0;i<n.length;i++){hid.push([n[i],n[i].style.getPropertyValue('display'),n[i].style.getPropertyPriority('display')]);n[i].style.setProperty('display','none','important');}" +
        "var t=(document.body||document.documentElement).innerText||'';return t.length>$max?t.slice(0,$max):t;}catch(e){return null;}" +
        "finally{for(var j=0;j<hid.length;j++){try{hid[j][0].style.setProperty('display',hid[j][1],hid[j][2]);}catch(e){}}}})()"

/**
 * A short stable hash (djb2, hex) of what a person would call the page's content: rendered text without this
 * plugin's overlay nodes, location.hash, and each form control's type, checked, selectedIndex and value LENGTH
 * (never a value). Video frames and animations do not move it; typing and clicks that change content do.
 */
internal fun contentHashScript(): String =
    "(function(){var hid=[];try{" +
        "var n=document.querySelectorAll('[data-rpa-pointer],[data-rpa-highlight]');" +
        "for(var i=0;i<n.length;i++){hid.push([n[i],n[i].style.getPropertyValue('display'),n[i].style.getPropertyPriority('display')]);n[i].style.setProperty('display','none','important');}" +
        "var parts=[(document.body||document.documentElement).innerText||'',location.hash||''];" +
        "var c=document.querySelectorAll('input,select,textarea');" +
        "for(var k=0;k<c.length;k++){var el=c[k];" +
        "parts.push((el.type||el.tagName)+'|'+(el.checked?1:0)+'|'+(el.selectedIndex===undefined?'':el.selectedIndex)+'|'+(typeof el.value==='string'?el.value.length:0));}" +
        "var s=parts.join('\\u0001'),h=5381;" +
        "for(var j=0;j<s.length;j++){h=((h<<5)+h+s.charCodeAt(j))|0;}" +
        "return (h>>>0).toString(16);}catch(e){return null;}" +
        "finally{for(var m=0;m<hid.length;m++){try{hid[m][0].style.setProperty('display',hid[m][1],hid[m][2]);}catch(e){}}}})()"

internal val CONTENT_HASH_RE = Regex("[0-9a-f]{1,8}")

internal const val LOCATE_TEXT_MAX = 100

/** Where a phrase sits relative to the viewport; see [locateTextScript]. */
@Serializable
internal data class LocatedInfo(
    val found: Boolean,
    val count: Int,
    @SerialName("in_view") val inView: Boolean,
    /** above | below | in_view; null when not found. */
    val direction: String? = null,
    /** Distance of the nearest match from the viewport in viewport heights (0.0 when in view). */
    val screens: Double? = null,
    val rect: LocatedRect? = null,
)

@Serializable
internal data class LocatedRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Finds visible text nodes containing [phrase] (case-insensitive, whitespace-collapsed; overlay, script, style and
 * form-control text excluded, so never a field value). A whole-word match (no letter or digit touching either end,
 * so "item 87" misses "Item 870") is preferred: substring matches count only when no whole-word match exists, and never when they cut through a number.
 * Answers JSON [LocatedInfo] about the match nearest the viewport.
 */
internal fun locateTextScript(phrase: String): String =
    """(function(){try{
var P=${phrase.asJsString()}.replace(/\s+/g,' ').trim().toLowerCase();
if(!P)return null;
var W=window.innerWidth||1,H=window.innerHeight||1,SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,SELECT:1,OPTION:1,TITLE:1};
function word(c){return !!c&&/[a-z0-9_]/i.test(c);}
function dig(c){return !!c&&/[0-9]/.test(c);}
var whole=[],part=[];
var w=document.createTreeWalker(document.body||document.documentElement,4,null),n;
while((n=w.nextNode())){
var el=n.parentElement;if(!el||SKIP[el.tagName])continue;
if(el.closest&&el.closest('[data-rpa-pointer],[data-rpa-highlight]'))continue;
var t=(n.nodeValue||'').replace(/\s+/g,' ').toLowerCase();
var at=t.indexOf(P);if(at<0)continue;
var cs=window.getComputedStyle(el);if(cs.display==='none'||cs.visibility==='hidden')continue;
var r=el.getBoundingClientRect();if(!(r.width>0&&r.height>0))continue;
var isWhole=false,isPart=false;
for(var i=at;i>=0;i=t.indexOf(P,i+1)){
var b=i>0?t.charAt(i-1):'',a=t.charAt(i+P.length);
if(!(word(b)&&word(P.charAt(0)))&&!(word(a)&&word(P.charAt(P.length-1)))){isWhole=true;break;}
if(!(dig(b)&&dig(P.charAt(0)))&&!(dig(a)&&dig(P.charAt(P.length-1))))isPart=true;}
if(isWhole)whole.push(r);else if(isPart)part.push(r);}
var hits=whole.length?whole:part;
if(!hits.length)return JSON.stringify({found:false,count:0,in_view:false});
var best=null,bd=0;
for(var k=0;k<hits.length;k++){var q=hits[k];
var dy=q.bottom<=0?-q.bottom:(q.top>=H?q.top-H:0),dx=q.right<=0||q.left>=W?1:0;
var d=dy+(dx?H*10:0);
if(best===null||d<bd){best=q;bd=d;}}
var inv=bd===0,dir=inv?'in_view':(best.bottom<=0?'above':'below');
var o={found:true,count:hits.length,in_view:inv,direction:dir,screens:Math.round(bd/H*10)/10};
if(inv)o.rect={x:Math.round(best.left),y:Math.round(best.top),w:Math.round(best.width),h:Math.round(best.height)};
return JSON.stringify(o);
}catch(e){return null;}})()"""

/** Jev's box for the target, in viewport CSS px. */
internal data class SnapBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    companion object {
        /** "left,top,right,bottom" with right > left and bottom > top, or null. */
        fun parse(value: String?): SnapBox? {
            val p = value?.split(',')?.map { it.trim().toDoubleOrNull()?.takeIf { d -> d.isFinite() } ?: return null } ?: return null
            if (p.size != 4 || p[2] <= p[0] || p[3] <= p[1]) return null
            return SnapBox(p[0], p[1], p[2], p[3])
        }
    }
}

/** Where a click_at really landed after snapping; present only when the point moved. */
@Serializable
internal data class SnapInfo(val from: String, val to: String, val tag: String? = null, val label: String? = null)

/** The page's answer to [snapScript]. */
@Serializable
internal data class SnapAnswer(val x: Int, val y: Int, val moved: Boolean = false, val tag: String? = null, val label: String? = null)

private const val SNAP_INTERACTIVE_SEL =
    "a[href],button,input,select,textarea,label,summary,[contenteditable],[onclick]," +
        "[role=button],[role=link],[role=checkbox],[role=switch],[role=tab],[role=menuitem],[role=option],[role=radio],[role=combobox],[role=textbox]"

/**
 * One page script that decides where a click at ([x],[y]) should land given [box]: unchanged when an interactive
 * element is under the point (or the point is on a canvas); otherwise the centre of the visible interactive element
 * overlapping the box most (box grown by 25%, min 12 px, to find candidates; ties go to the closest centre).
 * Answers JSON [SnapAnswer]; never reads a field value.
 */
internal fun snapScript(x: Int, y: Int, box: SnapBox): String =
    """(function(){try{
var x=$x,y=$y,B=[${box.left},${box.top},${box.right},${box.bottom}],SEL=${SNAP_INTERACTIVE_SEL.asJsString()};
var W=window.innerWidth||1,H=window.innerHeight||1;
function inOv(e){return !!(e.closest&&e.closest('[data-rpa-pointer],[data-rpa-highlight]'));}
function inter(e){
if(!e||e.nodeType!==1||inOv(e))return false;
if(e.disabled||e.getAttribute('aria-disabled')==='true'||e.hidden)return false;
if(e.tagName==='INPUT'&&(e.getAttribute('type')||'').toLowerCase()==='hidden')return false;
return e.matches(SEL)||(e.hasAttribute('tabindex')&&e.tabIndex>=0);}
function vis(e){
var r=e.getBoundingClientRect(),cs=window.getComputedStyle(e);
if(cs.display==='none'||cs.visibility==='hidden')return null;
var l=Math.max(r.left,0),t=Math.max(r.top,0),rr=Math.min(r.right,W),bb=Math.min(r.bottom,H);
return(rr>l&&bb>t)?[l,t,rr,bb]:null;}
function ov(a,b){var w=Math.min(a[2],b[2])-Math.max(a[0],b[0]),h=Math.min(a[3],b[3])-Math.max(a[1],b[1]);return(w>0&&h>0)?w*h:0;}
function hit(a,b){return Math.min(a[2],b[2])>=Math.max(a[0],b[0])&&Math.min(a[3],b[3])>=Math.max(a[1],b[1]);}
function label(e){
var tag=e.tagName.toLowerCase(),s=norm(e.getAttribute('aria-label'));
if(!s&&e.labels&&e.labels.length)s=norm(e.labels[0].textContent);
if(!s&&tag!=='input'&&tag!=='textarea'&&tag!=='select')s=norm(e.textContent);
if(!s)s=norm(e.getAttribute('title'))||norm(e.getAttribute('placeholder'))||norm(e.getAttribute('name'));
return s.slice(0,80);}
function norm(v){return(v||'').replace(/\s+/g,' ').trim();}
function out(nx,ny,el){var m=nx!==x||ny!==y;return JSON.stringify(m?{x:nx,y:ny,moved:true,tag:el.tagName.toLowerCase(),label:label(el)||null}:{x:x,y:y,moved:false});}
var under=document.elementFromPoint(x,y);
if(under&&under.tagName==='CANVAS')return out(x,y,null);
for(var n=under;n&&n.nodeType===1;n=n.parentElement){if(inter(n))return out(x,y,n);}
var bw=B[2]-B[0],bh=B[3]-B[1],px=Math.max(12,bw*0.25),py=Math.max(12,bh*0.25);
var wide=[B[0]-px,B[1]-py,B[2]+px,B[3]+py],best=null,bestA=-1,bestD=0;
var all=document.querySelectorAll(SEL+',[tabindex]');
for(var i=0;i<all.length;i++){var e=all[i];if(!inter(e))continue;var r=vis(e);if(!r||!hit(r,wide))continue;
var a=ov(r,B),cx=(r[0]+r[2])/2,cy=(r[1]+r[3])/2,d=Math.sqrt((cx-x)*(cx-x)+(cy-y)*(cy-y));
if(a>bestA||(a===bestA&&d<bestD)){best=e;bestA=a;bestD=d;best.__r=r;}}
if(!best)return out(x,y,null);
var rr=best.__r;delete best.__r;
var tx=Math.min(Math.max(Math.round((rr[0]+rr[2])/2),0),W-1),ty=Math.min(Math.max(Math.round((rr[1]+rr[3])/2),0),H-1);
return out(tx,ty,best);
}catch(e){return null;}})()"""

/** "x,y" (CSS px, decimals rounded) or null. */
internal fun parsePoint(value: String?): Pair<Int, Int>? {
    val parts = value?.split(',')?.map { it.trim() } ?: return null
    if (parts.size != 2) return null
    val x = parts[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val y = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return x.roundToInt() to y.roundToInt()
}

/** "dy" or "dx,dy" in px, positive = down/right. Null when malformed. */
internal fun parseScrollDelta(value: String?): Pair<Int, Int>? {
    val parts = value?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
    return when (parts.size) {
        1 -> 0 to parts[0]
        2 -> parts[0] to parts[1]
        else -> null
    }
}

/** The point when it lies inside a [width] x [height] CSS viewport, else null. */
internal fun pointInViewport(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int>? =
    if (x in 0 until width && y in 0 until height) x to y else null

/**
 * Saved-image size for a [width] x [height] bitmap: longest side at most [maxSide], never enlarged
 * past what the cap allows, and both sides at least [SHOT_MIN_SIDE] when the cap permits.
 */
internal fun fitShotSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height
    val longest = maxOf(width, height)
    val shortest = minOf(width, height)
    var scale = min(1.0, maxSide.toDouble() / longest)
    if (shortest * scale < SHOT_MIN_SIDE) scale = SHOT_MIN_SIDE.toDouble() / shortest
    if (longest * scale > maxSide) scale = maxSide.toDouble() / longest
    if (scale == 1.0) return width to height
    return ((width * scale).roundToInt().coerceIn(1, maxSide)) to ((height * scale).roundToInt().coerceIn(1, maxSide))
}

/** The page's layout viewport, read from the page itself. */
internal data class Viewport(val width: Int, val height: Int, val dpr: Double) {
    companion object {
        /** Synchronous on purpose: a promise comes back unawaited from executeJavaScript. */
        const val SCRIPT = "[window.innerWidth, window.innerHeight, window.devicePixelRatio].join(',')"

        fun parse(answer: Any?): Viewport? {
            val parts = answer?.toString()?.trim()?.trim('"')?.split(',')?.map { it.trim() } ?: return null
            if (parts.size < 2) return null
            val w = parts[0].toDoubleOrNull()?.roundToInt() ?: return null
            val h = parts[1].toDoubleOrNull()?.roundToInt() ?: return null
            if (w <= 0 || h <= 0) return null
            return Viewport(w, h, parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0)
        }
    }
}

/** An open overlay dialog on the page. */
@Serializable
internal data class ModalInfo(val label: String? = null, val role: String? = null)

/**
 * Defines `findModal()` -> {el, label, role} | null, self-contained. A visible [role=dialog|alertdialog], [aria-modal=true]
 * or dialog[open] counts; failing that, the element at the viewport centre resolving to a fixed/absolute ancestor of
 * at least 30% of the viewport with z-index >= 10 that is not the page's main content. This plugin's overlay nodes
 * are skipped. Label: aria-label, aria-labelledby text, then the first heading inside (80 chars at most).
 */
internal const val MODAL_JS = """
function mOvl(e){return !!(e&&e.closest&&e.closest('[data-rpa-pointer],[data-rpa-highlight]'));}
function mNorm(s){return s==null?'':String(s).replace(/\s+/g,' ').trim().slice(0,80);}
function mShown(e){try{var r=e.getBoundingClientRect();if(!(r.width>0&&r.height>0))return false;
var W=window.innerWidth||1,H=window.innerHeight||1;if(r.bottom<=0||r.right<=0||r.top>=H||r.left>=W)return false;
var cs=window.getComputedStyle(e);return cs.display!=='none'&&cs.visibility!=='hidden';}catch(x){return false;}}
function mLabel(e){var s=mNorm(e.getAttribute('aria-label'));if(s)return s;
var lb=e.getAttribute('aria-labelledby');
if(lb){s=mNorm(lb.split(/\s+/).map(function(id){var n=document.getElementById(id);return n?(n.innerText||''):'';}).join(' '));if(s)return s;}
var h=e.querySelector('h1,h2,h3,h4,h5,h6,[role=heading]');if(h){s=mNorm(h.innerText);if(s)return s;}return null;}
function findModal(){try{
var W=window.innerWidth||1,H=window.innerHeight||1,c=document.querySelectorAll('[role=dialog],[role=alertdialog],[aria-modal="true"],dialog[open]'),best=null;
for(var i=0;i<c.length;i++){var e=c[i];if(mOvl(e)||!mShown(e))continue;if(e.closest&&e.closest('[aria-hidden="true"]'))continue;best=e;}
if(best){var rl=mNorm(best.getAttribute('role'));return {el:best,label:mLabel(best),role:rl||'dialog'};}
var t=document.elementFromPoint(W/2,H/2);
for(var n=t;n&&n.nodeType===1&&n!==document.body&&n!==document.documentElement;n=n.parentElement){
if(mOvl(n))continue;var cs=window.getComputedStyle(n),pos=cs.position;
if(pos!=='fixed'&&pos!=='absolute')continue;
var z=parseInt(cs.zIndex,10);if(!(z>=10))continue;
var r=n.getBoundingClientRect();if(!(r.width*r.height>=0.3*W*H))continue;
if(n.matches&&n.matches('main,[role=main]'))continue;
return {el:n,label:mLabel(n),role:'dialog'};}
return null;}catch(x){return null;}}
"""

/** The page's address, title and visibility, so a screenshot carries them without a separate observe. */
@Serializable
internal data class PageMeta(val url: String? = null, val title: String? = null, val vis: String? = null, val modal: ModalInfo? = null) {
    /** document.visibilityState == 'visible'; null when the page did not say. */
    val visible: Boolean? get() = vis?.let { it == "visible" }

    companion object {
        const val SCRIPT = "(function(){try{" + MODAL_JS +
            "var m=findModal();return JSON.stringify({url:String(location.href||''),title:String(document.title||''),vis:String(document.visibilityState||'')," +
            "modal:m?{label:m.label,role:m.role}:null});}catch(e){return null;}})()"

        fun parse(answer: Any?): PageMeta? =
            (answer as? String)?.let { runCatching { TabJson.decodeFromString(serializer(), it) }.getOrNull() }
                ?.let { PageMeta(it.url?.takeIf(String::isNotEmpty)?.take(2000), it.title?.takeIf(String::isNotEmpty)?.take(300), it.vis?.takeIf(String::isNotEmpty), it.modal) }
    }
}

internal const val REVEAL_POLL_MS = 50L
internal const val REVEAL_TIMEOUT_MS = 1_500L
internal const val REVEAL_FRAME_MS = 120L

/** The page scroll position, so a caller can see progress without pixels. */
@Serializable
internal data class ScrollInfo(val x: Int, val y: Int, @SerialName("max_y") val maxY: Int) {
    companion object {
        const val SCRIPT = "[window.scrollX, window.scrollY, Math.max(0, document.documentElement.scrollHeight - window.innerHeight)].join(',')"

        fun parse(answer: Any?): ScrollInfo? {
            val p = answer?.toString()?.trim()?.trim('"')?.split(',')?.map { it.trim().toDoubleOrNull()?.roundToInt() ?: return null } ?: return null
            if (p.size < 3) return null
            return ScrollInfo(p[0].coerceAtLeast(0), p[1].coerceAtLeast(0), p[2].coerceAtLeast(0))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Result shapes
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class ScreenshotInfo(
    val path: String,
    val width: Int,
    val height: Int,
    @SerialName("css_width") val cssWidth: Int,
    @SerialName("css_height") val cssHeight: Int,
    val dpr: Double,
    /** Absent when the page would not answer the scroll read. */
    val scroll: ScrollInfo? = null,
    /** Hash of the page's content and form state (see [contentHashScript]); absent when the read failed. */
    @SerialName("content_hash") val contentHash: String? = null,
    /** location.href and document.title; each omitted when the page would not say. */
    val url: String? = null,
    val title: String? = null,
    /** document.visibilityState == 'visible' when captured; a hidden tab's bitmap can be stale. */
    val visible: Boolean? = null,
    /** True when ensure_visible had to select the tab first. */
    val revealed: Boolean? = null,
    /** An open overlay dialog (see [MODAL_JS]); omitted when none. */
    val modal: ModalInfo? = null,
)

/** What holds keyboard focus. Never the field's value. */
@Serializable
internal data class FocusedInfo(
    val tag: String? = null,
    val editable: Boolean = false,
    val label: String? = null,
    val type: String? = null,
    val options: List<String>? = null,
    val sensitive: Boolean = false,
)

internal class CuOutcome(
    val ok: Boolean,
    val error: String? = null,
    val screenshot: ScreenshotInfo? = null,
    val focused: FocusedInfo? = null,
    val text: String? = null,
    val snapped: SnapInfo? = null,
    val located: LocatedInfo? = null,
    val clicked: ClickedInfo? = null,
    val look: LookResult? = null,
)

/** The element a click_at / type_text `element` resolved to. */
@Serializable
internal data class ClickedInfo(val id: String, val label: String? = null, val tag: String? = null)

// ---------------------------------------------------------------------------------------------
// Input events (plain data) and key parsing
// ---------------------------------------------------------------------------------------------

internal sealed interface TabEvent {
    data class Move(val x: Int, val y: Int) : TabEvent
    data class Press(val x: Int, val y: Int, val clicks: Int = 1) : TabEvent
    data class Release(val x: Int, val y: Int, val clicks: Int = 1) : TabEvent
    data class Wheel(val x: Int, val y: Int, val dx: Float, val dy: Float) : TabEvent
    data class KeyDown(val code: String, val char: Char, val mods: Mods = Mods.NONE) : TabEvent
    data class KeyChar(val code: String, val char: Char, val mods: Mods = Mods.NONE) : TabEvent
    data class KeyUp(val code: String, val mods: Mods = Mods.NONE) : TabEvent
    data class Pause(val ms: Long) : TabEvent

    data class Mods(val shift: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val meta: Boolean = false) {
        companion object { val NONE = Mods() }
    }
}

/** Key names and characters to JxBrowser `KeyCode` constant names. */
internal object TabKeys {
    const val UNKNOWN = "UNKNOWN"
    const val RETURN = "KEY_CODE_RETURN"
    const val ESCAPE = "KEY_CODE_ESCAPE"

    data class Combo(val code: String, val char: Char, val mods: TabEvent.Mods)

    private val NAMED: Map<String, Pair<String, Char>> = mapOf(
        "enter" to (RETURN to '\r'), "return" to (RETURN to '\r'),
        "esc" to (ESCAPE to '\u0000'), "escape" to (ESCAPE to '\u0000'),
        "tab" to ("KEY_CODE_TAB" to '\u0000'), "space" to ("KEY_CODE_SPACE" to ' '),
        "backspace" to ("KEY_CODE_BACK" to '\u0000'), "delete" to ("KEY_CODE_DELETE" to '\u0000'),
        "arrowup" to ("KEY_CODE_UP" to '\u0000'), "up" to ("KEY_CODE_UP" to '\u0000'),
        "arrowdown" to ("KEY_CODE_DOWN" to '\u0000'), "down" to ("KEY_CODE_DOWN" to '\u0000'),
        "arrowleft" to ("KEY_CODE_LEFT" to '\u0000'), "left" to ("KEY_CODE_LEFT" to '\u0000'),
        "arrowright" to ("KEY_CODE_RIGHT" to '\u0000'), "right" to ("KEY_CODE_RIGHT" to '\u0000'),
        "home" to ("KEY_CODE_HOME" to '\u0000'), "end" to ("KEY_CODE_END" to '\u0000'),
        "pageup" to ("KEY_CODE_PRIOR" to '\u0000'), "pagedown" to ("KEY_CODE_NEXT" to '\u0000'),
    )

    fun codeOf(c: Char): String = when {
        c in 'a'..'z' || c in 'A'..'Z' -> "KEY_CODE_${c.uppercaseChar()}"
        c in '0'..'9' -> "KEY_CODE_$c"
        c == ' ' -> "KEY_CODE_SPACE"
        c == ',' -> "KEY_CODE_OEM_COMMA"
        c == '.' -> "KEY_CODE_OEM_PERIOD"
        c == '-' -> "KEY_CODE_OEM_MINUS"
        c == '=' -> "KEY_CODE_OEM_PLUS"
        else -> UNKNOWN
    }

    fun onMac(): Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    /** "Enter", "ArrowDown", "ctrl+a", "cmd+shift+z", "F5", "a"; null when it does not parse. */
    fun parse(combo: String?, mac: Boolean = onMac()): Combo? {
        val raw = combo?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.length == 1) return single(raw[0], TabEvent.Mods.NONE)
        val parts = raw.split('+').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var mods = TabEvent.Mods.NONE
        var key: String? = null
        for (part in parts) {
            val next = modifier(part, mods, mac)
            if (next != null) { mods = next; continue }
            if (key != null) return null
            key = part
        }
        val word = key ?: return null
        NAMED[word]?.let { (code, char) -> return Combo(code, char, mods) }
        val n = word.takeIf { it.length >= 2 && it[0] == 'f' }?.drop(1)?.toIntOrNull()
        if (n != null && n in 1..12) return Combo("KEY_CODE_F$n", '\u0000', mods)
        return word.singleOrNull()?.let { single(it, mods) }
    }

    private fun single(c: Char, mods: TabEvent.Mods): Combo {
        val char = if (mods.shift && c.isLetter()) c.uppercaseChar() else c
        return Combo(codeOf(c), char, mods.copy(shift = mods.shift || c.isUpperCase()))
    }

    private fun modifier(word: String, mods: TabEvent.Mods, mac: Boolean): TabEvent.Mods? = when (word) {
        "cmd", "command", "meta", "super" -> if (mac) mods.copy(meta = true) else mods.copy(ctrl = true)
        "ctrl", "control" -> mods.copy(ctrl = true)
        "shift" -> mods.copy(shift = true)
        "alt", "option", "opt" -> mods.copy(alt = true)
        else -> null
    }
}

/** Event sequences, paced like a hand's. */
internal object TabGestures {
    const val HOVER_MS = 60L
    const val PRESS_MS = 80L
    const val KEY_GAP_MS = 12L
    const val STEP_MS = 16L

    /** Wheel delta per event, in the units the host's co-browse path sends. */
    const val NOTCH_PX = 100f

    fun click(x: Int, y: Int): List<TabEvent> = listOf(
        TabEvent.Move(x, y), TabEvent.Pause(HOVER_MS),
        TabEvent.Press(x, y), TabEvent.Pause(PRESS_MS), TabEvent.Release(x, y),
    )

    /** Wheel events of at most [NOTCH_PX] each, totalling |dx| and |dy| (capped at [SCROLL_MAX_PX] per axis). */
    fun scroll(x: Int, y: Int, dx: Int, dy: Int): List<TabEvent> = buildList {
        add(TabEvent.Move(x, y))
        add(TabEvent.Pause(HOVER_MS))
        val ax = min(abs(dx), SCROLL_MAX_PX)
        val ay = min(abs(dy), SCROLL_MAX_PX)
        val events = ceil(maxOf(ax, ay) / NOTCH_PX).toInt()
        for (i in 0 until events) {
            val stepX = (ax - i * NOTCH_PX).coerceIn(0f, NOTCH_PX) * dx.sign
            val stepY = (ay - i * NOTCH_PX).coerceIn(0f, NOTCH_PX) * dy.sign
            add(TabEvent.Wheel(x, y, stepX, stepY))
            add(TabEvent.Pause(STEP_MS))
        }
    }

    fun type(text: String): List<TabEvent> = buildList {
        for (c in text) {
            when (c) {
                '\n', '\r' -> addAll(stroke(TabKeys.Combo(TabKeys.RETURN, '\r', TabEvent.Mods.NONE)))
                '\t' -> addAll(stroke(TabKeys.Combo("KEY_CODE_TAB", '\u0000', TabEvent.Mods.NONE)))
                else -> addAll(stroke(TabKeys.Combo(TabKeys.codeOf(c), c, TabEvent.Mods(shift = c.isUpperCase()))))
            }
            add(TabEvent.Pause(KEY_GAP_MS))
        }
    }

    /** Down, the character (printable input without ctrl/meta, plus Enter), up. */
    fun stroke(combo: TabKeys.Combo): List<TabEvent> = buildList {
        add(TabEvent.KeyDown(combo.code, combo.char, combo.mods))
        val printable = combo.char != '\u0000' && (!combo.char.isISOControl() || combo.char == '\r')
        if (printable && !combo.mods.ctrl && !combo.mods.meta) add(TabEvent.KeyChar(combo.code, combo.char, combo.mods))
        add(TabEvent.KeyUp(combo.code, combo.mods))
    }
}

// ---------------------------------------------------------------------------------------------
// The tab seam: everything that needs the host's Browser sits behind this
// ---------------------------------------------------------------------------------------------

internal interface TabSurface {
    /** The live viewport in device pixels, or null. */
    suspend fun bitmap(): BufferedImage?

    /** Dispatch one engine event (never [TabEvent.Pause]); false when it did not go out. */
    suspend fun dispatch(event: TabEvent): Boolean

    /** Give the page keyboard focus before a key sequence. */
    suspend fun focus() {}

    /** Start routing this tab's alert/confirm/prompt/beforeunload to [sink]; null when the tab cannot. */
    fun captureDialogs(sink: DialogSink): DialogCapture? = null

    /** Wrap the host's popup handler so popups that stay open are reported to [onPopup]; null when it cannot. */
    fun capturePopups(onPopup: (PopupWindow) -> Unit): DialogCapture? = null
}

/** Plays [events] in order, sleeping on pauses. False as soon as one dispatch fails. */
internal suspend fun TabSurface.play(events: List<TabEvent>): Boolean {
    for (e in events) {
        if (e is TabEvent.Pause) delay(e.ms) else if (!dispatch(e)) return false
    }
    return true
}

/** One-shot EDT poster with a timeout: a wedged UI thread costs one failed step, never a hang. */
internal object Edt {
    const val TIMEOUT_MS = 5_000L

    fun <T : Any> on(timeoutMs: Long = TIMEOUT_MS, block: () -> T): T? {
        val future = CompletableFuture<T>()
        val live = AtomicBoolean(true)
        runCatching {
            java.awt.EventQueue.invokeLater {
                if (!live.compareAndSet(true, false)) return@invokeLater
                runCatching { future.complete(block()) }.onFailure { future.completeExceptionally(it) }
            }
        }.onFailure { return null }
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull().also { live.set(false) }
    }
}

/** [TabSurface] over the host's JxBrowser `Browser`, by reflection (no JxBrowser classes at compile time). */
internal class JxBrowserSurface private constructor(private val browser: Any) : TabSurface {

    private val loader: ClassLoader = browser.javaClass.classLoader
    private val events = JxEvents(loader)

    override suspend fun bitmap(): BufferedImage? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bitmap = cls(BROWSER).getMethod("bitmap").invoke(browser)
            val image = cls("com.teamdev.jxbrowser.view.swing.graphics.BitmapImage")
                .getMethod("toToolkit", cls("com.teamdev.jxbrowser.ui.Bitmap"))
                .invoke(null, bitmap) as java.awt.Image
            val w = image.getWidth(null)
            val h = image.getHeight(null)
            if (w <= 0 || h <= 0) return@runCatching null
            BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out ->
                val g = out.createGraphics()
                try { g.drawImage(image, 0, 0, null) } finally { g.dispose() }
            }
        }.getOrNull()
    }

    override suspend fun dispatch(event: TabEvent): Boolean {
        val built = runCatching { events.build(event) }.getOrNull() ?: return false
        val method = dispatchMethod(built) ?: return false
        // The engine's input entry point is the UI thread, as in the host's co-browse path.
        return Edt.on { method.invoke(browser, built); true } == true
    }

    override suspend fun focus() {
        runCatching { cls(BROWSER).getMethod("focus").invoke(browser) }
    }

    override fun captureDialogs(sink: DialogSink): DialogCapture? =
        runCatching { ProxyDialogCapture.install(JxCallbackHost(browser), loader, JX_DIALOG_SPECS, sink) }.getOrNull()

    override fun capturePopups(onPopup: (PopupWindow) -> Unit): DialogCapture? =
        runCatching { PopupProxyCapture.install(JxCallbackHost(browser), loader, JX_OPEN_POPUP_CALLBACK) { popup -> onPopup(JxPopupWindow(popup)) } }.getOrNull()

    private fun cls(name: String): Class<*> = Class.forName(name, true, loader)

    // Several `dispatch` overloads; the first by name is the wrong one for most events.
    private fun dispatchMethod(event: Any): Method? = runCatching {
        cls(BROWSER).methods.firstOrNull { it.name == "dispatch" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(event) }
    }.getOrNull()

    companion object {
        const val BROWSER = "com.teamdev.jxbrowser.browser.Browser"
        private const val SEARCH_DEPTH = 5
        private val found: MutableMap<BrowserIntegration, Any> = Collections.synchronizedMap(WeakHashMap())

        /** A surface over a Browser object itself (a popup window). */
        fun wrap(browser: Any): TabSurface = JxBrowserSurface(browser)

        /** The tab's surface, or null when no live JxBrowser sits behind [integration]. */
        fun of(integration: BrowserIntegration): TabSurface? {
            if (!runCatching { integration.isBrowserAvailable() }.getOrDefault(false)) return null
            val browser = found[integration]?.takeIf(::alive)
                ?: find(integration, 0, IdentityHashMap())?.also { found[integration] = it }
                ?: return null
            return JxBrowserSurface(browser)
        }

        private fun alive(browser: Any): Boolean = runCatching {
            Class.forName(BROWSER, true, browser.javaClass.classLoader).getMethod("isClosed").invoke(browser) != true
        }.getOrDefault(true)

        private fun find(o: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Any? {
            if (o == null || depth > SEARCH_DEPTH || seen.put(o, true) != null) return null
            if (isBrowser(o.javaClass)) return o
            if (o is String || o is Number || o is Boolean || o is Class<*>) return null
            var c: Class<*>? = o.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.type.isPrimitive || java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull() ?: continue
                    find(v, depth + 1, seen)?.let { return it }
                }
                c = c.superclass
            }
            return null
        }

        private fun isBrowser(c: Class<*>): Boolean {
            var k: Class<*>? = c
            while (k != null) {
                if (k.name == BROWSER || k.interfaces.any { it.name == BROWSER || implementsBrowser(it) }) return true
                k = k.superclass
            }
            return false
        }

        private fun implementsBrowser(i: Class<*>): Boolean = i.interfaces.any { it.name == BROWSER || implementsBrowser(it) }
    }
}

/** [TabEvent] to JxBrowser event objects, by reflection against the host's classes. */
internal class JxEvents(private val loader: ClassLoader) {

    private fun cls(name: String): Class<*> = Class.forName(name, true, loader)

    private fun point(x: Int, y: Int): Any =
        cls("com.teamdev.jxbrowser.ui.Point").getMethod("of", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, x, y)

    private fun keyCode(name: String): Any {
        val type = cls("com.teamdev.jxbrowser.ui.KeyCode")
        return runCatching { type.getField(name).get(null) }.getOrElse { type.getField(TabKeys.UNKNOWN).get(null) }
    }

    private fun mods(m: TabEvent.Mods): Any {
        var b = cls("com.teamdev.jxbrowser.ui.KeyModifiers").getMethod("newBuilder").invoke(null)
        b = call(b, "shiftDown", m.shift); b = call(b, "controlDown", m.ctrl)
        b = call(b, "altDown", m.alt); b = call(b, "metaDown", m.meta)
        return build(b)
    }

    private fun builder(event: String, arg: Any, argType: String): Any =
        cls("com.teamdev.jxbrowser.ui.event.$event").getMethod("newBuilder", cls(argType)).invoke(null, arg)

    private val primary: Any get() = cls("com.teamdev.jxbrowser.ui.MouseButton").getField("PRIMARY").get(null)

    fun build(e: TabEvent): Any? = when (e) {
        is TabEvent.Move -> build(builder("MouseMoved", point(e.x, e.y), POINT))
        is TabEvent.Press -> build(call(call(builder("MousePressed", point(e.x, e.y), POINT), "button", primary), "clickCount", e.clicks))
        is TabEvent.Release -> build(call(call(builder("MouseReleased", point(e.x, e.y), POINT), "button", primary), "clickCount", e.clicks))
        is TabEvent.Wheel -> {
            val (dx, dy) = engineWheel(e.dx, e.dy)
            var b = call(call(builder("MouseWheel", point(e.x, e.y), POINT), "deltaX", dx), "deltaY", dy)
            b = call(b, "scrollType", cls("com.teamdev.jxbrowser.ui.ScrollType").getField("UNIT_SCROLL").get(null))
            build(b)
        }
        is TabEvent.KeyDown -> build(call(call(builder("KeyPressed", keyCode(e.code), KEY_CODE), "keyChar", e.char), "keyModifiers", mods(e.mods)))
        is TabEvent.KeyChar -> build(call(call(builder("KeyTyped", keyCode(e.code), KEY_CODE), "keyChar", e.char), "keyModifiers", mods(e.mods)))
        is TabEvent.KeyUp -> build(call(builder("KeyReleased", keyCode(e.code), KEY_CODE), "keyModifiers", mods(e.mods)))
        is TabEvent.Pause -> null
    }

    companion object {
        private const val POINT = "com.teamdev.jxbrowser.ui.Point"
        private const val KEY_CODE = "com.teamdev.jxbrowser.ui.KeyCode"

        /** DOM signs (positive = down/right) to JxBrowser's `MouseWheel`, which is the opposite on both axes. */
        internal fun engineWheel(dx: Float, dy: Float): Pair<Float, Float> = (0f - dx) to (0f - dy)

        private fun call(target: Any, name: String, value: Any): Any {
            val method = target.javaClass.methods.first { it.name == name && it.parameterCount == 1 && accepts(it.parameterTypes[0], value) }
            method.isAccessible = true
            return method.invoke(target, value)
        }

        private fun build(builder: Any): Any {
            val method = builder.javaClass.methods.first { it.name == "build" && it.parameterCount == 0 }
            method.isAccessible = true
            return method.invoke(builder)
        }

        private fun accepts(type: Class<*>, value: Any): Boolean = type.isInstance(value) || when (type) {
            Int::class.javaPrimitiveType -> value is Int
            Float::class.javaPrimitiveType -> value is Float
            Char::class.javaPrimitiveType -> value is Char
            Boolean::class.javaPrimitiveType -> value is Boolean
            else -> false
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screenshots on disk
// ---------------------------------------------------------------------------------------------

internal fun scaleImage(image: BufferedImage, width: Int, height: Int): BufferedImage {
    if (image.width == width && image.height == height) return image
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(image, 0, 0, width, height, null)
    } finally {
        g.dispose()
    }
    return out
}

/** JPEG bytes (always an opaque RGB raster: an ARGB JPEG decodes with inverted colours in many readers). */
internal fun encodeJpeg(image: BufferedImage, quality: Double = SHOT_JPEG_QUALITY): ByteArray {
    val opaque = if (image.type == BufferedImage.TYPE_INT_RGB) image else BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
        val g = it.createGraphics()
        try { g.drawImage(image, 0, 0, java.awt.Color.WHITE, null) } finally { g.dispose() }
    }
    val sink = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    MemoryCacheImageOutputStream(sink).use { stream ->
        writer.output = stream
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality.coerceIn(0.1, 1.0).toFloat()
        }
        writer.write(null, IIOImage(opaque, null, null), params)
        stream.flush()
    }
    writer.dispose()
    return sink.toByteArray()
}

/** `<dir>/<tab>-<epoch ms>.jpg`, keeping the newest [keep] files. */
internal class ShotStore(
    private val dir: File = File(System.getProperty("user.home"), ".boss/cache/rpaengine/shots"),
    private val keep: Int = SHOT_KEEP,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun save(tabId: String, jpeg: ByteArray): File {
        dir.mkdirs()
        val safe = tabId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifEmpty { "tab" }
        val stamp = now()
        var file = File(dir, "$safe-$stamp.jpg")
        var n = 1
        while (file.exists()) file = File(dir, "$safe-$stamp-${n++}.jpg")
        file.writeBytes(jpeg)
        prune()
        return file
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        if (files.size <= keep) return
        files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .take(files.size - keep).forEach { runCatching { it.delete() } }
    }
}

// ---------------------------------------------------------------------------------------------
// Pointer overlay
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class PointerSpec(val label: String = POINTER_DEFAULT_LABEL, val color: String = POINTER_DEFAULT_COLOR)

internal const val POINTER_DEFAULT_LABEL = "Agent"
internal const val POINTER_DEFAULT_COLOR = "#6D28D9"
internal const val POINTER_LABEL_MAX = 40
internal const val POINTER_GLIDE_MS = 260L

/** The glide starts, and the input follows this long after: the click never waits for the pointer to arrive. */
internal const val POINTER_LEAD_MS = 60L
internal const val POINTER_ID = "__rpa_pointer"
internal const val POINTER_ATTR = "data-rpa-pointer"

private val HEX_COLOR = Regex("#[0-9a-fA-F]{3,8}")

internal fun isHexColor(s: String): Boolean = HEX_COLOR.matches(s)

/**
 * Ensures the overlay exists (re-created after a navigation at [from]) and glides it to ([x],[y]);
 * [ripple] adds the click ring. Built with createElement and CSSOM only (strict CSP blocks style
 * attributes in markup, Trusted Types blocks innerHTML); pointer-events:none keeps it out of hit testing.
 */
internal fun pointerScript(x: Int, y: Int, from: Pair<Int, Int>?, spec: PointerSpec, ripple: Boolean): String {
    val color = if (isHexColor(spec.color)) spec.color else POINTER_DEFAULT_COLOR
    val label = spec.label.take(POINTER_LABEL_MAX)
    val fx = from?.first ?: x
    val fy = from?.second ?: y
    return """(function(){
var d=document,id=${POINTER_ID.asJsString()},root=d.getElementById(id);
function st(e,css){e.style.cssText='all:initial;'+css;return e;}
if(root&&root.getAttribute('data-rpa-color')!=='$color'){root.remove();root=null;}
if(!root){
root=st(d.createElement('div'),'position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none;display:block;transform:translate(${fx}px,${fy}px);transition:transform ${POINTER_GLIDE_MS}ms cubic-bezier(.2,.7,.2,1);');
root.id=id;root.setAttribute('$POINTER_ATTR','1');root.setAttribute('data-rpa-color','$color');root.setAttribute('aria-hidden','true');
var ns='http://www.w3.org/2000/svg',svg=d.createElementNS(ns,'svg');
svg.setAttribute('width','22');svg.setAttribute('height','26');svg.setAttribute('viewBox','0 0 22 26');
svg.style.cssText='position:absolute;left:-2px;top:-1px;overflow:visible;filter:drop-shadow(0 1px 2px rgba(0,0,0,.35));';
var p=d.createElementNS(ns,'path');
p.setAttribute('d','M2 1 L2 21 L7.5 16 L11 24.5 L14.5 23 L11 14.8 L18.5 14.8 Z');
p.setAttribute('fill','$color');p.setAttribute('stroke','#ffffff');p.setAttribute('stroke-width','1.5');p.setAttribute('stroke-linejoin','round');
svg.appendChild(p);root.appendChild(svg);
var l=st(d.createElement('div'),'position:absolute;left:16px;top:21px;display:block;background:$color;color:#fff;font:600 11px/16px -apple-system,BlinkMacSystemFont,system-ui,sans-serif;padding:0 7px;border-radius:8px;white-space:nowrap;box-shadow:0 1px 3px rgba(0,0,0,.3);');
l.setAttribute('data-rpa-label','1');root.appendChild(l);
(d.body||d.documentElement).appendChild(root);
void root.offsetWidth;
}
var lab=root.querySelector('[data-rpa-label]');if(lab)lab.textContent=${label.asJsString()};
if(root.parentNode!==(d.body||d.documentElement))(d.body||d.documentElement).appendChild(root);
root.style.transform='translate(${x}px,${y}px)';
${if (ripple) rippleJs(x, y, color) else ""}
return 'ok';
})()"""
}

// Started by the animation's own delay, not setTimeout: timers in a hidden page are throttled.
private fun rippleJs(x: Int, y: Int, color: String): String =
    """try{var r=st(d.createElement('div'),'position:fixed;left:${x - 14}px;top:${y - 14}px;width:28px;height:28px;border-radius:50%;border:2px solid $color;box-sizing:border-box;z-index:2147483646;pointer-events:none;display:block;opacity:0;');
r.setAttribute('$POINTER_ATTR','1');(d.body||d.documentElement).appendChild(r);
if(r.animate){var a=r.animate([{transform:'scale(.3)',opacity:.9},{transform:'scale(1.6)',opacity:0}],{duration:450,delay:$POINTER_GLIDE_MS,easing:'ease-out',fill:'both'});a.onfinish=function(){r.remove();};}
else{r.remove();}}catch(e){}"""

internal fun pointerRemoveScript(): String =
    "(function(){var n=document.querySelectorAll('[$POINTER_ATTR]');for(var i=0;i<n.length;i++){n[i].remove();}return n.length;})()"

/** Removes every overlay node this plugin draws (pointer, ripple, highlight): opacity did not keep them out of the bitmap. */
internal fun overlayRemoveScript(): String =
    "(function(){var n=document.querySelectorAll('[data-rpa-pointer],[data-rpa-highlight]');" +
        "for(var i=0;i<n.length;i++){if(n[i].parentNode){n[i].parentNode.removeChild(n[i]);}}return n.length;})()"

/** The viewport centre of the element [locate] resolves to, as "x,y", clamped into the viewport; null when it has no box. */
internal fun elementCenterScript(locate: String): String =
    "(function(){try{var el=$locate;if(!el||!el.getBoundingClientRect){return null;}var r=el.getBoundingClientRect();" +
        "if(!(r.width>0||r.height>0)){return null;}" +
        "var w=window.innerWidth||1,h=window.innerHeight||1;" +
        "var x=Math.min(Math.max(Math.round(r.left+r.width/2),0),w-1),y=Math.min(Math.max(Math.round(r.top+r.height/2),0),h-1);" +
        "return x+','+y;}catch(e){return null;}})()"

// ---------------------------------------------------------------------------------------------
// Focused element
// ---------------------------------------------------------------------------------------------

/**
 * Describes the focused element as JSON matching [FocusedInfo]; never reads its value. With [selectAll] a
 * focused text field / contenteditable has its content selected, so typing replaces it.
 */
internal fun focusedScript(selectAll: Boolean): String =
    "(function () { try { " + OBSERVE_HELPERS_JS + SENSITIVE_DOM_JS +
        "var el = document.activeElement; " +
        "while (el && el.shadowRoot && el.shadowRoot.activeElement) { el = el.shadowRoot.activeElement; } " +
        "if (!el || el === document.body || el === document.documentElement) { return JSON.stringify({ tag: 'body', editable: false }); } " +
        "var tag = el.tagName.toLowerCase(); " +
        "var type = tag === 'input' ? (el.getAttribute('type') || 'text').toLowerCase() : null; " +
        "var role = el.getAttribute('role'); " +
        "var ce = el.isContentEditable === true && tag !== 'input' && tag !== 'textarea'; " +
        "var editable = tag === 'textarea' || ce || role === 'textbox' || role === 'searchbox' || " +
        "(tag === 'input' && ['text','search','email','url','tel','password','number'].indexOf(type) !== -1); " +
        "var lab = (el.labels && el.labels.length) ? el.labels[0] : null; " +
        "var label = norm(el.getAttribute('aria-label')) || (lab ? textOf(lab) : '') || norm(el.getAttribute('placeholder')) || " +
        "norm(el.getAttribute('title')) || norm(el.getAttribute('name')) || ''; " +
        "var options = null; if (tag === 'select') { options = Array.prototype.slice.call(el.options || [], 0, 30)" +
        ".map(function (o) { return norm(o.label || o.text); }); } " +
        (if (selectAll) {
            "if (editable) { try { if (ce) { var rg = document.createRange(); rg.selectNodeContents(el); " +
                "var sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(rg); } else { el.select(); } } catch (e) {} } "
        } else "") +
        "return JSON.stringify({ tag: tag, editable: editable, label: label || null, type: type, options: options, " +
        "sensitive: sensitiveEl(el) }); " +
        "} catch (e) { return JSON.stringify({ tag: null, editable: false }); } })()"

// ---------------------------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------------------------

/**
 * Runs the computer-use verbs. [surfaceFor] resolves the tab's [TabSurface] (null = no JxBrowser behind
 * it); the verbs that are JS-only (focused, pointer_hide) never ask for one.
 */
internal class ComputerUse(
    private val surfaceFor: (BrowserIntegration) -> TabSurface? = JxBrowserSurface::of,
    private val shots: ShotStore = ShotStore(),
) {
    /** Where each tab's pointer last was, so a re-injection after navigation starts from there. */
    private val positions = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    /** The label and color the pointer last used per tab, so a screenshot can put it back unchanged. */
    private val specs = java.util.concurrent.ConcurrentHashMap<String, PointerSpec>()

    private class TabDialogs(val pending: PendingDialogs, val capture: DialogCapture?, val popupCapture: DialogCapture? = null)

    private val dialogs = java.util.concurrent.ConcurrentHashMap<String, TabDialogs>()

    /** Popups opened from each captured tab, under `<tabId>#popup-<n>`. */
    private val registries = java.util.concurrent.ConcurrentHashMap<String, PopupRegistry>()

    private val newPending: () -> PendingDialogs = { PendingDialogs() }

    /** The element marks of each tab's latest look, by id. */
    private val marks = java.util.concurrent.ConcurrentHashMap<String, Map<String, MarkRef>>()

    /** The quiet wait of rpa_step action verbs; skipped while a dialog is pending (a script would block behind it). */
    suspend fun quiet(browser: BrowserIntegration, tabId: String) = awaitQuiet(browser, hasDialog = { pendingDialog(tabId) != null })

    /** The tab's pending dialog, for every rpa_step result. */
    fun pendingDialog(tabId: String): DialogInfo? = dialogs[tabId]?.pending?.current()

    /** Dialogs this plugin auto-answered since the last result (second dialog, timeout, capture end). */
    fun drainAutoDialogs(tabId: String): List<AutoDialog> = dialogs[tabId]?.pending?.drainAuto().orEmpty()

    /** The open popup behind a pseudo tab id, or null. */
    fun popupTarget(id: String): PopupWindow? = splitPopupId(id)?.let { (parent, n) -> registries[parent]?.get(n) }

    /** Open popups of the tab (or of the parent when [tabId] is itself a popup), plus ones that just closed. */
    fun popupsFor(tabId: String): List<PopupInfo> {
        val parent = splitPopupId(tabId)?.first ?: tabId
        val list = registries[parent]?.status().orEmpty()
        list.filter { !it.open }.forEach { endCapture(it.id) }
        return list
    }

    /** Restores every tab's host callbacks (plugin dispose). */
    fun dispose() {
        dialogs.keys.toList().forEach { endCapture(it) }
        registries.keys.toList().forEach { endCapture(it) }
    }

    private fun endCapture(tabId: String) {
        registries.remove(tabId)?.ids()?.forEach { endCapture(it) }
        val d = dialogs.remove(tabId) ?: return
        d.pending.close()
        runCatching { d.popupCapture?.restore() }
        runCatching { d.capture?.restore() }
    }

    /** A popup that stayed open: register it and give it the same dialog capture. */
    private fun adopt(parentId: String, window: PopupWindow) {
        val id = registries.computeIfAbsent(parentId) { PopupRegistry(parentId) }.add(window)
        val pending = newPending()
        val capture = window.surface.captureDialogs { info, responder -> pending.offer(info, responder) } ?: return
        dialogs[id] = TabDialogs(pending, capture)
    }

    /** The surface behind this tab id: a popup's own, else the tab's JxBrowser. */
    private fun surfaceNow(browser: BrowserIntegration, tabId: String): TabSurface? =
        popupTarget(tabId)?.surface ?: surfaceFor(browser)

    /** First computer-use action on a tab: remember the host's callbacks and install ours. */
    private fun startCapture(browser: BrowserIntegration, tabId: String) {
        if (dialogs.containsKey(tabId)) return
        val surface = surfaceNow(browser, tabId) ?: return
        val pending = newPending()
        val capture = surface.captureDialogs { info, responder -> pending.offer(info, responder) }
        val popupCapture = if (splitPopupId(tabId) == null) surface.capturePopups { w -> adopt(tabId, w) } else null
        if (capture == null && popupCapture == null) return
        if (dialogs.putIfAbsent(tabId, TabDialogs(pending, capture, popupCapture)) != null) {
            popupCapture?.restore() // lost a race: ours was installed twice, keep the first
            capture?.restore()
        }
    }

    suspend fun run(
        browser: BrowserIntegration,
        tabId: String,
        action: RpaActionConfig,
        pointer: PointerSpec?,
        allowSensitive: Boolean,
        snap: SnapBox? = null,
        hideOverlay: Boolean = false,
        /** screenshot with ensure_visible: selects the tab; null for popups and when not asked. */
        reveal: (suspend () -> Unit)? = null,
        /** click_at / type_text: a mark id from the tab's latest look. */
        element: String? = null,
    ): CuOutcome {
        val value = action.value
        if (action.type != CuTypes.CU_END && action.type != CuTypes.DIALOG_ANSWER) startCapture(browser, tabId)
        return when (action.type) {
            CuTypes.CU_END -> {
                endCapture(tabId) // first: a held dialog is answered before any script could block on it
                positions.remove(tabId)
                specs.remove(tabId)
                marks.remove(tabId)
                orNullUnlessCancelled { browser.executeJavaScript(pointerRemoveScript()) }
                CuOutcome(true)
            }
            CuTypes.DIALOG_ANSWER -> {
                val answer = DialogAnswer.parse(value) ?: return CuOutcome(false, "dialog_answer value must be 'ok', 'cancel' or 'ok:<text>'")
                val pending = dialogs[tabId]?.pending
                // Off the caller's thread, never the EDT: the Action's send is a plain RPC reply.
                val answered = pending != null && withContext(kotlinx.coroutines.Dispatchers.IO) { pending.answer(answer) }
                if (!answered) return CuOutcome(false, "No browser dialog is pending on this tab")
                quiet(browser, tabId)
                CuOutcome(true)
            }
            CuTypes.POINTER_HIDE -> {
                positions.remove(tabId)
                specs.remove(tabId)
                browser.executeJavaScript(pointerRemoveScript())
                CuOutcome(true)
            }
            CuTypes.PAGE_TEXT -> {
                val max = (value?.trim()?.toIntOrNull() ?: PAGE_TEXT_DEFAULT).coerceIn(1, PAGE_TEXT_LIMIT)
                val text = orNullUnlessCancelled { browser.executeJavaScript(pageTextScript(max)) } as? String
                    ?: return CuOutcome(false, "Could not read the page text (the page may be navigating)")
                CuOutcome(true, text = text)
            }
            CuTypes.LOCATE_TEXT -> {
                val located = (orNullUnlessCancelled { browser.executeJavaScript(locateTextScript(value.orEmpty())) } as? String)
                    ?.let { runCatching { TabJson.decodeFromString(LocatedInfo.serializer(), it) }.getOrNull() }
                    ?: return CuOutcome(false, "Could not search the page text (the page may be navigating)")
                CuOutcome(true, located = located)
            }
            CuTypes.FOCUSED -> CuOutcome(true, focused = readFocused(browser, selectAll = false) ?: return CuOutcome(false, "Could not read the focused element (the page may be navigating)"))
            else -> {
                val surface = surfaceNow(browser, tabId)
                    ?: return CuOutcome(false, "Screenshots and real input need a JxBrowser tab; none is behind this one")
                when (action.type) {
                    CuTypes.SCREENSHOT -> screenshot(browser, surface, tabId, value?.trim()?.toIntOrNull() ?: SHOT_DEFAULT_MAX_SIDE, hideOverlay, reveal)
                    CuTypes.LOOK -> look(browser, surface, tabId, value, reveal)
                    CuTypes.CLICK_AT -> clickAt(browser, surface, tabId, value, pointer, snap, element)
                    CuTypes.TYPE_TEXT -> {
                        var clicked: ClickedInfo? = null
                        if (element != null) {
                            val c = clickAt(browser, surface, tabId, null, pointer, null, element)
                            if (!c.ok) return c
                            clicked = c.clicked
                        }
                        val t = typeText(browser, surface, tabId, value.orEmpty(), allowSensitive)
                        CuOutcome(t.ok, t.error, focused = t.focused, clicked = clicked)
                    }
                    CuTypes.KEY -> key(browser, surface, tabId, value)
                    CuTypes.SCROLL_BY -> scrollBy(browser, surface, tabId, value, pointer)
                    else -> CuOutcome(false, "Unknown action type '${action.type}'")
                }
            }
        }
    }

    /** Hook for selector actions: glide the pointer to the element's centre before the action touches it. */
    fun pointerHook(tabId: String, spec: PointerSpec, ripple: Boolean): ElementHook = { browser, locate ->
        val centre = orNullUnlessCancelled { browser.executeJavaScript(elementCenterScript(locate)) }?.let { parsePoint(it.toString()) }
        if (centre != null) glide(browser, tabId, centre.first, centre.second, spec, ripple)
    }

    private suspend fun glide(browser: BrowserIntegration, tabId: String, x: Int, y: Int, spec: PointerSpec, ripple: Boolean) {
        orNullUnlessCancelled { browser.executeJavaScript(pointerScript(x, y, positions[tabId], spec, ripple)) }
        positions[tabId] = x to y
        specs[tabId] = spec
        delay(POINTER_LEAD_MS)
    }

    private suspend fun readMeta(browser: BrowserIntegration): PageMeta? =
        PageMeta.parse(orNullUnlessCancelled { browser.executeJavaScript(PageMeta.SCRIPT) })

    private suspend fun readViewport(browser: BrowserIntegration): Viewport? =
        Viewport.parse(orNullUnlessCancelled { browser.executeJavaScript(Viewport.SCRIPT) })

    private suspend fun readFocused(browser: BrowserIntegration, selectAll: Boolean): FocusedInfo? =
        (orNullUnlessCancelled { browser.executeJavaScript(focusedScript(selectAll)) } as? String)
            ?.let { runCatching { TabJson.decodeFromString(FocusedInfo.serializer(), it) }.getOrNull() }
            ?.takeIf { it.tag != null }

    /** ensure_visible: selects a hidden tab, waits until it reports visible, gives it a frame. Returns the last meta and whether it selected. */
    private suspend fun ensureShown(browser: BrowserIntegration, reveal: (suspend () -> Unit)?): Pair<PageMeta?, Boolean> {
        var meta = readMeta(browser)
        var revealed = false
        if (reveal != null && meta?.visible == false) {
            // A hidden tab's bitmap is stale (paint-only changes never show): select it, wait for 'visible', give it a frame.
            revealed = runCatching { reveal(); true }.getOrDefault(false)
            if (revealed) {
                var waited = 0L
                while (waited < REVEAL_TIMEOUT_MS && meta?.visible != true) {
                    delay(REVEAL_POLL_MS); waited += REVEAL_POLL_MS
                    meta = readMeta(browser)
                }
                if (meta?.visible == true) delay(REVEAL_FRAME_MS)
            }
        }
        return meta to revealed
    }

    private suspend fun screenshot(browser: BrowserIntegration, surface: TabSurface, tabId: String, maxSide: Int, hideOverlay: Boolean, reveal: (suspend () -> Unit)?): CuOutcome {
        val (meta, revealed) = ensureShown(browser, reveal)
        val viewport = readViewport(browser) ?: return CuOutcome(false, "Could not read the viewport size (the page may be navigating)")
        val scroll = ScrollInfo.parse(orNullUnlessCancelled { browser.executeJavaScript(ScrollInfo.SCRIPT) })
        val contentHash = (orNullUnlessCancelled { browser.executeJavaScript(contentHashScript()) } as? String)?.trim('"')?.takeIf { CONTENT_HASH_RE.matches(it) }
        // Default: the pointer stays untouched (no DOM churn, no flicker between actions). hide_overlay removes the
        // overlay nodes for the capture and puts the pointer back at its remembered spot, without a glide.
        val image = if (!hideOverlay) {
            surface.bitmap()
        } else {
            orNullUnlessCancelled { browser.executeJavaScript(overlayRemoveScript()) }
            try {
                delay(OVERLAY_SETTLE_MS) // one compositor frame, so the bitmap no longer holds the overlay
                surface.bitmap()
            } finally {
                val at = positions[tabId]
                val spec = specs[tabId]
                if (at != null && spec != null) {
                    withContext(NonCancellable) { orNullUnlessCancelled { browser.executeJavaScript(pointerScript(at.first, at.second, at, spec, false)) } }
                }
            }
        } ?: return CuOutcome(false, "The browser returned no bitmap")
        val (w, h) = fitShotSize(image.width, image.height, maxSide)
        val file = shots.save(tabId, encodeJpeg(scaleImage(image, w, h)))
        return CuOutcome(true, screenshot = ScreenshotInfo(file.absolutePath, w, h, viewport.width, viewport.height, viewport.dpr, scroll, contentHash, meta?.url, meta?.title, meta?.visible, revealed.takeIf { it }, meta?.modal))
    }

    private suspend fun look(browser: BrowserIntegration, surface: TabSurface, tabId: String, value: String?, reveal: (suspend () -> Unit)?): CuOutcome {
        val opts = try { LookOptions.parse(value) } catch (e: IllegalArgumentException) { return CuOutcome(false, e.message) }
        var revealed = false
        if (reveal != null) revealed = ensureShown(browser, reveal).second
        val raw = (orNullUnlessCancelled { browser.executeJavaScript(lookScript(opts)) } as? String)
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return CuOutcome(false, "Could not read the page (the page may be navigating)")
        fun part(k: String): String? = (raw[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val viewport = Viewport.parse(part("vp")) ?: return CuOutcome(false, "Could not read the viewport size (the page may be navigating)")
        val meta = PageMeta.parse(part("meta"))
        val scroll = ScrollInfo.parse(part("scroll"))
        val hash = part("hash")?.trim('"')?.takeIf { CONTENT_HASH_RE.matches(it) }
        val located = LinkedHashMap<String, LocatedInfo>()
        val locRaw = raw["located"] as? JsonObject
        for (phrase in opts.locate) {
            located[phrase] = (locRaw?.get(phrase) as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { runCatching { TabJson.decodeFromString(LocatedInfo.serializer(), it) }.getOrNull() }
                ?: LocatedInfo(found = false, count = 0, inView = false)
        }
        val rawEls = part("els")
            ?.let { runCatching { TabJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RawLookElement.serializer()), it) }.getOrNull() }
            .orEmpty().filter { isMarkId(it.id) }.take(LOOK_MAX_ELEMENTS)
        if (opts.elements) {
            marks[tabId] = rawEls.associate { it.id to MarkRef(it.xp, normalizeLabel(it.label).orEmpty(), it.tag) }
        }
        val image = surface.bitmap() ?: return CuOutcome(false, "The browser returned no bitmap")
        val (w, h) = fitShotSize(image.width, image.height, opts.maxSide)
        val file = shots.save(tabId, encodeJpeg(scaleImage(image, w, h), opts.quality))
        val shot = ScreenshotInfo(file.absolutePath, w, h, viewport.width, viewport.height, viewport.dpr, scroll, hash, meta?.url, meta?.title, meta?.visible, revealed.takeIf { it }, meta?.modal)
        return CuOutcome(true, look = LookResult(shot, located, rawEls.map { it.toElement() }, pageText = part("text")))
    }

    /** Resolves mark [id] on the page: its current clickable point, or why there is none (disabled/covered/gone). */
    private suspend fun resolveMark(browser: BrowserIntegration, tabId: String, id: String): MarkAnswer {
        val ref = marks[tabId]?.get(id) ?: return MarkAnswer("gone")
        return (orNullUnlessCancelled { browser.executeJavaScript(markScript(id, ref)) } as? String)
            ?.let { runCatching { TabJson.decodeFromString(MarkAnswer.serializer(), it) }.getOrNull() }
            ?: MarkAnswer("gone")
    }

    private suspend fun clickAt(browser: BrowserIntegration, surface: TabSurface, tabId: String, value: String?, pointer: PointerSpec?, snap: SnapBox? = null, element: String? = null): CuOutcome {
        val fallback = parsePoint(value)
        var clicked: ClickedInfo? = null
        var resolved: Pair<Int, Int>? = null
        if (element != null) {
            val a = resolveMark(browser, tabId, element)
            val name = a.label ?: marks[tabId]?.get(element)?.label ?: element
            when (a.state) {
                "ok" -> { resolved = a.x to a.y; clicked = ClickedInfo(element, a.label, a.tag) }
                "disabled" -> return CuOutcome(false, "'$name' is disabled")
                "covered" -> return CuOutcome(false, "'$name' is covered by ${a.cover ?: "another element"}")
                else -> if (fallback == null) return CuOutcome(false, "Element '$element' is gone and no x,y fallback was given; take a new look")
            }
        }
        var (x, y) = resolved ?: fallback ?: return CuOutcome(false, "click_at value must be 'x,y' in CSS px")
        val viewport = readViewport(browser) ?: return CuOutcome(false, "Could not read the viewport size (the page may be navigating)")
        pointInViewport(x, y, viewport.width, viewport.height)
            ?: return CuOutcome(false, "($x,$y) is outside the ${viewport.width}x${viewport.height} viewport")
        var snapped: SnapInfo? = null
        if (snap != null && resolved == null) {
            val answer = (orNullUnlessCancelled { browser.executeJavaScript(snapScript(x, y, snap)) } as? String)
                ?.let { runCatching { TabJson.decodeFromString(SnapAnswer.serializer(), it) }.getOrNull() }
            if (answer != null && answer.moved) {
                val to = pointInViewport(answer.x, answer.y, viewport.width, viewport.height)
                if (to != null) {
                    snapped = SnapInfo("$x,$y", "${to.first},${to.second}", answer.tag, answer.label)
                    x = to.first; y = to.second
                }
            }
        }
        if (pointer != null) glide(browser, tabId, x, y, pointer, ripple = true)
        surface.focus()
        if (!surface.play(TabGestures.click(x, y))) return CuOutcome(false, "The browser did not accept the click", snapped = snapped)
        quiet(browser, tabId)
        return CuOutcome(true, snapped = snapped, clicked = clicked)
    }

    private suspend fun typeText(browser: BrowserIntegration, surface: TabSurface, tabId: String, text: String, allowSensitive: Boolean): CuOutcome {
        val focused = readFocused(browser, selectAll = false)
            ?: return CuOutcome(false, "Could not read the focused element (the page may be navigating)")
        val isSelect = focused.tag == "select"
        if (!isSelect && !focused.editable) {
            return CuOutcome(false, "The focused element is not a text field or select; click one first", focused = focused)
        }
        if (focused.sensitive && !allowSensitive) {
            throw TabToolException(
                TabErrorCodes.INVALID_INPUT,
                "The focused field is a password, payment or one-time-code field; pass allow_sensitive: true to type into it",
            )
        }
        surface.focus()
        val events = if (isSelect) {
            // Close the native popup, then type-ahead picks the option on the closed control.
            TabGestures.stroke(TabKeys.Combo(TabKeys.ESCAPE, '\u0000', TabEvent.Mods.NONE)) + TabEvent.Pause(60) + TabGestures.type(text)
        } else {
            readFocused(browser, selectAll = true)
            TabGestures.type(text)
        }
        if (!surface.play(events)) return CuOutcome(false, "The browser did not accept the key events", focused = focused)
        quiet(browser, tabId)
        return CuOutcome(true, focused = readFocused(browser, selectAll = false) ?: focused)
    }

    private suspend fun key(browser: BrowserIntegration, surface: TabSurface, tabId: String, value: String?): CuOutcome {
        val combo = TabKeys.parse(value) ?: return CuOutcome(false, "Unknown key '$value'")
        surface.focus()
        if (!surface.play(TabGestures.stroke(combo))) return CuOutcome(false, "The browser did not accept the key event")
        quiet(browser, tabId)
        return CuOutcome(true)
    }

    private suspend fun scrollBy(browser: BrowserIntegration, surface: TabSurface, tabId: String, value: String?, pointer: PointerSpec?): CuOutcome {
        val (dx, dy) = parseScrollDelta(value) ?: return CuOutcome(false, "scroll_by value must be 'dy' or 'dx,dy' (integer px)")
        val viewport = readViewport(browser) ?: return CuOutcome(false, "Could not read the viewport size (the page may be navigating)")
        val cx = viewport.width / 2
        val cy = viewport.height / 2
        if (pointer != null) glide(browser, tabId, cx, cy, pointer, ripple = false)
        if (!surface.play(TabGestures.scroll(cx, cy, dx, dy))) return CuOutcome(false, "The browser did not accept the wheel event")
        quiet(browser, tabId)
        return CuOutcome(true)
    }

    private companion object {
        const val OVERLAY_SETTLE_MS = 80L
    }
}
