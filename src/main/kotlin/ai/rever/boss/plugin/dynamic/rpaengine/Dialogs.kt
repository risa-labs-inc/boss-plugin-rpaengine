package ai.rever.boss.plugin.dynamic.rpaengine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Browser dialogs (alert / confirm / prompt / beforeunload) for computer-use tabs. They reach us on JxBrowser
 * threads, are held (one per tab) until rpa_step answers them with `dialog_answer`, and are auto-cancelled after
 * 120 s so a page never hangs forever. Everything JxBrowser-specific sits behind [CallbackHost]; the rest is
 * plain data and a synchronized holder, so it is testable without the engine.
 */

internal const val DIALOG_TIMEOUT_MS = 120_000L
internal const val DIALOG_MESSAGE_MAX = 2_000
private const val AUTO_KEEP = 20

internal object DialogTypes {
    const val ALERT = "alert"
    const val CONFIRM = "confirm"
    const val PROMPT = "prompt"
    const val BEFOREUNLOAD = "beforeunload"
}

/** What a pending dialog looks like to the caller. Optional fields are omitted from the JSON when unset. */
@Serializable
internal data class DialogInfo(
    val type: String,
    val message: String,
    @SerialName("default_value") val defaultValue: String? = null,
    @SerialName("ok_text") val okText: String? = null,
    @SerialName("cancel_text") val cancelText: String? = null,
    val url: String? = null,
)

/** A dialog this plugin answered itself ([reason] = `second_dialog`, `timeout` or `capture_end`). */
@Serializable
internal data class AutoDialog(val type: String, val message: String, val action: String, val reason: String)

/** Answers a held dialog once; a second call is ignored. [accept] false = cancel (beforeunload: stay). */
internal fun interface DialogResponder {
    fun answer(accept: Boolean, text: String?)
}

internal fun interface DialogSink {
    fun onDialog(info: DialogInfo, responder: DialogResponder)
}

/** A live capture on one tab; [restore] puts the host's callbacks back exactly as they were. */
internal interface DialogCapture {
    fun restore()
}

/** The dialog answer that cannot do harm: ok for an alert (it has nothing else), cancel/stay for the rest. */
internal fun safeAccept(type: String): Boolean = type == DialogTypes.ALERT

private fun actionName(type: String, accept: Boolean): String =
    if (accept) "ok" else if (type == DialogTypes.ALERT) "ok" else "cancel"

/** `dialog_answer` value: "ok", "cancel" or "ok:<text>" (null = malformed). */
internal data class DialogAnswer(val accept: Boolean, val text: String?) {
    companion object {
        fun parse(value: String?): DialogAnswer? {
            val v = value ?: return null
            val t = v.trim()
            return when {
                t.equals("ok", ignoreCase = true) -> DialogAnswer(true, null)
                t.equals("cancel", ignoreCase = true) -> DialogAnswer(false, null)
                t.startsWith("ok:", ignoreCase = true) -> DialogAnswer(true, v.substring(v.indexOf(':') + 1))
                else -> null
            }
        }
    }
}

internal object DialogTimer {
    val shared: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rpaengine-dialog-timeout").apply { isDaemon = true } }
    }
}

/**
 * The one dialog a tab may hold. Thread-safe: dialogs arrive on JxBrowser threads, answers on the tool thread,
 * expiry on the timer thread. Responders are always called outside the lock.
 */
internal class PendingDialogs(
    private val timeoutMs: Long = DIALOG_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timer: ScheduledExecutorService = DialogTimer.shared,
) {
    private class Held(val id: Long, val info: DialogInfo, val responder: DialogResponder, val since: Long, var task: ScheduledFuture<*>?)

    private val lock = Any()
    private var held: Held? = null
    private var nextId = 0L
    private val auto = ArrayDeque<AutoDialog>()

    private fun record(info: DialogInfo, accept: Boolean, reason: String) {
        if (auto.size >= AUTO_KEEP) auto.removeFirst()
        auto.addLast(AutoDialog(info.type, info.message, actionName(info.type, accept), reason))
    }

    private fun safely(responder: DialogResponder, accept: Boolean, text: String? = null) {
        runCatching { responder.answer(accept, text) }
    }

    /** Holds [info], or - when one is already pending - answers this new one with its safe default. */
    fun offer(info: DialogInfo, responder: DialogResponder) {
        val rejected: Boolean
        current() // a stale hold is auto-answered first
        synchronized(lock) {
            rejected = held != null
            if (rejected) {
                record(info, safeAccept(info.type), "second_dialog")
            } else {
                val id = ++nextId
                val h = Held(id, info, responder, clock(), null)
                held = h
                h.task = runCatching { timer.schedule({ expire(id) }, timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        }
        if (rejected) safely(responder, safeAccept(info.type))
    }

    /** The pending dialog, if any (a stale one is auto-cancelled first). */
    fun current(): DialogInfo? {
        val expired = synchronized(lock) { expireLocked() }
        expired?.let { safely(it.responder, safeAccept(it.info.type)) }
        return synchronized(lock) { held?.info }
    }

    /** Answers the pending dialog; false when none is pending. A prompt `ok` without text keeps its default value. */
    fun answer(answer: DialogAnswer): Boolean {
        current()
        val h = synchronized(lock) { take() } ?: return false
        val text = if (answer.accept && h.info.type == DialogTypes.PROMPT) answer.text ?: h.info.defaultValue ?: "" else null
        safely(h.responder, answer.accept, text)
        return true
    }

    /** Auto-answered dialogs since the last call. */
    fun drainAuto(): List<AutoDialog> = synchronized(lock) { auto.toList().also { auto.clear() } }

    /** Capture is ending: a held dialog gets its safe answer so the page is never left waiting. */
    fun close() {
        val h = synchronized(lock) { take()?.also { record(it.info, safeAccept(it.info.type), "capture_end") } } ?: return
        safely(h.responder, safeAccept(h.info.type))
    }

    private fun expire(id: Long) {
        val h = synchronized(lock) {
            val cur = held
            if (cur == null || cur.id != id) null else take()?.also { record(it.info, safeAccept(it.info.type), "timeout") }
        } ?: return
        safely(h.responder, safeAccept(h.info.type))
    }

    /** Detaches a stale hold and records it; the caller answers it. Call with the lock held. */
    private fun expireLocked(): Held? {
        val cur = held ?: return null
        if (clock() - cur.since < timeoutMs) return null
        return take()?.also { record(it.info, safeAccept(it.info.type), "timeout") }
    }

    private fun take(): Held? = held?.also { it.task?.cancel(false); held = null }
}

// ---------------------------------------------------------------------------------------------
// Callback proxies
// ---------------------------------------------------------------------------------------------

/** The host browser's callback registry (JxBrowser's `Advisable`), reduced to what restore needs. */
internal interface CallbackHost {
    fun get(type: Class<*>): Any?
    fun set(type: Class<*>, callback: Any)
    fun remove(type: Class<*>)
}

/** One dialog callback interface and how its params map to [DialogInfo]. Getter names are looked up by reflection. */
internal data class DialogCallbackSpec(
    val callbackClass: String,
    val type: String,
    val defaultValue: String? = null,
    val okText: String? = null,
    val cancelText: String? = null,
    val url: String? = "url",
)

internal val JX_DIALOG_SPECS = listOf(
    DialogCallbackSpec("com.teamdev.jxbrowser.browser.callback.AlertCallback", DialogTypes.ALERT, okText = "okActionText"),
    DialogCallbackSpec("com.teamdev.jxbrowser.browser.callback.ConfirmCallback", DialogTypes.CONFIRM, okText = "okActionText", cancelText = "cancelActionText"),
    DialogCallbackSpec("com.teamdev.jxbrowser.browser.callback.PromptCallback", DialogTypes.PROMPT, defaultValue = "text", okText = "okActionText", cancelText = "cancelActionText"),
    // No url in its params; "leave" is the ok side and "stay" the cancel side.
    DialogCallbackSpec("com.teamdev.jxbrowser.browser.callback.BeforeUnloadCallback", DialogTypes.BEFOREUNLOAD, okText = "leaveActionText", cancelText = "stayActionText", url = null),
)

/**
 * Installs one [Proxy] per spec on [host], remembering what was there. [restore] puts each callback back (or
 * removes ours when the host had none), but never overwrites a callback someone else installed since.
 */
internal class ProxyDialogCapture private constructor(
    private val host: CallbackHost,
    private val installed: List<Triple<Class<*>, Any?, Any>>, // type, previous, ours
) : DialogCapture {
    private val done = AtomicBoolean(false)

    override fun restore() {
        if (!done.compareAndSet(false, true)) return
        for ((type, previous, ours) in installed) {
            runCatching {
                if (host.get(type) !== ours) return@runCatching // replaced by someone else: not ours to undo
                if (previous != null) host.set(type, previous) else host.remove(type)
            }
        }
    }

    companion object {
        /** Null when no callback could be installed; a partial install is rolled back. */
        fun install(host: CallbackHost, loader: ClassLoader, specs: List<DialogCallbackSpec>, sink: DialogSink): ProxyDialogCapture? {
            val done = ArrayList<Triple<Class<*>, Any?, Any>>()
            try {
                for (spec in specs) {
                    val type = Class.forName(spec.callbackClass, true, loader)
                    val previous = host.get(type)
                    val proxy = Proxy.newProxyInstance(loader, arrayOf(type), Handler(spec, sink))
                    host.set(type, proxy)
                    done += Triple(type, previous, proxy)
                }
            } catch (e: Throwable) {
                ProxyDialogCapture(host, done).restore()
                return null
            }
            return ProxyDialogCapture(host, done)
        }
    }

    private class Handler(private val spec: DialogCallbackSpec, private val sink: DialogSink) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name != "on" || args == null || args.size != 2) {
                return when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "rpaengine-dialog-capture(${spec.type})"
                    else -> null
                }
            }
            val params = args[0]
            val action = args[1] ?: return null
            val responder = Responder(spec.type, action)
            try {
                val info = DialogInfo(
                    type = spec.type,
                    message = (read(params, "message") ?: "").take(DIALOG_MESSAGE_MAX),
                    defaultValue = spec.defaultValue?.let { read(params, it) },
                    okText = spec.okText?.let { read(params, it) },
                    cancelText = spec.cancelText?.let { read(params, it) },
                    url = spec.url?.let { read(params, it) },
                )
                sink.onDialog(info, responder)
            } catch (e: Throwable) {
                // Never leave the page waiting on a dialog we could not even describe.
                responder.answer(safeAccept(spec.type), null)
            }
            return null
        }
    }

    /** Calls the Action once: ok/ok(text) or cancel (beforeunload: leave/stay). */
    private class Responder(private val type: String, private val action: Any) : DialogResponder {
        private val used = AtomicBoolean(false)

        override fun answer(accept: Boolean, text: String?) {
            if (!used.compareAndSet(false, true)) return
            val name = when (type) {
                DialogTypes.BEFOREUNLOAD -> if (accept) "leave" else "stay"
                DialogTypes.ALERT -> "ok"
                else -> if (accept) "ok" else "cancel"
            }
            if (name == "ok" && type == DialogTypes.PROMPT) call(action, "ok", text ?: "") else call(action, name)
        }
    }
}

/** A public no-arg String getter by name, or null. */
private fun read(target: Any?, name: String): String? {
    val t = target ?: return null
    return runCatching { call(t, name) as? String }.getOrNull()
}

/** Reflective call by name and arity; impl classes may be non-public, so access is forced. */
private fun call(target: Any, name: String, vararg args: Any?): Any? {
    val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
        ?: throw NoSuchMethodException(name)
    method.isAccessible = true
    return method.invoke(target, *args)
}

/** [CallbackHost] over a JxBrowser `Browser` (an `Advisable`), by reflection. */
internal class JxCallbackHost(private val browser: Any) : CallbackHost {
    private fun m(name: String, vararg types: Class<*>): Method {
        val c = Class.forName("com.teamdev.jxbrowser.browser.Browser", true, browser.javaClass.classLoader)
        return c.methods.first { it.name == name && it.parameterCount == types.size }
    }

    override fun get(type: Class<*>): Any? = (m("get", Class::class.java).invoke(browser, type) as? java.util.Optional<*>)?.orElse(null)
    override fun set(type: Class<*>, callback: Any) { m("set", Class::class.java, Any::class.java).invoke(browser, type, callback) }
    override fun remove(type: Class<*>) { m("remove", Class::class.java).invoke(browser, type) }
}
