package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * MCP tools contributed by the RPA Engine plugin: read execution status and
 * start/stop the currently-loaded workflow.
 *
 * The engine's actions live on the per-panel [RpaengineComponent], so these
 * tools operate on the most recently opened RPA Engine panel (via [component]);
 * if none is open they report that. Registered in
 * [RpaengineDynamicPlugin.register]; removed automatically on disable/unload.
 */
internal class RpaengineMcpToolProvider(
    override val providerId: String,
    private val component: () -> RpaengineComponent?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "rpa_status",
            description = "Report RPA Engine execution status (state, current action, result count).",
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                McpToolResult("status=${c.executionStatus.value.name} action=${c.currentActionIndex.value} results=${c.executionResults.value.size}")
            },
        ),
        McpToolDefinition(
            name = "rpa_run",
            description = "Start (or resume) execution of the currently-loaded RPA workflow.",
            readOnly = false,
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                c.startExecution()
                McpToolResult("Started RPA execution (status now ${c.executionStatus.value.name}).")
            },
        ),
        McpToolDefinition(
            name = "rpa_stop",
            description = "Stop the currently-running RPA workflow.",
            readOnly = false,
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                c.stopExecution()
                McpToolResult("Stopped RPA execution.")
            },
        ),
    )

    private fun notOpen(): McpToolResult =
        McpToolResult("Open the RPA Engine panel first (no active instance).", isError = true)
}
