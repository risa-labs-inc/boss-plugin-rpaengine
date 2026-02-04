package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * RPA Engine dynamic plugin - Loaded from external JAR.
 *
 * Execute recorded RPA workflows.
 * Works with or without BrowserService - simulation mode always available.
 */
class RpaengineDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.rpaengine"
    override val displayName: String = "RPA Engine (Dynamic)"
    override val version: String = "1.0.5"
    override val description: String = "Execute recorded RPA workflows"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-rpaengine"

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
            )
        }
    }
}
