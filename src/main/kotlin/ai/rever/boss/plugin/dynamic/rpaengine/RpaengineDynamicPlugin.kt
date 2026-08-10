package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

/**
 * RPA Engine dynamic plugin - Loaded from external JAR.
 *
 * Execute recorded RPA workflows.
 * Works with or without BrowserService - simulation mode always available.
 */
class RpaengineDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.rpaengine"
    override val displayName: String = "RPA Engine (Dynamic)"
    // Read from the manifest, which processResources syncs from build.gradle.kts - the single
    // source of truth. Hardcoded, this said 1.0.5 while the build said 1.2.0, and it drifted
    // further with every bump because the resource filter does not touch Kotlin sources.
    override val version: String = manifestVersion()
    override val description: String = "Execute recorded RPA workflows"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-rpaengine"

    // Most recently created panel component, so MCP tools can drive the engine.
    @Volatile
    private var lastComponent: RpaengineComponent? = null

    override fun register(context: PluginContext) {
        // Get services from context
        val browserService = context.browserService
        val activeTabsProvider = context.activeTabsProvider

        context.panelRegistry.registerPanel(RpaengineInfo) { ctx, panelInfo ->
            RpaengineComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                browserService = browserService,
                activeTabsProvider = activeTabsProvider
            ).also { comp ->
                lastComponent = comp
                // Clear on panel close: the component's scope is cancelled on
                // destroy, so MCP tools driving it would silently no-op while
                // reporting success. Better to answer "open the panel first".
                ctx.lifecycle.doOnDestroy { if (lastComponent === comp) lastComponent = null }
            }
        }

        // Contribute rpa_status/run/stop MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(RpaengineMcpToolProvider(pluginId) { lastComponent })
    }

    override fun dispose() {
        lastComponent = null
    }

    /**
     * The version from *this* plugin's manifest.
     *
     * Every BOSS plugin ships `/META-INF/boss-plugin/plugin.json` at the same resource path, so a
     * single `getResourceAsStream` returns whichever jar comes first if the host ever loads plugins
     * through a shared or parent-first classloader - and this plugin would report someone else's
     * version. Every candidate is checked and only the one naming this plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}
