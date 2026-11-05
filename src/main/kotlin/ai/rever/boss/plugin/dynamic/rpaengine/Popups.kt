package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.BrowserIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Popup windows (window.open with a size: OAuth and sign-in) are separate Swing windows the host opens through its
 * OpenPopupCallback, so they are not BOSS tabs. While a tab is in computer use, that callback is wrapped: the host's
 * own handler runs first (so BOSS still opens its window or tab exactly as before), and a popup Browser that is
 * still open afterwards is registered under the pseudo tab id `<tabId>#popup-<n>`.
 */

internal const val POPUP_ID_MARK = "#popup-"

/**
 * A popup with empty initial bounds is a target=_blank / window.open without size: the host closes it and reopens the
 * URL as a tab. It is registered only if it is still open this long after the host's handler.
 */
internal const val POPUP_CONFIRM_MS = 1_000L

private val popupTimer: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rpaengine-popup-confirm").apply { isDaemon = true } }

/** `<tabId>#popup-<n>` into (tabId, n); null for an ordinary tab id. */
internal fun splitPopupId(id: String): Pair<String, Int>? {
    val at = id.lastIndexOf(POPUP_ID_MARK)
    if (at <= 0) return null
    val n = id.substring(at + POPUP_ID_MARK.length).toIntOrNull()?.takeIf { it > 0 } ?: return null
    return id.substring(0, at) to n
}

/** One entry of `popups` in a step result; a closed popup appears once as `{id, open:false}`. */
@Serializable
internal data class PopupInfo(val id: String, val open: Boolean, val url: String? = null, val title: String? = null)

/** A popup browser window, however it is reached. */
internal interface PopupWindow {
    val surface: TabSurface
    val integration: BrowserIntegration
    fun url(): String?
    fun title(): String?
    fun isClosed(): Boolean
}

/** One tab's popups. Thread-safe: popups arrive on JxBrowser threads, results are built on the tool thread. */
internal class PopupRegistry(private val parentId: String) {
    private val lock = Any()
    private val open = LinkedHashMap<Int, PopupWindow>()
    private var next = 0
    private val closedReports = ArrayList<Int>()

    /** Registers [window]; returns its pseudo id. */
    fun add(window: PopupWindow): String = synchronized(lock) {
        val n = ++next
        open[n] = window
        "$parentId$POPUP_ID_MARK$n"
    }

    /** The open popup with number [n], pruning it if it has closed meanwhile. */
    fun get(n: Int): PopupWindow? = synchronized(lock) {
        val w = open[n] ?: return null
        if (safeClosed(w)) { open.remove(n); closedReports += n; return null }
        w
    }

    /** Open popups, plus each popup that closed since the last call (once). Empty list when there is nothing to say. */
    fun status(): List<PopupInfo> = synchronized(lock) {
        val gone = open.entries.filter { safeClosed(it.value) }.map { it.key }
        gone.forEach { open.remove(it); closedReports += it }
        val out = ArrayList<PopupInfo>()
        for ((n, w) in open) out += PopupInfo("$parentId$POPUP_ID_MARK$n", true, runCatching { w.url() }.getOrNull(), runCatching { w.title() }.getOrNull())
        for (n in closedReports) out += PopupInfo("$parentId$POPUP_ID_MARK$n", false)
        closedReports.clear()
        out
    }

    /** Ids of the popups still registered (for ending their captures). */
    fun ids(): List<String> = synchronized(lock) { open.keys.map { "$parentId$POPUP_ID_MARK$it" } }

    private fun safeClosed(w: PopupWindow) = runCatching { w.isClosed() }.getOrDefault(true)
}

/**
 * Wraps the host's callback of [callbackClass] (JxBrowser's OpenPopupCallback: `on(params)` returning a response).
 * The host's own handler is called first and its answer returned untouched; afterwards `params.popupBrowser()`, when
 * still open, goes to [onPopup]. Nothing is installed when the host has no handler (its default stays in force).
 */
internal class PopupProxyCapture private constructor(
    private val host: CallbackHost,
    private val type: Class<*>,
    private val previous: Any,
    private val ours: Any,
) : DialogCapture {
    private val done = AtomicBoolean(false)

    override fun restore() {
        if (!done.compareAndSet(false, true)) return
        runCatching { if (host.get(type) === ours) host.set(type, previous) }
    }

    companion object {
        fun install(host: CallbackHost, loader: ClassLoader, callbackClass: String, confirmMs: Long = POPUP_CONFIRM_MS, onPopup: (Any) -> Unit): PopupProxyCapture? = try {
            val type = Class.forName(callbackClass, true, loader)
            val previous = host.get(type)
            if (previous == null) null else {
                val proxy = Proxy.newProxyInstance(loader, arrayOf(type), Handler(previous, confirmMs, onPopup))
                host.set(type, proxy)
                PopupProxyCapture(host, type, previous, proxy)
            }
        } catch (e: Throwable) {
            null
        }
    }

    private class Handler(private val host: Any, private val confirmMs: Long, private val onPopup: (Any) -> Unit) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name != "on" || args == null || args.size != 1) {
                return when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "rpaengine-popup-capture"
                    else -> forward(method, args)
                }
            }
            val response = forward(method, args) // the host first, exactly as it would have run
            runCatching {
                val popup = call(args[0]!!, "popupBrowser")
                if (popup != null && call(popup, "isClosed") != true) {
                    if (realWindow(args[0]!!) != false) {
                        onPopup(popup)
                    } else {
                        // Empty bounds: usually reopened as a tab and closed by the host; only a survivor is a popup.
                        popupTimer.schedule({ runCatching { if (call(popup, "isClosed") != true) onPopup(popup) } }, confirmMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                }
            }
            return response
        }

        /** True for non-empty initialBounds (a real popup window), false for empty ones, null when the host does not say. */
        private fun realWindow(params: Any): Boolean? = runCatching {
            val b = call(params, "initialBounds") ?: return false
            ((call(b, "width") as? Number)?.toInt() ?: 0) > 0 && ((call(b, "height") as? Number)?.toInt() ?: 0) > 0
        }.getOrNull()

        private fun forward(method: Method, args: Array<out Any?>?): Any? = try {
            method.isAccessible = true
            method.invoke(host, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

/** A [BrowserIntegration] over a JxBrowser popup `Browser`: JS through `mainFrame().executeJavaScript`, by reflection. */
internal class JxPopupIntegration(private val browser: Any, val surface: TabSurface) : BrowserIntegration {
    private fun cls(name: String) = Class.forName(name, true, browser.javaClass.classLoader)

    override suspend fun executeJavaScript(script: String): Any? = withContext(Dispatchers.IO) {
        val frame = (cls(JxBrowserSurface.BROWSER).getMethod("mainFrame").invoke(browser) as? java.util.Optional<*>)?.orElse(null)
            ?: throw IllegalStateException("The popup has no main frame")
        val m = cls("com.teamdev.jxbrowser.frame.Frame").methods.first { it.name == "executeJavaScript" && it.parameterCount == 1 }
        try { m.invoke(frame, script) } catch (e: InvocationTargetException) { throw e.targetException }
    }

    override fun isBrowserAvailable(): Boolean = runCatching { !isClosed() }.getOrDefault(false)
    override suspend fun getCurrentUrl(): String? = url()

    fun url(): String? = runCatching { cls(JxBrowserSurface.BROWSER).getMethod("url").invoke(browser) as? String }.getOrNull()
    fun title(): String? = runCatching { cls(JxBrowserSurface.BROWSER).getMethod("title").invoke(browser) as? String }.getOrNull()
    fun isClosed(): Boolean = runCatching { cls(JxBrowserSurface.BROWSER).getMethod("isClosed").invoke(browser) == true }.getOrDefault(true)
}

internal class JxPopupWindow(browser: Any) : PopupWindow {
    override val surface: TabSurface = JxBrowserSurface.wrap(browser)
    private val jx = JxPopupIntegration(browser, surface)
    override val integration: BrowserIntegration get() = jx
    override fun url() = jx.url()
    override fun title() = jx.title()
    override fun isClosed() = jx.isClosed()
}

private fun call(target: Any, name: String): Any? {
    val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: throw NoSuchMethodException(name)
    m.isAccessible = true
    return m.invoke(target)
}

internal const val JX_OPEN_POPUP_CALLBACK = "com.teamdev.jxbrowser.browser.callback.OpenPopupCallback"
