package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.BrowserIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/*
 * Child frames (iframes, cross-origin and nested) for look, page_text, locate_text, content_hash, click_at/type_text
 * by element and focused. The top document is still read through the tab's BrowserIntegration; each child frame
 * through JxBrowser's Frame (Browser.mainFrame() -> Frame.children(), Frame.name(), Frame.executeJavaScript(String,
 * Consumer)) by reflection. Frame has no URL accessor, so a frame's script reports its own location.href.
 *
 * Geometry: each parent frame's script lists its <iframe>/<frame> elements with their content-box offset in that
 * frame's viewport; a child's offset in the TOP viewport is the sum along the chain, and what of it is on screen is
 * the intersection of every ancestor's visible part. Real mouse events are dispatched at the top-viewport point:
 * Chromium hit-tests the root widget and routes the event into the out-of-process frame under it.
 */

internal const val FRAME_MAX = 10
internal const val FRAME_MAX_DEPTH = 3
internal const val FRAME_EVAL_TIMEOUT_MS = 2_000L
internal const val FRAME_LIST_MAX = 50

/** One child frame, however it is reached. */
internal interface SubFrame {
    /** window.name of the frame (the iframe's name attribute unless the page changed it); "" when none. */
    fun name(): String
    fun children(): List<SubFrame>
    /** The script's answer, or null when the frame did not answer in time or is gone. */
    suspend fun eval(script: String): Any?
}

/** A JxBrowser `Frame`, by reflection. */
internal class JxFrame(private val frame: Any) : SubFrame {
    private val type: Class<*> = Class.forName(FRAME_CLASS, true, frame.javaClass.classLoader)

    override fun name(): String = runCatching { type.getMethod("name").invoke(frame) as? String }.getOrNull().orEmpty()

    override fun children(): List<SubFrame> = runCatching {
        (type.getMethod("children").invoke(frame) as? List<*>).orEmpty().filterNotNull().map { JxFrame(it) }
    }.getOrDefault(emptyList())

    // The callback form: a frame that never answers costs FRAME_EVAL_TIMEOUT_MS, never a blocked thread.
    override suspend fun eval(script: String): Any? = withContext(Dispatchers.IO) {
        runCatching {
            val future = CompletableFuture<Any?>()
            val m = type.methods.first { it.name == "executeJavaScript" && it.parameterCount == 2 && it.parameterTypes[1] == Consumer::class.java }
            m.invoke(frame, script, Consumer<Any?> { future.complete(it) })
            future.get(FRAME_EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    companion object {
        const val FRAME_CLASS = "com.teamdev.jxbrowser.frame.Frame"

        /** The main frame's children of a JxBrowser `Browser`. */
        fun childrenOf(browser: Any): List<SubFrame> = runCatching {
            val b = Class.forName(JxBrowserSurface.BROWSER, true, browser.javaClass.classLoader)
            val main = (b.getMethod("mainFrame").invoke(browser) as? java.util.Optional<*>)?.orElse(null) ?: return emptyList()
            JxFrame(main).children()
        }.getOrDefault(emptyList())
    }
}

/** A [BrowserIntegration] over one child frame, so the page-script helpers (quiet wait, mark, focused) run there. */
internal class FrameIntegration(val frame: SubFrame) : BrowserIntegration {
    override suspend fun executeJavaScript(script: String): Any? = frame.eval(script)
    override fun isBrowserAvailable(): Boolean = true
    override suspend fun getCurrentUrl(): String? = frame.eval("location.href") as? String
}

/** One iframe element as its parent's [framesListScript] sees it; geometry in the parent's viewport CSS px. */
@Serializable
internal data class IframeInfo(
    /** Index among the document's iframe/frame elements (document order). */
    val i: Int,
    /** Index in the parent's window.frames (== the frame-tree child order), -1 when unknown. */
    val fi: Int = -1,
    val name: String = "",
    val src: String = "",
    /** Content-box origin and size. */
    val x: Double = 0.0, val y: Double = 0.0, val w: Double = 0.0, val h: Double = 0.0,
    /** Rendered: has size, not display:none / visibility:hidden. */
    val shown: Boolean = false,
    /** The on-screen part [l,t,r,b], null when scrolled out of view. */
    val vis: List<Double>? = null,
    /** 4x4 row-major samples over [vis]: '1' where something else in the parent sits on top of the iframe. */
    val cov: String? = null,
    /** Inside the parent's open modal. */
    val mod: Boolean = false,
    /** title, aria-label or name. */
    val lab: String = "",
)

internal data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
    fun intersect(o: Box): Box? = Box(maxOf(l, o.l), maxOf(t, o.t), minOf(r, o.r), minOf(b, o.b)).takeIf { it.r > it.l && it.b > it.t }
    fun shift(dx: Double, dy: Double) = Box(l + dx, t + dy, r + dx, b + dy)
}

/** Where an iframe is covered in its parent, in top-viewport px. */
internal class CoverGrid(val box: Box, private val cells: String) {
    fun coveredAt(x: Double, y: Double): Boolean {
        if (cells.length != 16 || x < box.l || x >= box.r || y < box.t || y >= box.b) return false
        val c = ((x - box.l) / (box.r - box.l) * 4).toInt().coerceIn(0, 3)
        val r = ((y - box.t) / (box.b - box.t) * 4).toInt().coerceIn(0, 3)
        return cells[r * 4 + c] == '1'
    }
}

/** One step down the frame tree: child index in Frame.children(), the iframe's index in the parent's list, the child's URL then. */
internal data class FrameHop(val child: Int, val iframe: Int, val url: String?)

/** A frame placed in the top viewport. The top document is [FrameView.top]. */
internal class FrameView(
    /** "" for the top document, else child indexes joined by '.', e.g. "0" or "0.1". */
    val path: String,
    val handle: SubFrame?,
    val offX: Double,
    val offY: Double,
    /** What of the frame is on screen, in top px; null when none of it is. */
    val clip: Box?,
    val covers: List<CoverGrid>,
    /** An ancestor iframe is labelled as a card / password / one-time-code frame. */
    val sensitive: Boolean,
    /** The depth-1 ancestor iframe sits in the top document's modal. */
    val inTopModal: Boolean,
    val hops: List<FrameHop>,
    val host: String,
) {
    val isTop: Boolean get() = handle == null

    companion object {
        fun top(vw: Int, vh: Int, url: String?) =
            FrameView("", null, 0.0, 0.0, Box(0.0, 0.0, vw.toDouble(), vh.toDouble()), emptyList(), false, false, emptyList(), hostOf(url, ""))
    }
}

/** A child frame's answer to the walk's script. */
internal class FrameAnswer(val view: FrameView, val data: JsonObject)

internal fun hostOf(url: String?, fallback: String): String {
    val u = url?.trim().orEmpty()
    if (u.isEmpty() || u.startsWith("about:")) return fallback
    return runCatching { java.net.URI(u).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: fallback
}

private fun stripFragment(u: String) = u.substringBefore('#')

/** A child frame known by its index in Frame.children(), its name and the URL its script reported. */
internal data class ChildId(val index: Int, val name: String, val url: String?)

/**
 * Child frame index -> its iframe element. Matched by name (a non-empty name shared by exactly one iframe), then by
 * URL (the iframe's src, fragment ignored, when exactly one unmatched iframe has it), then by order: the remaining
 * children in Frame.children() order against the remaining iframes in window.frames order (Chromium's frame-tree
 * child order), document order for those without a window.frames index.
 */
internal fun matchFrames(children: List<ChildId>, iframes: List<IframeInfo>): Map<Int, IframeInfo> {
    val out = LinkedHashMap<Int, IframeInfo>()
    val free = iframes.toMutableList()
    for (c in children) {
        if (c.name.isEmpty()) continue
        val hit = free.filter { it.name == c.name }.singleOrNull() ?: continue
        out[c.index] = hit; free.remove(hit)
    }
    for (c in children) {
        if (c.index in out) continue
        val u = c.url?.let(::stripFragment)?.takeIf { it.isNotEmpty() } ?: continue
        val hit = free.filter { stripFragment(it.src) == u }.singleOrNull() ?: continue
        out[c.index] = hit; free.remove(hit)
    }
    val rest = free.sortedWith(compareBy<IframeInfo> { if (it.fi >= 0) 0 else 1 }.thenBy { if (it.fi >= 0) it.fi else it.i })
    val left = children.filter { it.index !in out }.sortedBy { it.index }
    for ((k, c) in left.withIndex()) out[c.index] = rest.getOrNull(k) ?: break
    return out
}

internal fun iframesOf(data: JsonObject?): List<IframeInfo> =
    (data?.get("f") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.let { runCatching { TabJson.decodeFromString(ListSerializer(IframeInfo.serializer()), it) }.getOrNull() }
        .orEmpty()

internal fun urlOf(data: JsonObject?): String? = (data?.get("u") as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The child's place in the top viewport, from its parent's place and the iframe element hosting it. */
internal fun childView(parent: FrameView, f: IframeInfo, childIndex: Int, handle: SubFrame, url: String?): FrameView {
    val vis = f.vis?.takeIf { it.size == 4 }?.let { Box(it[0], it[1], it[2], it[3]).shift(parent.offX, parent.offY) }
    val clip = if (vis == null) null else parent.clip?.intersect(vis)
    val covers = if (vis != null && f.cov != null) parent.covers + CoverGrid(vis, f.cov) else parent.covers
    return FrameView(
        path = if (parent.isTop) "$childIndex" else "${parent.path}.$childIndex",
        handle = handle,
        offX = parent.offX + f.x,
        offY = parent.offY + f.y,
        clip = clip,
        covers = covers,
        sensitive = parent.sensitive || looksSensitiveName(f.lab) || looksSensitiveName(f.name),
        inTopModal = if (parent.isTop) f.mod else parent.inTopModal,
        hops = parent.hops + FrameHop(childIndex, f.i, url),
        host = hostOf(url, parent.host),
    )
}

/**
 * Runs [script] in the rendered child frames under [top], breadth first, at most [maxFrames] frames and
 * [maxDepth] levels; the frames of one level run in parallel. [script]'s answer must be a JSON object string with
 * "u" (location.href) and "f" ([framesListScript]'s answer). Hidden frames and frames that match no rendered iframe
 * are skipped; frames scrolled out of view are kept (their text counts) with a null clip.
 */
internal suspend fun walkFrames(
    top: FrameView,
    topData: JsonObject,
    roots: List<SubFrame>,
    script: String,
    maxFrames: Int = FRAME_MAX,
    maxDepth: Int = FRAME_MAX_DEPTH,
): List<FrameAnswer> {
    class Parent(val view: FrameView, val iframes: List<IframeInfo>, val kids: List<SubFrame>)
    val out = ArrayList<FrameAnswer>()
    var level = listOf(Parent(top, iframesOf(topData), roots))
    var budget = maxFrames
    for (depth in 1..maxDepth) {
        if (budget <= 0 || level.isEmpty()) break
        // Children worth a script: those a rendered iframe names, or holds by window.frames index; in-view ones first.
        val jobs = ArrayList<Triple<Parent, Int, SubFrame>>()
        for (p in level) {
            val shown = p.iframes.filter { it.shown }
            if (shown.isEmpty() || p.kids.isEmpty()) continue
            val names = p.kids.map { runCatching { it.name() }.getOrDefault("") }
            fun rank(k: Int): Int? {
                val f = shown.firstOrNull { (names[k].isNotEmpty() && it.name == names[k]) || it.fi == k } ?: return null
                return if (f.vis != null) 0 else 1
            }
            val ranked = p.kids.indices.mapNotNull { k -> rank(k)?.let { k to it } }
            val picked = if (ranked.isNotEmpty()) ranked.sortedBy { it.second }.map { it.first } else p.kids.indices.toList()
            for (k in picked) jobs += Triple(p, k, p.kids[k])
        }
        val run = jobs.take(budget)
        budget -= run.size
        val answers = coroutineScope {
            run.map { (p, k, h) ->
                async {
                    val data = (orNullUnlessCancelled { h.eval(script) } as? String)
                        ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
                    Triple(p, k, data)
                }
            }.awaitAll()
        }
        val next = ArrayList<Parent>()
        for ((p, group) in answers.groupBy { it.first }) {
            val ids = group.filter { it.third != null }.map { (_, k, d) -> ChildId(k, runCatching { p.kids[k].name() }.getOrDefault(""), urlOf(d)) }
            val match = matchFrames(ids, p.iframes)
            for ((_, k, d) in group) {
                val f = match[k]?.takeIf { it.shown } ?: continue
                val data = d ?: continue
                val view = childView(p.view, f, k, p.kids[k], urlOf(data))
                out += FrameAnswer(view, data)
                if (depth < maxDepth) next += Parent(view, iframesOf(data), runCatching { p.kids[k].children() }.getOrDefault(emptyList()))
            }
        }
        level = next
    }
    return out
}

/** The frames' content hashes folded into one (djb2, hex); the top hash alone when there are no frames. */
internal fun combineHashes(hashes: List<String?>): String? {
    val top = hashes.firstOrNull() ?: return null
    if (hashes.size == 1) return top
    val s = hashes.joinToString("\u0001") { it.orEmpty() }
    var h = 5381
    for (c in s) h = (h shl 5) + h + c.code
    return (h.toLong() and 0xffffffffL).toString(16)
}

/** Page text with each frame's block after the top's, as "[frame <host>]\n<text>", cut to [max] chars in all. */
internal fun joinFrameText(top: String, frames: List<Pair<String, String>>, max: Int): String {
    val sb = StringBuilder(top)
    for ((host, text) in frames) {
        if (text.isBlank()) continue
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("[frame ").append(host).append("]\n").append(text.trim())
        if (sb.length >= max) break
    }
    return if (sb.length > max) sb.substring(0, max) else sb.toString()
}

/** One frame's located answer for a phrase, merged across frames: counts add up; an in-view match wins, top first. */
internal fun mergeLocated(top: LocatedInfo, frames: List<Pair<FrameView, LocatedInfo>>): LocatedInfo {
    val found = frames.filter { it.second.found }
    if (found.isEmpty()) return top
    val count = top.count + found.sumOf { it.second.count }
    if (top.found && top.inView) return top.copy(count = count)
    for ((v, l) in found) {
        if (!l.inView || l.rect == null) continue
        val r = l.rect
        val box = v.clip?.intersect(Box(r.x + v.offX, r.y + v.offY, r.x + r.w + v.offX, r.y + r.h + v.offY)) ?: continue
        return LocatedInfo(true, count, true, "in_view", 0.0, LocatedRect(box.l.toInt(), box.t.toInt(), (box.r - box.l).toInt(), (box.b - box.t).toInt()))
    }
    if (top.found) return top.copy(count = count)
    // Only inside a frame that is (partly) off screen: where that frame sits says which way to scroll the page.
    val (v, l) = found.first()
    val dir = if (v.clip != null) (l.direction ?: "below") else if (v.offY < 0) "above" else "below"
    return LocatedInfo(true, count, false, dir, l.screens)
}

/** One element of a look, as returned, and how to find it again. */
internal class PlacedElement(val element: LookElement, val mark: MarkRef)

/**
 * The look's element list across frames. Frame elements move into top-viewport px and are clipped to what of their
 * frame is on screen (dropped when nothing is); covered also when an ancestor iframe is covered at their centre.
 * Order: the top modal's elements, elements of frames inside the top modal, the rest of the top document, other
 * frames; at most [max]. Without frames the page's own ids are kept; with frames they are renumbered m1.. in that order.
 */
internal fun placeElements(top: List<RawLookElement>, frames: List<Pair<FrameView, List<RawLookElement>>>, max: Int = LOOK_MAX_ELEMENTS): List<PlacedElement> {
    class C(val raw: RawLookElement, val view: FrameView?, val rect: LookRect, val covered: Boolean, val group: Int)
    val cands = ArrayList<C>()
    for (e in top) cands += C(e, null, LookRect(e.x, e.y, e.w, e.h), e.covered, if (e.mod) 0 else 2)
    for ((v, els) in frames) {
        val clip = v.clip ?: continue
        for (e in els) {
            val b = clip.intersect(Box(e.x + v.offX, e.y + v.offY, e.x + e.w + v.offX, e.y + e.h + v.offY)) ?: continue
            val covered = e.covered || v.covers.any { it.coveredAt((b.l + b.r) / 2, (b.t + b.b) / 2) }
            val l = kotlin.math.round(b.l).toInt()
            val t = kotlin.math.round(b.t).toInt()
            val rect = LookRect(l, t, kotlin.math.round(b.r).toInt() - l, kotlin.math.round(b.b).toInt() - t)
            cands += C(e, v, rect, covered, if (v.inTopModal) 1 else 3)
        }
    }
    val renumber = frames.isNotEmpty()
    return cands.sortedBy { it.group }.take(max).mapIndexed { n, c ->
        val id = if (renumber) "m${n + 1}" else c.raw.id
        PlacedElement(
            c.raw.toElement().copy(id = id, rect = c.rect, covered = c.covered, frame = c.view?.path),
            MarkRef(c.raw.xp, normalizeLabel(c.raw.label).orEmpty(), c.raw.tag, c.raw.id, c.view),
        )
    }
}
