package ai.rever.boss.plugin.dynamic.rpaengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FakePopupBrowser(var closed: Boolean = false) { fun isClosed() = closed }
interface FakePopupParams { fun popupBrowser(): FakePopupBrowser? }
interface FakeOpenPopupCb { fun on(params: FakePopupParams): String }

class PopupsTest {
    private val pkg = "ai.rever.boss.plugin.dynamic.rpaengine"
    private val loader = PopupsTest::class.java.classLoader

    private class Host(initial: Map<Class<*>, Any> = emptyMap()) : CallbackHost {
        val map = HashMap(initial)
        override fun get(type: Class<*>): Any? = map[type]
        override fun set(type: Class<*>, callback: Any) { map[type] = callback }
        override fun remove(type: Class<*>) { map.remove(type) }
    }

    private fun params(b: FakePopupBrowser?) = object : FakePopupParams { override fun popupBrowser() = b }

    @Test
    fun `the host's handler runs first and its answer is returned, then an open popup is reported`() {
        val order = mutableListOf<String>()
        val hostCb = object : FakeOpenPopupCb { override fun on(params: FakePopupParams): String { order += "host"; return "PROCEED" } }
        val host = Host(mapOf(FakeOpenPopupCb::class.java to hostCb))
        val cap = PopupProxyCapture.install(host, loader, "$pkg.FakeOpenPopupCb") { order += "ours" }!!
        val ours = host.map[FakeOpenPopupCb::class.java] as FakeOpenPopupCb
        assertEquals("PROCEED", ours.on(params(FakePopupBrowser())))
        assertEquals(listOf("host", "ours"), order)
        cap.restore(); cap.restore()
        assertSame(hostCb, host.map[FakeOpenPopupCb::class.java])
    }

    @Test
    fun `a popup the host closed is not reported, a throwing observer never breaks the host, no host handler means no wrap`() {
        val seen = mutableListOf<Any>()
        val closing = object : FakeOpenPopupCb { override fun on(params: FakePopupParams): String { params.popupBrowser()!!.closed = true; return "R" } }
        val host = Host(mapOf(FakeOpenPopupCb::class.java to closing))
        PopupProxyCapture.install(host, loader, "$pkg.FakeOpenPopupCb") { seen += it }
        assertEquals("R", (host.map[FakeOpenPopupCb::class.java] as FakeOpenPopupCb).on(params(FakePopupBrowser())))
        assertTrue(seen.isEmpty())
        val h2 = Host(mapOf(FakeOpenPopupCb::class.java to object : FakeOpenPopupCb { override fun on(params: FakePopupParams) = "OK" }))
        PopupProxyCapture.install(h2, loader, "$pkg.FakeOpenPopupCb") { error("boom") }
        assertEquals("OK", (h2.map[FakeOpenPopupCb::class.java] as FakeOpenPopupCb).on(params(FakePopupBrowser())))
        assertNull(PopupProxyCapture.install(Host(), loader, "$pkg.FakeOpenPopupCb") { })
        assertEquals("OK", (h2.map[FakeOpenPopupCb::class.java] as FakeOpenPopupCb).on(params(null)))
    }

    @Test
    fun `restore leaves a handler someone else installed since`() {
        val hostCb = object : FakeOpenPopupCb { override fun on(params: FakePopupParams) = "h" }
        val host = Host(mapOf(FakeOpenPopupCb::class.java to hostCb))
        val cap = PopupProxyCapture.install(host, loader, "$pkg.FakeOpenPopupCb") { }!!
        val theirs = object : FakeOpenPopupCb { override fun on(params: FakePopupParams) = "t" }
        host.map[FakeOpenPopupCb::class.java] = theirs
        cap.restore()
        assertSame(theirs, host.map[FakeOpenPopupCb::class.java])
    }

    private class Win(var closed: Boolean = false, var url: String? = "https://accounts.test/", var title: String? = "Sign in") : PopupWindow {
        override val surface: TabSurface = object : TabSurface {
            override suspend fun bitmap() = null
            override suspend fun dispatch(event: TabEvent) = true
        }
        override val integration = object : ai.rever.boss.plugin.api.BrowserIntegration {
            override suspend fun executeJavaScript(script: String): Any? = true
            override fun isBrowserAvailable() = true
            override suspend fun getCurrentUrl(): String? = url
        }
        override fun url() = url
        override fun title() = title
        override fun isClosed() = closed
    }

    @Test
    fun `registry numbers popups, lists open ones, and reports a closed one exactly once`() {
        val r = PopupRegistry("tab1")
        assertTrue(r.status().isEmpty())
        val a = Win(); val b = Win(url = "https://b.test/", title = null)
        assertEquals("tab1#popup-1", r.add(a)); assertEquals("tab1#popup-2", r.add(b))
        assertEquals(listOf(PopupInfo("tab1#popup-1", true, "https://accounts.test/", "Sign in"), PopupInfo("tab1#popup-2", true, "https://b.test/", null)), r.status())
        a.closed = true
        assertEquals(listOf(PopupInfo("tab1#popup-2", true, "https://b.test/", null), PopupInfo("tab1#popup-1", false)), r.status())
        assertEquals(listOf("tab1#popup-2"), r.status().map { it.id })
        assertNull(r.get(1)); assertSame(b, r.get(2))
        b.closed = true
        assertNull(r.get(2)) // a lookup also prunes
        assertEquals(listOf(PopupInfo("tab1#popup-2", false)), r.status())
    }

    @Test
    fun `popup ids split on the last marker`() {
        assertEquals("abc" to 3, splitPopupId("abc#popup-3"))
        assertEquals("a#popup-1" to 2, splitPopupId("a#popup-1#popup-2"))
        listOf("abc", "abc#popup-", "abc#popup-x", "abc#popup-0", "#popup-1").forEach { assertNull(splitPopupId(it), it) }
    }

    class Bounds(private val w: Int, private val h: Int) { fun width() = w; fun height() = h }
    class BoundedParams(private val b: FakePopupBrowser?, private val bounds: Bounds) : FakePopupParams {
        override fun popupBrowser() = b
        fun initialBounds() = bounds
    }

    @Test
    fun `a sized popup registers at once, an empty-bounds one only if it is still open after the confirm delay`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Any>()
        val hostCb = object : FakeOpenPopupCb { override fun on(params: FakePopupParams) = "OK" }
        val host = Host(mapOf(FakeOpenPopupCb::class.java to hostCb))
        PopupProxyCapture.install(host, loader, "$pkg.FakeOpenPopupCb", confirmMs = 150) { seen += it }
        val ours = host.map[FakeOpenPopupCb::class.java] as FakeOpenPopupCb
        val real = FakePopupBrowser()
        ours.on(BoundedParams(real, Bounds(500, 600)))
        assertEquals(listOf<Any>(real), seen.toList(), "a real window: immediately")
        // target=_blank: empty bounds, the host reopens it as a tab and closes this one
        val blank = FakePopupBrowser()
        ours.on(BoundedParams(blank, Bounds(0, 0)))
        assertEquals(1, seen.size, "not yet")
        blank.closed = true
        Thread.sleep(400)
        assertEquals(listOf<Any>(real), seen.toList(), "closed by the host: never registered")
        // empty bounds but it stays open: registered after the delay
        val stays = FakePopupBrowser()
        ours.on(BoundedParams(stays, Bounds(0, 0)))
        Thread.sleep(400)
        assertEquals(listOf<Any>(real, stays), seen.toList())
    }
}

