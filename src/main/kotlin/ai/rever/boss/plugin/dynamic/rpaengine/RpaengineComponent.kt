package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * RPA Engine panel component (Dynamic Plugin)
 *
 * This is a stub implementation. Full functionality requires
 * host services not yet exposed through PluginContext.
 */
class RpaengineComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        RpaengineContent()
    }
}
