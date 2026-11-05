package ai.rever.boss.plugin.dynamic.rpaengine

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Fake stand-ins shaped like JxBrowser's callbacks: an interface with on(params, action), a params interface, a final action class.
interface FakeParams { fun message(): String; fun okActionText(): String; fun cancelActionText(): String; fun text(): String; fun url(): String }
class FakeAction { val calls = mutableListOf<String>(); fun ok() { calls += "ok" }; fun ok(text: String) { calls += "ok:$text" }; fun cancel() { calls += "cancel" }; fun stay() { calls += "stay" }; fun leave() { calls += "leave" } }
interface FakeAlertCb { fun on(params: FakeParams, action: FakeAction) }
interface FakeConfirmCb { fun on(params: FakeParams, action: FakeAction) }
interface FakePromptCb { fun on(params: FakeParams, action: FakeAction) }

class DialogsTest {

    private val pkg = "ai.rever.boss.plugin.dynamic.rpaengine"
    private val specs = listOf(
        DialogCallbackSpec("$pkg.FakeAlertCb", DialogTypes.ALERT, okText = "okActionText"),
        DialogCallbackSpec("$pkg.FakeConfirmCb", DialogTypes.CONFIRM, okText = "okActionText", cancelText = "cancelActionText"),
        DialogCallbackSpec("$pkg.FakePromptCb", DialogTypes.PROMPT, defaultValue = "text", okText = "okActionText", cancelText = "cancelActionText"),
    )

    private class Host(initial: Map<Class<*>, Any> = emptyMap()) : CallbackHost {
        val map = HashMap(initial)
        val ops = mutableListOf<String>()
        override fun get(type: Class<*>): Any? = map[type]
        override fun set(type: Class<*>, callback: Any) { ops += "set ${type.simpleName}"; map[type] = callback }
        override fun remove(type: Class<*>) { ops += "remove ${type.simpleName}"; map.remove(type) }
    }

    private fun params(msg: String, text: String = "dflt") = object : FakeParams {
        override fun message() = msg; override fun okActionText() = "Yes"; override fun cancelActionText() = "No"
        override fun text() = text; override fun url() = "https://p.test/"
    }

    private val loader = DialogsTest::class.java.classLoader

    // ---- proxy dispatch ------------------------------------------------------------------------

    @Test
    fun `a dispatched dialog reaches the sink with its fields, and answering calls the action once`() {
        val host = Host()
        val seen = mutableListOf<Pair<DialogInfo, DialogResponder>>()
        val cap = ProxyDialogCapture.install(host, loader, specs) { i, r -> seen += i to r }
        assertNotNull(cap)
        val prompt = host.map[FakePromptCb::class.java] as FakePromptCb
        val action = FakeAction()
        prompt.on(params("Your name?", "Bob"), action)
        val (info, responder) = seen.single()
        assertEquals(DialogInfo("prompt", "Your name?", "Bob", "Yes", "No", "https://p.test/"), info)
        assertTrue(action.calls.isEmpty()) // held, not answered
        responder.answer(true, "Alice"); responder.answer(false, null)
        assertEquals(listOf("ok:Alice"), action.calls)
        val confirm = FakeAction(); (host.map[FakeConfirmCb::class.java] as FakeConfirmCb).on(params("sure?"), confirm)
        seen.last().second.answer(false, null)
        assertEquals(listOf("cancel"), confirm.calls)
        val alert = FakeAction(); (host.map[FakeAlertCb::class.java] as FakeAlertCb).on(params("hi"), alert)
        seen.last().second.answer(false, null) // an alert has only ok
        assertEquals(listOf("ok"), alert.calls)
    }

    @Test
    fun `a sink that throws still answers the dialog safely`() {
        val host = Host()
        ProxyDialogCapture.install(host, loader, specs) { _, _ -> error("boom") }
        val a = FakeAction(); (host.map[FakeConfirmCb::class.java] as FakeConfirmCb).on(params("x"), a)
        assertEquals(listOf("cancel"), a.calls)
    }

    // ---- restore -------------------------------------------------------------------------------

    @Test
    fun `restore puts back the host's callbacks, or removes ours when it had none`() {
        val hostAlert = object : FakeAlertCb { override fun on(params: FakeParams, action: FakeAction) {} }
        val host = Host(mapOf(FakeAlertCb::class.java to hostAlert))
        val cap = ProxyDialogCapture.install(host, loader, specs) { _, _ -> }!!
        assertFalse(host.map[FakeAlertCb::class.java] === hostAlert)
        cap.restore(); cap.restore() // idempotent
        assertSame(hostAlert, host.map[FakeAlertCb::class.java])
        assertNull(host.map[FakeConfirmCb::class.java]); assertNull(host.map[FakePromptCb::class.java])
        assertEquals(2, host.ops.count { it.startsWith("remove") })
    }

    @Test
    fun `restore leaves a callback someone else installed since`() {
        val host = Host()
        val cap = ProxyDialogCapture.install(host, loader, specs) { _, _ -> }!!
        val theirs = object : FakeConfirmCb { override fun on(params: FakeParams, action: FakeAction) {} }
        host.map[FakeConfirmCb::class.java] = theirs
        cap.restore()
        assertSame(theirs, host.map[FakeConfirmCb::class.java])
        assertNull(host.map[FakeAlertCb::class.java])
    }

    @Test
    fun `a partial install is rolled back`() {
        val host = object : CallbackHost {
            val inner = Host()
            override fun get(type: Class<*>) = inner.get(type)
            override fun set(type: Class<*>, callback: Any) { if (type == FakePromptCb::class.java) error("no") else inner.set(type, callback) }
            override fun remove(type: Class<*>) = inner.remove(type)
        }
        assertNull(ProxyDialogCapture.install(host, loader, specs) { _, _ -> })
        assertTrue(host.inner.map.isEmpty())
    }

    // ---- pending / answer / timeout --------------------------------------------------------------

    private class Resp { val calls = mutableListOf<String>(); val r = DialogResponder { a, t -> calls += "$a:$t" } }
    private fun info(type: String, msg: String = "m") = DialogInfo(type, msg, defaultValue = if (type == "prompt") "d" else null)
    private val idle = Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }

    @Test
    fun `one dialog is held, a second is answered with its safe default and recorded`() {
        val p = PendingDialogs(clock = { 0 }, timer = idle)
        val a = Resp(); val b = Resp(); val c = Resp()
        p.offer(info("confirm", "first"), a.r)
        p.offer(info("confirm", "second"), b.r)
        p.offer(info("alert", "third"), c.r)
        assertEquals("first", p.current()!!.message)
        assertTrue(a.calls.isEmpty())
        assertEquals(listOf("false:null"), b.calls)
        assertEquals(listOf("true:null"), c.calls)
        val auto = p.drainAuto()
        assertEquals(listOf("cancel", "ok"), auto.map { it.action }); assertTrue(auto.all { it.reason == "second_dialog" })
        assertTrue(p.drainAuto().isEmpty())
    }

    @Test
    fun `answer ok cancel and ok-text, and none pending is false`() {
        val p = PendingDialogs(clock = { 0 }, timer = idle)
        assertFalse(p.answer(DialogAnswer(true, null)))
        val r = Resp(); p.offer(info("prompt"), r.r)
        assertTrue(p.answer(DialogAnswer.parse("ok:hello world")!!))
        assertEquals(listOf("true:hello world"), r.calls); assertNull(p.current())
        val d = Resp(); p.offer(info("prompt"), d.r); p.answer(DialogAnswer.parse("OK")!!)
        assertEquals(listOf("true:d"), d.calls) // the prompt keeps its default value
        val e = Resp(); p.offer(info("confirm"), e.r); p.answer(DialogAnswer.parse("cancel")!!)
        assertEquals(listOf("false:null"), e.calls)
        assertEquals(DialogAnswer(true, ""), DialogAnswer.parse("ok:"))
        assertNull(DialogAnswer.parse("maybe")); assertNull(DialogAnswer.parse(null))
    }

    @Test
    fun `a dialog older than the timeout is auto-cancelled lazily`() {
        var now = 0L
        val p = PendingDialogs(timeoutMs = 120_000, clock = { now }, timer = idle)
        val r = Resp(); p.offer(info("confirm"), r.r)
        now = 119_999; assertNotNull(p.current())
        now = 120_000; assertNull(p.current())
        assertEquals(listOf("false:null"), r.calls)
        assertEquals("timeout", p.drainAuto().single().reason)
        // an expired hold does not block the next dialog
        val n = Resp(); p.offer(info("alert"), n.r); assertEquals("alert", p.current()!!.type)
    }

    @Test
    fun `the timer cancels a pending dialog on its own, an alert with ok`() {
        val p = PendingDialogs(timeoutMs = 50, timer = idle)
        val r = Resp(); p.offer(info("alert"), r.r)
        val end = System.currentTimeMillis() + 3000
        while (r.calls.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(listOf("true:null"), r.calls)
        assertEquals("timeout", p.drainAuto().single().reason)
    }

    @Test
    fun `an answered dialog is not auto-cancelled later`() {
        val p = PendingDialogs(timeoutMs = 60, timer = idle)
        val r = Resp(); p.offer(info("confirm"), r.r); p.answer(DialogAnswer(true, null))
        Thread.sleep(200)
        assertEquals(listOf("true:null"), r.calls); assertTrue(p.drainAuto().isEmpty())
    }

    @Test
    fun `close answers a held dialog with its safe default`() {
        val p = PendingDialogs(clock = { 0 }, timer = idle)
        val r = Resp(); p.offer(info("prompt"), r.r); p.close()
        assertEquals(listOf("false:null"), r.calls); assertNull(p.current())
        assertEquals("capture_end", p.drainAuto().single().reason)
    }

    @Test
    fun `concurrent dialogs from many threads leave exactly one held and answer the rest once`() {
        val p = PendingDialogs(timeoutMs = 60_000, timer = idle)
        val resps = List(50) { Resp() }
        val threads = resps.map { r -> Thread { p.offer(info("confirm"), r.r) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(49, resps.count { it.calls.size == 1 })
        assertEquals(1, resps.count { it.calls.isEmpty() })
        assertEquals(20, p.drainAuto().size) // the record keeps the newest 20
    }
}
