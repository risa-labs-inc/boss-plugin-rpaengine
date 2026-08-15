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
import kotlinx.coroutines.CancellationException
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
            return executeRealAction(browser, action)
        }

        // Otherwise, fall back to simulation mode
        return executeSimulatedAction(action)
    }

    /**
     * Execute a real action in the browser
     */
    private suspend fun executeRealAction(browser: BrowserIntegration, action: RpaActionConfig): Pair<Boolean, String?> {
        return try {
            when (action.type) {
                ActionTypes.NAVIGATE -> {
                    val url = action.value ?: return Pair(false, "No URL specified")
                    // Assigning location.href to a javascript: URL executes it in the page.
                    // `run_script` is a declared verb, so this is not a new capability - but a
                    // navigate step should navigate, and nothing should smuggle script through it.
                    if (!isNavigableUrl(url)) {
                        return Pair(false, "Refusing to navigate to '$url' (http, https or about only)")
                    }
                    // Was discarding the result, so a dead tab or a throwing assignment still
                    // reported ok - in the verb that is action #0 of every plan.
                    //
                    // The assignment is all that is checked. Comparing location.href before and
                    // after looks stronger and is not: the execution tab is *created at* the first
                    // navigate's URL, so that navigation is legitimately a no-op, and
                    // "https://www.google.com" vs the browser's "https://www.google.com/" differ by
                    // a slash - which reported the working step as a failure and stopped the run at
                    // action #0. A redirect breaks the comparison from the other side.
                    val assigned =
                        browser.executeJavaScript(
                            "(function () { try { window.location.href = ${url.asJsString()}; " +
                                "return true; } catch (e) { return 'threw: ' + e.message; } })();",
                        )
                    delay(NAVIGATE_SETTLE_MS)
                    when {
                        assigned is String && assigned.startsWith("threw: ") -> Pair(false, assigned)
                        assigned.isJsTrue() -> Pair(true, null)
                        // No completion value: the assignment navigated and tore the frame down
                        // before it could return, which is what a successful navigate looks like.
                        assigned == null || assigned == "" -> Pair(true, null)
                        else -> Pair(false, "Could not navigate to '$url': $assigned")
                    }
                }
                ActionTypes.CLICK -> {
                    val outcome =
                        browser.runOn(
                            action.selector,
                            "el.click();",
                            "No element matched ${action.selector.value}",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(ACTION_SETTLE_MS)
                    outcome
                }
                ActionTypes.INPUT -> {
                    val value = action.value ?: ""
                    // Reports whether the value actually took. `el.value = x` on a contenteditable
                    // div or any non-form element a plan guessed sets an expando property: nothing
                    // visible happens and existence alone would call that a success.
                    val outcome =
                        browser.runOn(
                            action.selector,
                            typeValueScript(value),
                            "Could not type into '${action.selector.value}' (no match, or it has no value)",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(INPUT_SETTLE_MS)
                    outcome
                }
                ActionTypes.SELECT -> {
                    val value = action.value ?: ""
                    // Assigning an unmatched value to a <select> leaves it unchanged per spec, and
                    // `change` still fires - so existence reported ok for a selection that never
                    // happened. Matches by label as well as value, because a plan names what the
                    // user sees (same reasoning as TEXT_CANDIDATE_TAGS).
                    val outcome =
                        browser.runOn(
                            action.selector,
                            selectOptionScript(value),
                            "No option '$value' in '${action.selector.value}'",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(INPUT_SETTLE_MS)
                    outcome
                }
                // Enter in a search field is how most of the web is driven, so this is not an
                // exotic verb - its absence is why a generated "search for X" plan stopped at
                // typing. `value` names the key, defaulting to Enter.
                ActionTypes.KEYPRESS -> {
                    val key = action.value?.takeIf { it.isNotBlank() } ?: "Enter"
                    val dispatch = "el.focus(); " + keyPressScript(key)
                    val outcome =
                        if (action.selector.type == SelectorTypes.NONE || action.selector.value.isNullOrBlank()) {
                            // No selector means "whatever has focus". Nothing is focused right
                            // after a navigation, which is exactly when a plan says "press
                            // Enter", so the script reports whether it had a real target
                            // instead of this branch assuming success.
                            val landed =
                                browser.executeJavaScript(
                                    // `landed` is checked BEFORE dispatching: firing all three
                                    // key events at <body> and only then deciding there was no
                                    // real target left side effects on the page for an action that
                                    // reports failure.
                                    "(function () { var el = document.activeElement; " +
                                        "if (!el || el === document.body) { return false; } " +
                                        "$dispatch return landed; })();",
                                ).isJsTrue()
                            if (landed) {
                                Pair(true, null)
                            } else {
                                Pair(false, "Nothing was focused to receive the '$key' key")
                            }
                        } else {
                            browser.runOn(
                                action.selector,
                                dispatch,
                                "No element matched ${action.selector.value}",
                            ) ?: return Pair(false, unsupportedSelector(action.selector))
                        }
                    delay(ACTION_SETTLE_MS)
                    outcome
                }
                ActionTypes.SUBMIT -> {
                    // requestSubmit fires validation and submit handlers the way a real click
                    // does; submit() skips both, so it is only the fallback.
                    val script =
                        "var f = el.form || el; " +
                            "if (f.requestSubmit) { f.requestSubmit(); } else { f.submit(); }"
                    val outcome =
                        browser.runOn(
                            action.selector,
                            script,
                            "No element matched ${action.selector.value}",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(NAVIGATE_SETTLE_MS)
                    outcome
                }
                ActionTypes.RUN_SCRIPT -> {
                    val script = action.value ?: return Pair(false, "No script specified")
                    // Wrapped so a throwing script is a failure rather than a silent pass: the
                    // bridge may surface an eval error as a returned value rather than an
                    // exception, and this branch used to discard the result entirely.
                    val outcome =
                        browser.executeJavaScript(
                            "(function () { try { $script; return true; } " +
                                "catch (e) { return 'threw: ' + e.message; } })();",
                        )
                    delay(ACTION_SETTLE_MS)
                    // Deliberately NOT interpretOutcome's last branch: that one is justified by
                    // awaitElement having already proven the element exists, and a bare script has
                    // no such proof - "nothing came back" is genuinely ambiguous here. It stays a
                    // failure, but says which ambiguity rather than printing `null`.
                    when {
                        outcome.isJsTrue() -> Pair(true, null)
                        outcome == null || outcome == "" ->
                            Pair(false, "Script returned no completion value; it may have navigated")
                        else -> Pair(false, "Script failed: $outcome")
                    }
                }
                ActionTypes.WAIT -> {
                    val raw = action.value
                    val waitTime = raw?.toLongOrNull()
                    if (raw != null && waitTime == null) {
                        // A warning was not enough: the plan asked for a specific settle and got a
                        // different one, and reporting ok says it got what it asked for. "3s" and
                        // "3000ms" are the realistic cases.
                        return Pair(false, "Wait value '$raw' is not a number of milliseconds")
                    }
                    delay(waitTime ?: DEFAULT_WAIT_MS)
                    Pair(true, null)
                }
                ActionTypes.SCROLL -> {
                    val coords = action.value?.split(",")?.map { it.trim().toIntOrNull() }
                    if (coords?.any { it == null } == true) {
                        // Same reasoning as wait: scrolling somewhere other than where the plan
                        // said, and reporting ok, is the failure this engine exists to remove.
                        return Pair(false, "Scroll value '${action.value}' is not a coordinate or 'x,y'")
                    }
                    // One coordinate means the y axis. Read as x it parsed cleanly, fired no
                    // warning, and scrolled the page *sideways* to 500 while staying at the top -
                    // and "scroll to 500" almost always means down.
                    val x = if (coords != null && coords.size > 1) coords[0] ?: 0 else 0
                    val y = (if (coords != null && coords.size > 1) coords[1] else coords?.get(0)) ?: 0
                    val scrolled =
                        browser.executeJavaScript(
                            "(function () { try { window.scrollTo($x, $y); return true; } " +
                                "catch (e) { return 'threw: ' + e.message; } })();",
                        )
                    delay(ACTION_SETTLE_MS)
                    if (scrolled.isJsTrue()) Pair(true, null) else Pair(false, "Could not scroll: $scrolled")
                }
                ActionTypes.ASSERT -> {
                    // An assert used to pass on mere existence, dropping `value` silently - so a
                    // plan asserting the wrong text passed, which is the same silent success as
                    // everything else here.
                    val expected = action.value?.takeIf { it.isNotBlank() }
                    val body = if (expected == null) "" else assertTextScript(expected)
                    val failure =
                        if (expected == null) {
                            "Assertion failed: element not found"
                        } else {
                            "Assertion failed: '${action.selector.value}' does not contain '$expected'"
                        }
                    browser.runOn(action.selector, body, failure)
                        ?: return Pair(false, unsupportedSelector(action.selector))
                }
                // Declared in ActionTypes but not doable through `executeJavaScript`, which is the
                // only thing BrowserIntegration offers: a screenshot needs host capture, and
                // frame switching needs a target this api cannot express. Failing says so; the
                // previous `else` slept 500ms and reported success, so an unimplemented verb was
                // indistinguishable from a working one in the log.
                ActionTypes.SCREENSHOT, ActionTypes.SWITCH_FRAME ->
                    Pair(false, "'${action.type}' is not supported when driving a real browser")
                else -> Pair(false, "Unknown action type '${action.type}'")
            }
        } catch (e: CancellationException) {
            // Must come first: CancellationException is a RuntimeException, and every verb has a
            // delay() inside this try - so a cancelled job almost always resumes *into* the catch
            // below. Swallowed, `rpa_stop` appended a phantom FAILED action and left the status
            // ERROR, and a resume was killed by the cancelled job's resumption setting ERROR
            // before the new one could start.
            throw e
        } catch (e: Exception) {
            Pair(false, "Error executing action: ${e.message}")
        }
    }

    /**
     * Run [body] against the first element [selector] names, and report what happened.
     *
     * Returns null when the selector kind is one this engine cannot resolve, so the caller fails
     * with a message rather than silently doing nothing - which is what the old
     * `else -> "// No selector specified"` did, while still reporting success.
     *
     * Otherwise the outcome distinguishes four cases, because collapsing them sends the reader
     * hunting for the wrong thing:
     *  - the body ran: success.
     *  - the body threw: the failure names the exception, not the selector.
     *  - the body returned false, meaning its own check failed (the value did not take, no such
     *    option): [onFailure].
     *  - no completion value came back at all: success. Existence was just proven by
     *    [awaitElement], so this is a click or submit that navigated and tore the frame down
     *    before the value returned - failing it would fail the step that actually worked and stop
     *    the run right at the point it succeeded.
     *
     * Every value crosses into JavaScript through [asJsString]. That is not defensive tidying: a
     * generated plan's very first selector is typically `input[name='q']`, and interpolating it
     * raw produced `document.querySelector('input[name='q']')` - a syntax error. XPath is worse,
     * since `//div[@role='tab']` is full of quotes.
     */
    private suspend fun BrowserIntegration.runOn(
        selector: SelectorInfo,
        body: String,
        onFailure: String,
    ): Pair<Boolean, String?>? {
        val primary = locateExpression(selector) ?: return null
        // A tag-qualified CSS selector gets its tag dropped as an alternative in the SAME probe.
        // Two sequential deadlines made every miss cost 10s and let the fallback win only after
        // the primary had exhausted its own - so it is an alternative, not a retry.
        val stripped = selector.value.orEmpty().stripTagQualifier()
        val fallback =
            if (selector.type == SelectorTypes.CSS && stripped != selector.value) {
                visibleQuerySelector(stripped)
            } else {
                null
            }
        val locate = if (fallback == null) primary else "($primary) || ($fallback)"
        if (!awaitElement(locate)) return Pair(false, onFailure)
        if (fallback != null && !matches(primary)) {
            addLog(
                LogLevel.WARNING,
                "Selector '${selector.value}' matched nothing; used '$stripped' instead",
            )
        }
        // Wrapped: `var` at eval top level lands on the page's global object, so `el` would
        // clobber a page global of that name.
        val outcome =
            executeJavaScript(
                "(function () { var el = $locate; if (!el) { return false; } " +
                    "try { $body } catch (e) { return 'threw: ' + e.message; } return true; })();",
            )
        return interpretOutcome(outcome, onFailure)
    }

    /**
     * Poll until [locate] resolves to an element, or the timeout expires.
     *
     * Existence only: this must never carry the action's body. Probe and mutation used to be one
     * script, so an eval whose completion value did not come back - a body that threw, or a click
     * that navigated and tore down the frame - re-ran the mutation on every poll, turning one
     * submit into fifty.
     *
     * The interval backs off. A `text` selector resolves by scanning twelve tag names, so a miss
     * at a flat 100ms was fifty full sweeps of the document; backing off costs a little latency on
     * a slow-appearing element and roughly quarters that.
     */
    private suspend fun BrowserIntegration.awaitElement(locate: String): Boolean {
        val deadline = Clock.System.now().toEpochMilliseconds() + ELEMENT_TIMEOUT_MS
        var interval = ELEMENT_POLL_MS
        while (true) {
            if (matches(locate)) return true
            if (Clock.System.now().toEpochMilliseconds() >= deadline) return false
            delay(interval)
            interval = (interval * 2).coerceAtMost(ELEMENT_POLL_MAX_MS)
        }
    }

    /** True when [locate] currently resolves to an element. Side-effect free. */
    private suspend fun BrowserIntegration.matches(locate: String): Boolean =
        executeJavaScript("(function () { return !!($locate); })();").isJsTrue()

    private fun unsupportedSelector(selector: SelectorInfo): String =
        "Cannot resolve a '${selector.type}' selector" +
            if (selector.value.isNullOrBlank()) " with no value" else ""

    /**
     * Execute a simulated action: sleep for a plausible duration and report the outcome.
     *
     * Two things this must NOT do, both of which it used to. It returned a random 5% failure,
     * making a simulated run non-reproducible for no benefit. And its `else` branch passed *any*
     * verb, including the ones [executeRealAction] explicitly refuses, so with no browser an
     * unimplemented verb was again indistinguishable from a working one - and the
     * `rpa_results` output was indistinguishable from a real run's. Unsupported verbs fail here
     * too, and every outcome is tagged so a caller cannot mistake this for execution.
     */
    private suspend fun executeSimulatedAction(action: RpaActionConfig): Pair<Boolean, String?> {
        val executionTime =
            when (action.type) {
                ActionTypes.NAVIGATE -> NAVIGATE_SETTLE_MS
                ActionTypes.WAIT -> action.value?.toLongOrNull() ?: DEFAULT_WAIT_MS
                ActionTypes.SCROLL -> ACTION_SETTLE_MS
                ActionTypes.ASSERT -> INPUT_SETTLE_MS
                else -> ACTION_SETTLE_MS
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

    private companion object {
        /** After a navigation or form submit, before the next action reads the new page. */
        const val NAVIGATE_SETTLE_MS = 1000L

        /** After a click, keypress or scroll, so handlers run before the next action. */
        const val ACTION_SETTLE_MS = 300L

        /** After typing, which fires input and change handlers. */
        const val INPUT_SETTLE_MS = 200L

        /** A `wait` action with no parseable value. */
        const val DEFAULT_WAIT_MS = 1000L


        /**
         * How long to keep looking for an element before calling the action failed.
         *
         * Deliberately NOT scaled by the execution speed, unlike the inter-action delays: speed
         * is about pacing a run for a watching human, while this is how long a page is given to
         * produce an element. Scaling it would make a fast run fail on a slow site.
         */
        const val ELEMENT_TIMEOUT_MS = 5_000L
        const val ELEMENT_POLL_MS = 100L

        /** The poll interval doubles up to this, so a miss is not fifty full document sweeps. */
        const val ELEMENT_POLL_MAX_MS = 500L
    }
}
