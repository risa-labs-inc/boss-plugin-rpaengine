package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.browser.BrowserService
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * RPA Engine panel component (Dynamic Plugin)
 *
 * Execute recorded RPA workflows.
 * Works with or without BrowserService - simulation mode always available.
 */
class RpaengineComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val browserService: BrowserService? = null,
    private val activeTabsProvider: ActiveTabsProvider? = null
) : PanelComponentWithUI, ComponentContext by ctx {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsManager = RpaEngineSettingsManager()
    private var executionJob: Job? = null
    private val actionRunner = ActionRunner { level, message -> addLog(level, message) }

    // Configuration management
    private val _selectedConfig = MutableStateFlow<RpaConfiguration?>(null)
    val selectedConfig: StateFlow<RpaConfiguration?> = _selectedConfig.asStateFlow()

    private val _availableConfigs = MutableStateFlow<List<ConfigFileInfo>>(emptyList())
    val availableConfigs: StateFlow<List<ConfigFileInfo>> = _availableConfigs.asStateFlow()

    // Execution state
    private val _executionStatus = MutableStateFlow(ExecutionStatus.IDLE)
    val executionStatus: StateFlow<ExecutionStatus> = _executionStatus.asStateFlow()

    private val _currentActionIndex = MutableStateFlow(-1)
    val currentActionIndex: StateFlow<Int> = _currentActionIndex.asStateFlow()

    private val _executionResults = MutableStateFlow<List<ActionExecutionResult>>(emptyList())
    val executionResults: StateFlow<List<ActionExecutionResult>> = _executionResults.asStateFlow()

    private val _executionLogs = MutableStateFlow<List<ExecutionLogEntry>>(emptyList())
    val executionLogs: StateFlow<List<ExecutionLogEntry>> = _executionLogs.asStateFlow()

    // Execution settings
    private val _executionSpeed = MutableStateFlow(1.0f)
    val executionSpeed: StateFlow<Float> = _executionSpeed.asStateFlow()

    private val _humanLikeMode = MutableStateFlow(true)
    val humanLikeMode: StateFlow<Boolean> = _humanLikeMode.asStateFlow()

    private val _stopOnError = MutableStateFlow(true)
    val stopOnError: StateFlow<Boolean> = _stopOnError.asStateFlow()

    // Execution summary
    private val _executionSummary = MutableStateFlow<ExecutionSummary?>(null)
    val executionSummary: StateFlow<ExecutionSummary?> = _executionSummary.asStateFlow()

    // Browser tab and integration for real execution
    private var currentTabId: String? = null
    private var browserIntegration: BrowserIntegration? = null

    // Browser service availability
    val hasBrowserService: Boolean get() = browserService != null

    // Active tabs provider availability
    val hasActiveTabsProvider: Boolean get() = activeTabsProvider != null

    init {
        lifecycle.doOnDestroy {
            executionJob?.cancel()
            scope.cancel()
        }

        // Load settings and available configurations
        scope.launch {
            // loadSettings does exists()/readText() and writes a default file on first run.
            val settings = withContext(Dispatchers.IO) { settingsManager.loadSettings() }
            _executionSpeed.value = settings.executionSpeed
            _humanLikeMode.value = settings.humanLikeMode
            _stopOnError.value = settings.stopOnError

            loadAvailableConfigurations()
        }
    }

    @Composable
    override fun Content() {
        RpaengineContent(this)
    }

    /**
     * Load available RPA configurations from the file system
     */
    fun loadAvailableConfigurations() {
        scope.launch { refreshConfigurations() }
    }

    /**
     * Refresh the available configurations and return them.
     *
     * The fire-and-forget [loadAvailableConfigurations] is what the UI wants, but a non-UI
     * caller (the MCP tools) has to *await* the scan before it can match a name against it.
     */
    suspend fun refreshConfigurations(): List<ConfigFileInfo> {
        val found = withContext(Dispatchers.IO) { settingsManager.findAvailableConfigurations() }
        _availableConfigs.value = found
        addLog(LogLevel.INFO, "Found ${found.size} configuration(s)")
        return found
    }

    /**
     * Load a specific configuration
     */
    fun loadConfiguration(configInfo: ConfigFileInfo) {
        scope.launch { loadConfigurationNow(configInfo) }
    }

    /**
     * Load [configInfo], reporting why not when it fails.
     *
     * Returning a bare Boolean collapsed "a run is in flight" into "it did not parse", so
     * `rpa_load` during a run told the caller its plan was corrupt - which is the misdirection
     * [LoadOutcome] exists to remove, and an agent told that will regenerate rather than stop.
     */
    suspend fun loadConfigurationNow(configInfo: ConfigFileInfo): LoadResult {
        if (_executionStatus.value == ExecutionStatus.EXECUTING ||
            _executionStatus.value == ExecutionStatus.PAUSED ||
            _executionStatus.value == ExecutionStatus.LOADING
        ) {
            // Loading clears results and resets status, which silently kills a run in progress.
            // PAUSED counts: pausing only flips the flag, so the job is still alive and would
            // append one stale result into the freshly cleared list. LOADING counts because two
            // concurrent rpa_load calls otherwise interleave and the last write wins.
            addLog(LogLevel.ERROR, "A run is in progress - stop it before loading a configuration")
            return LoadResult.Busy(_executionStatus.value)
        }
        _executionStatus.value = ExecutionStatus.LOADING
        addLog(LogLevel.INFO, "Loading configuration: ${configInfo.name}")

        val config = withContext(Dispatchers.IO) { settingsManager.loadConfiguration(configInfo.path) }

        if (config != null) {
            _selectedConfig.value = config
            _currentActionIndex.value = -1
            _executionResults.value = emptyList()
            _executionSummary.value = null
            _executionStatus.value = ExecutionStatus.IDLE

            withContext(Dispatchers.IO) { settingsManager.addToRecent(configInfo.path) }
            addLog(LogLevel.SUCCESS, "Loaded ${config.actions.size} actions from ${config.name}")
        } else {
            _executionStatus.value = ExecutionStatus.ERROR
            addLog(LogLevel.ERROR, "Failed to load configuration: ${configInfo.name}")
        }
        return if (config != null) LoadResult.Loaded else LoadResult.NotParsed
    }

    /** Why [loadConfigurationNow] did or did not load. */
    sealed interface LoadResult {
        data object Loaded : LoadResult
        data object NotParsed : LoadResult
        data class Busy(val status: ExecutionStatus) : LoadResult
    }

    /**
     * Set execution speed
     */
    fun setExecutionSpeed(speed: Float) {
        _executionSpeed.value = speed
        // Off Main: this writes JSON synchronously from a Compose event handler.
        scope.launch { withContext(Dispatchers.IO) { settingsManager.updateSettings { it.copy(executionSpeed = speed) } } }
    }

    /**
     * Set human-like mode
     */
    fun setHumanLikeMode(enabled: Boolean) {
        _humanLikeMode.value = enabled
        // Off Main: this writes JSON synchronously from a Compose event handler.
        scope.launch { withContext(Dispatchers.IO) { settingsManager.updateSettings { it.copy(humanLikeMode = enabled) } } }
    }

    /**
     * Set stop on error
     */
    fun setStopOnError(enabled: Boolean) {
        _stopOnError.value = enabled
        // Off Main: this writes JSON synchronously from a Compose event handler.
        scope.launch { withContext(Dispatchers.IO) { settingsManager.updateSettings { it.copy(stopOnError = enabled) } } }
    }

    /**
     * Load [name] from the configurations BOSS itself manages, refusing anything outside them.
     *
     * This is the agent-reachable entry point, and the scan deliberately includes `~/Downloads`
     * for the human picking from the panel. Combined with substring matching and the
     * `run_script` verb, that source would let any JSON file that lands in Downloads become
     * agent-triggered script execution in a tab holding the user's live session. A person
     * clicking a downloaded plan is choosing it; an agent resolving a name is not.
     */
    suspend fun selectManagedConfigurationByName(name: String): LoadOutcome {
        // One scan for both the match and the miss report: two calls walked three directories
        // twice and logged "Found N configuration(s)" twice for every miss.
        val all = refreshConfigurations()
        // Roots resolved once for the whole scan, not per candidate.
        val roots = settingsManager.managedRoots()
        val (managed, unmanaged) = all.partition { settingsManager.isManagedPath(it.path, roots) }
        managed.matchByName(name)?.let { match ->
            return when (val result = loadConfigurationNow(match)) {
                LoadResult.Loaded -> LoadOutcome.Loaded(match.name)
                LoadResult.NotParsed -> LoadOutcome.Failed(match.name)
                is LoadResult.Busy -> LoadOutcome.Busy(result.status)
            }
        }
        // A name the user can see in the panel but an agent may not load is a *policy* answer, not
        // "no such configuration" - saying the latter reads as a bug to whoever is looking at it.
        unmanaged.matchByName(name)?.let { return LoadOutcome.NotManaged(it.name) }
        return LoadOutcome.NoMatch(managed.map { it.name })
    }

    /** What [selectManagedConfigurationByName] did, so the caller can report it precisely. */
    sealed interface LoadOutcome {
        data class Loaded(val name: String) : LoadOutcome
        data class Failed(val name: String) : LoadOutcome
        data class NotManaged(val name: String) : LoadOutcome
        data class Busy(val status: ExecutionStatus) : LoadOutcome
        data class NoMatch(val available: List<String>) : LoadOutcome
    }

    /** The loaded configuration's name, so a caller can tell what a run would execute. */
    fun loadedConfigurationName(): String? = _selectedConfig.value?.name

    /**
     * Start or resume execution.
     */
    fun startExecution() {
        if (_executionStatus.value == ExecutionStatus.EXECUTING ||
            _executionStatus.value == ExecutionStatus.LOADING
        ) {
            // Reachable from an agent that can call rpa_run in a loop. Without this, a second
            // start cleared the results mid-run and replaced executionJob WITHOUT cancelling it,
            // so the old job appended one stale result into the new run and orphaned its tab.
            addLog(LogLevel.ERROR, "A run is already in progress - stop it before starting another")
            return
        }
        val config =
            _selectedConfig.value ?: run {
                // Was a bare `?: return`, so `rpa_run` reported "Started" while nothing happened
                // and the status stayed IDLE - the same silent-success shape as the unknown-verb
                // branch this change also fixed.
                addLog(LogLevel.ERROR, "No configuration is loaded - select one before running")
                return
            }

        if (_executionStatus.value == ExecutionStatus.PAUSED) {
            // Resume from paused state.
            //
            // Cancel first: pausing only flips the status flag, so the previous job is still alive
            // and can sit inside awaitElement for up to ELEMENT_TIMEOUT_MS. Resuming without
            // cancelling let it reach the top of its loop, see EXECUTING again and carry on from
            // action i+1 while the new job started from i - two loops appending results and
            // driving the same tab, which is the double-start bug reached through resume.
            val previous = executionJob
            previous?.cancel()
            _executionStatus.value = ExecutionStatus.EXECUTING
            addLog(LogLevel.INFO, "Resuming execution from action ${_currentActionIndex.value + 1}")
            executionJob = scope.launch {
                // Joined, not just cancelled: cancellation is cooperative, so without this the old
                // job can still be unwinding while the new one starts appending results.
                previous?.join()
                executeActions()
            }
        } else {
            // Start fresh - create a browser tab first
            _executionStatus.value = ExecutionStatus.LOADING
            _currentActionIndex.value = 0
            // Stale handles from the previous run: if this run's createBrowserTab returns null,
            // the log says "simulation mode" while executeAction still drives the OLD tab.
            browserIntegration = null
            currentTabId = null
            _executionResults.value = emptyList()
            _executionSummary.value = ExecutionSummary(
                totalActions = config.actions.size,
                completedActions = 0,
                failedActions = 0,
                skippedActions = 0,
                totalDuration = 0,
                startTime = Clock.System.now().toEpochMilliseconds()
            )

            executionJob = scope.launch {
                // Create a browser tab for execution
                val provider = activeTabsProvider
                if (provider != null) {
                    val firstNavUrl = config.actions.firstOrNull { it.type == ActionTypes.NAVIGATE }?.value ?: "about:blank"
                    val tabTitle = "RPA: ${config.name}"

                    addLog(LogLevel.INFO, "Creating browser tab for RPA execution...")

                    val tabId = provider.createBrowserTab(firstNavUrl, tabTitle)
                    if (tabId != null) {
                        currentTabId = tabId
                        addLog(LogLevel.SUCCESS, "Browser tab created: $tabTitle")

                        // Wait for the tab to initialize
                        delay(1000)

                        // Get browser integration for the tab
                        val integration = provider.getBrowserIntegration(tabId)
                        if (integration != null && integration.isBrowserAvailable()) {
                            browserIntegration = integration
                            addLog(LogLevel.SUCCESS, "Browser connection established")
                        } else {
                            addLog(LogLevel.WARNING, "Could not connect to browser - running in simulation mode")
                        }
                    } else {
                        addLog(LogLevel.WARNING, "Could not create browser tab - running in simulation mode")
                    }
                } else {
                    addLog(LogLevel.WARNING, "No ActiveTabsProvider available - running in simulation mode")
                }

                _executionStatus.value = ExecutionStatus.EXECUTING
                addLog(LogLevel.INFO, "Starting execution of ${config.name} (${config.actions.size} actions)")
                executeActions()
            }
        }
    }

    /**
     * Pause execution
     */
    fun pauseExecution() {
        if (_executionStatus.value == ExecutionStatus.EXECUTING) {
            _executionStatus.value = ExecutionStatus.PAUSED
            addLog(LogLevel.WARNING, "Execution paused at action ${_currentActionIndex.value + 1}")
        }
    }

    /**
     * Stop execution
     */
    fun stopExecution(): Boolean {
        val wasRunning =
            _executionStatus.value == ExecutionStatus.EXECUTING ||
                _executionStatus.value == ExecutionStatus.PAUSED ||
                _executionStatus.value == ExecutionStatus.LOADING
        if (!wasRunning) {
            // Reported "Stopped RPA execution." and logged a stop for a run that was never
            // going, which by this plugin's own standard is the wrong answer.
            return false
        }
        executionJob?.cancel()
        _executionStatus.value = ExecutionStatus.IDLE
        _currentActionIndex.value = -1

        // Update summary
        _executionSummary.value?.let { summary ->
            _executionSummary.value = summary.copy(
                endTime = Clock.System.now().toEpochMilliseconds()
            )
        }

        addLog(LogLevel.WARNING, "Execution stopped")
        return true
    }

    /**
     * Reset execution state
     */
    fun resetExecution() {
        executionJob?.cancel()
        _executionStatus.value = ExecutionStatus.IDLE
        _currentActionIndex.value = -1
        _executionResults.value = emptyList()
        _executionSummary.value = null
        addLog(LogLevel.INFO, "Execution reset")
    }

    /**
     * Clear execution logs
     */
    fun clearLogs() {
        _executionLogs.value = emptyList()
    }

    /**
     * Execute RPA actions
     */
    private suspend fun executeActions() {
        val config = _selectedConfig.value ?: return
        val startIndex = _currentActionIndex.value.coerceAtLeast(0)

        for (index in startIndex until config.actions.size) {
            // Check if paused or stopped
            if (_executionStatus.value != ExecutionStatus.EXECUTING) {
                return
            }

            // Pausing only flips the status flag, so the in-flight action runs to completion and
            // records its result while _currentActionIndex still points at it - and this loop
            // resumes from that index. Skipping an index that already has a result is what stops
            // resume re-running it: a second result for the same action, counted twice, and for a
            // click that navigated, the click replayed on a different page.
            if (_executionResults.value.any { it.actionIndex == index }) {
                continue
            }

            _currentActionIndex.value = index
            val action = config.actions[index]

            addLog(LogLevel.INFO, "Executing action ${index + 1}: ${action.name}", index)

            val startTime = Clock.System.now().toEpochMilliseconds()

            // Simulate action execution
            val result = executeAction(action)

            val duration = Clock.System.now().toEpochMilliseconds() - startTime

            // Record result
            val actionResult = ActionExecutionResult(
                actionIndex = index,
                actionName = action.name,
                success = result.first,
                error = result.second,
                duration = duration
            )
            _executionResults.value = _executionResults.value + actionResult

            // Update summary
            _executionSummary.value?.let { summary ->
                _executionSummary.value = summary.copy(
                    completedActions = if (result.first) summary.completedActions + 1 else summary.completedActions,
                    failedActions = if (!result.first) summary.failedActions + 1 else summary.failedActions,
                    totalDuration = Clock.System.now().toEpochMilliseconds() - summary.startTime
                )
            }

            if (result.first) {
                addLog(LogLevel.SUCCESS, "Action ${index + 1} completed (${duration}ms)", index)
            } else {
                addLog(LogLevel.ERROR, "Action ${index + 1} failed: ${result.second}", index)

                if (_stopOnError.value) {
                    _executionStatus.value = ExecutionStatus.ERROR
                    addLog(LogLevel.ERROR, "Execution stopped due to error")
                    return
                }
            }

            // Add delay between actions based on speed
            if (index < config.actions.size - 1) {
                val baseDelay = if (_humanLikeMode.value) {
                    // Random delay between 500-1500ms for human-like behavior
                    (500 + (Math.random() * 1000)).toLong()
                } else {
                    300L
                }
                val adjustedDelay = (baseDelay / _executionSpeed.value).toLong()
                delay(adjustedDelay)
            }
        }

        // Execution reached the end of the plan. With stopOnError = false that is not the same
        // as success: failed actions were recorded and the run used to finish COMPLETED and log
        // "Execution completed successfully" over the top of them.
        val failed = _executionResults.value.count { !it.success }
        _executionStatus.value = if (failed > 0) ExecutionStatus.ERROR else ExecutionStatus.COMPLETED
        _executionSummary.value?.let { summary ->
            _executionSummary.value = summary.copy(
                endTime = Clock.System.now().toEpochMilliseconds()
            )
        }
        if (failed > 0) {
            addLog(
                LogLevel.ERROR,
                "Execution finished with $failed of ${_executionResults.value.size} action(s) failed",
            )
        } else {
            addLog(LogLevel.SUCCESS, "Execution completed successfully")
        }
    }

    /**
     * Execute a single action
     */
    private suspend fun executeAction(action: RpaActionConfig): Pair<Boolean, String?> {
        val browser = browserIntegration

        // If we have browser integration, execute real actions
        if (browser != null && browser.isBrowserAvailable()) {
            return actionRunner.execute(browser, action)
        }

        // Otherwise, fall back to simulation mode
        return executeSimulatedAction(action)
    }

    /**
     * Execute a simulated action: sleep for a plausible duration and report the outcome.
     *
     * Two things this must NOT do, both of which it used to. It returned a random 5% failure,
     * making a simulated run non-reproducible for no benefit. And its `else` branch passed *any*
     * verb, including the ones [ActionRunner.execute] explicitly refuses, so with no browser an
     * unimplemented verb was again indistinguishable from a working one - and the
     * `rpa_results` output was indistinguishable from a real run's. Unsupported verbs fail here
     * too, and every outcome is tagged so a caller cannot mistake this for execution.
     */
    private suspend fun executeSimulatedAction(action: RpaActionConfig): Pair<Boolean, String?> {
        val executionTime =
            when (action.type) {
                ActionTypes.NAVIGATE -> ActionTiming.NAVIGATE_SETTLE_MS
                ActionTypes.WAIT -> action.value?.toLongOrNull() ?: ActionTiming.DEFAULT_WAIT_MS
                ActionTypes.SCROLL -> ActionTiming.ACTION_SETTLE_MS
                ActionTypes.ASSERT -> ActionTiming.INPUT_SETTLE_MS
                else -> ActionTiming.ACTION_SETTLE_MS
            }
        delay((executionTime / _executionSpeed.value).toLong())

        return when (action.type) {
            ActionTypes.NAVIGATE, ActionTypes.CLICK, ActionTypes.INPUT, ActionTypes.SELECT,
            ActionTypes.KEYPRESS, ActionTypes.SUBMIT, ActionTypes.RUN_SCRIPT, ActionTypes.WAIT,
            ActionTypes.SCROLL, ActionTypes.ASSERT,
            -> Pair(true, "simulated: no browser, nothing was actually done")
            else -> Pair(false, "simulated: '${action.type}' is not a verb this engine implements")
        }
    }

    /**
     * Add a log entry
     */
    private fun addLog(level: LogLevel, message: String, actionIndex: Int? = null) {
        val entry = ExecutionLogEntry(
            level = level,
            message = message,
            actionIndex = actionIndex
        )
        _executionLogs.value = _executionLogs.value + entry

        // Keep only last 100 log entries
        if (_executionLogs.value.size > 100) {
            _executionLogs.value = _executionLogs.value.takeLast(100)
        }
    }

    /**
     * Format timestamp for display
     */
    fun formatTimestamp(timestamp: Long): String {
        return settingsManager.formatTimestamp(timestamp)
    }
}
