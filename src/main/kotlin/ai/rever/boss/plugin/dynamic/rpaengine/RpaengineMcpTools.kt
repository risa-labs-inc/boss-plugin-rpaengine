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
            name = "rpa_results",
            description =
                "Per-action outcome of the last RPA run (index, name, ok/fail, error, duration) " +
                    "followed by the most recent log lines. Use after rpa_status reports ERROR.",
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                val results = c.executionResults.value
                val logs = c.executionLogs.value.takeLast(LOG_TAIL)
                if (results.isEmpty() && logs.isEmpty()) {
                    return@McpToolHandler McpToolResult("No run has produced results yet.")
                }
                val body = buildString {
                    results.forEach { r ->
                        append("#${r.actionIndex} ${r.actionName} ")
                        append(if (r.success) "ok" else "FAILED")
                        r.error?.let { append(" - ").append(it) }
                        append(" (${r.duration}ms)\n")
                    }
                    if (logs.isNotEmpty()) {
                        append("--- last ${logs.size} log line(s) ---\n")
                        logs.forEach { append("[${it.level.name}] ${it.message}\n") }
                    }
                }
                McpToolResult(body.trimEnd())
            },
        ),
        McpToolDefinition(
            name = "rpa_load",
            description =
                "Load a saved RPA configuration by name (substring matches) so rpa_run can " +
                    "execute it. Lists the available names when there is no match. Only " +
                    "configurations in BOSS-managed directories can be loaded this way; one " +
                    "downloaded into ~/Downloads has to be picked in the panel.",
            inputSchema = NAME_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val c = component() ?: return@McpToolHandler notOpen()
                val name = args.string("name")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@McpToolHandler McpToolResult("Missing required argument: name", isError = true)
                when (val outcome = c.selectManagedConfigurationByName(name)) {
                    is RpaengineComponent.LoadOutcome.Loaded ->
                        McpToolResult("Loaded '${outcome.name}'. Call rpa_run to execute it.")

                    is RpaengineComponent.LoadOutcome.Failed ->
                        McpToolResult("Found '${outcome.name}' but it did not parse.", isError = true)

                    is RpaengineComponent.LoadOutcome.Busy ->
                        McpToolResult(
                            "A run is in progress (status ${outcome.status}) - call rpa_stop first.",
                            isError = true,
                        )

                    is RpaengineComponent.LoadOutcome.NotManaged ->
                        McpToolResult(
                            "'${outcome.name}' is in ~/Downloads, which this tool will not load - " +
                                "open it from the RPA Engine panel instead.",
                            isError = true,
                        )

                    is RpaengineComponent.LoadOutcome.NoMatch ->
                        McpToolResult(
                            "No configuration matched '$name'. Available: " +
                                outcome.available.joinToString(", ").ifEmpty { "none" },
                            isError = true,
                        )
                }
            },
        ),
        McpToolDefinition(
            name = "rpa_run",
            description = "Start (or resume) execution of the currently-loaded RPA workflow.",
            readOnly = false,
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                val status = c.executionStatus.value
                if (status == ExecutionStatus.EXECUTING || status == ExecutionStatus.LOADING) {
                    return@McpToolHandler McpToolResult(
                        "A run is already in progress (status $status) - call rpa_stop first.",
                        isError = true,
                    )
                }
                val loaded = c.loadedConfigurationName()
                    ?: return@McpToolHandler McpToolResult(
                        "No configuration is loaded - call rpa_load first.",
                        isError = true,
                    )
                c.startExecution()
                McpToolResult("Running '$loaded' (status now ${c.executionStatus.value.name}).")
            },
        ),
        McpToolDefinition(
            name = "rpa_stop",
            description = "Stop the currently-running RPA workflow.",
            readOnly = false,
            handler = McpToolHandler {
                val c = component() ?: return@McpToolHandler notOpen()
                if (c.stopExecution()) {
                    McpToolResult("Stopped RPA execution.")
                } else {
                    McpToolResult("Nothing was running (status ${c.executionStatus.value}).")
                }
            },
        ),
    )

    private fun notOpen(): McpToolResult =
        McpToolResult("Open the RPA Engine panel first (no active instance).", isError = true)

    private companion object {
        /** Enough log tail to explain a failure without dumping a whole session. */
        const val LOG_TAIL = 15

        const val NAME_SCHEMA =
            """{"type":"object","properties":{"name":{"type":"string","description":"Configuration name, or any part of it."}},"required":["name"]}"""
    }
}
