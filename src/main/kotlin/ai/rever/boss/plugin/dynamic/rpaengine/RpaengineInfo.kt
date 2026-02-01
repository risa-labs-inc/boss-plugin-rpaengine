package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory

object RpaengineInfo : PanelInfo {
    override val id = PanelId("rpa_engine", 20)
    override val displayName = "RPA Engine"
    override val icon = Icons.Outlined.Memory
    override val defaultSlotPosition = right.top.top
}
