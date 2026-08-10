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
            val settings = settingsManager.loadSettings()
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

    /** Load [configInfo] and report whether it parsed, for a caller that must await the result. */
    suspend fun loadConfigurationNow(configInfo: ConfigFileInfo): Boolean {
        if (_executionStatus.value == ExecutionStatus.EXECUTING ||
            _executionStatus.value == ExecutionStatus.PAUSED
        ) {
            // Loading clears results and resets status, which silently kills a run in progress.
            // PAUSED counts: pausing only flips the flag, so the job is still alive and would
            // append one stale result into the freshly cleared list.
            addLog(LogLevel.ERROR, "A run is in progress - stop it before loading a configuration")
            return false
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
        return config != null
    }

    /**
     * Set execution speed
     */
    fun setExecutionSpeed(speed: Float) {
        _executionSpeed.value = speed
        settingsManager.updateSettings { it.copy(executionSpeed = speed) }
    }

    /**
     * Set human-like mode
     */
    fun setHumanLikeMode(enabled: Boolean) {
        _humanLikeMode.value = enabled
        settingsManager.updateSettings { it.copy(humanLikeMode = enabled) }
    }

    /**
     * Set stop on error
     */
    fun setStopOnError(enabled: Boolean) {
        _stopOnError.value = enabled
        settingsManager.updateSettings { it.copy(stopOnError = enabled) }
    }

    /**
     * Load [name] from the available configurations, or return false when there is no such one.
     *
     * Exists so a run can be started without a click. Everything else on this path was
     * reachable - a plan can be generated and written, the engine lists it - and then
     * [startExecution] returned silently because nothing had selected it.
     */
    suspend fun selectConfigurationByName(name: String): Boolean {
        val match = refreshConfigurations().matchByName(name) ?: return false
        return loadConfigurationNow(match)
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
        val (managed, unmanaged) = all.partition { settingsManager.isManagedPath(it.path) }
        managed.matchByName(name)?.let { match ->
            return if (loadConfigurationNow(match)) {
                LoadOutcome.Loaded(match.name)
            } else {
                LoadOutcome.Failed(match.name)
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
            executionJob?.cancel()
            _executionStatus.value = ExecutionStatus.EXECUTING
            addLog(LogLevel.INFO, "Resuming execution from action ${_currentActionIndex.value + 1}")
            executionJob = scope.launch {
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
    fun stopExecution() {
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

        // Execution completed
        _executionStatus.value = ExecutionStatus.COMPLETED
        _executionSummary.value?.let { summary ->
            _executionSummary.value = summary.copy(
                endTime = Clock.System.now().toEpochMilliseconds()
            )
        }
        addLog(LogLevel.SUCCESS, "Execution completed successfully")
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
                    if (NAVIGABLE_SCHEME.matchEntire(url.trim()) == null) {
                        return Pair(false, "Refusing to navigate to '$url' (http, https or about only)")
                    }
                    browser.executeJavaScript("window.location.href = ${url.asJsString()};")
                    delay(NAVIGATE_SETTLE_MS)
                    Pair(true, null)
                }
                ActionTypes.CLICK -> {
                    val found = browser.elementScript(action.selector, "el.click();")
                        ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(ACTION_SETTLE_MS)
                    if (found) Pair(true, null) else Pair(false, "No element matched ${action.selector.value}")
                }
                ActionTypes.INPUT -> {
                    val value = action.value ?: ""
                    val found =
                        browser.elementScript(
                            action.selector,
                            "el.focus(); el.value = ${value.asJsString()}; " +
                                "el.dispatchEvent(new Event('input', { bubbles: true })); " +
                                "el.dispatchEvent(new Event('change', { bubbles: true }));",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(INPUT_SETTLE_MS)
                    if (found) Pair(true, null) else Pair(false, "No element matched ${action.selector.value}")
                }
                ActionTypes.SELECT -> {
                    val value = action.value ?: ""
                    val found =
                        browser.elementScript(
                            action.selector,
                            "el.value = ${value.asJsString()}; " +
                                "el.dispatchEvent(new Event('change', { bubbles: true }));",
                        ) ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(INPUT_SETTLE_MS)
                    if (found) Pair(true, null) else Pair(false, "No element matched ${action.selector.value}")
                }
                // Enter in a search field is how most of the web is driven, so this is not an
                // exotic verb - its absence is why a generated "search for X" plan stopped at
                // typing. `value` names the key, defaulting to Enter.
                ActionTypes.KEYPRESS -> {
                    val key = action.value?.takeIf { it.isNotBlank() } ?: "Enter"
                    val dispatch = "if (el) { el.focus(); }" + keyPressScript(key)
                    val found =
                        if (action.selector.type == SelectorTypes.NONE || action.selector.value.isNullOrBlank()) {
                            // No selector means "whatever has focus". Nothing is focused right
                            // after a navigation, which is exactly when a plan says "press
                            // Enter", so the script reports whether it had a real target
                            // instead of this branch assuming success.
                            browser.executeJavaScript(
                                "(function () { var el = document.activeElement; " +
                                    "$dispatch return landed; })();",
                            ).isJsTrue()
                        } else {
                            browser.elementScript(action.selector, dispatch)
                                ?: return Pair(false, unsupportedSelector(action.selector))
                        }
                    delay(ACTION_SETTLE_MS)
                    if (found) {
                        Pair(true, null)
                    } else if (action.selector.value.isNullOrBlank()) {
                        Pair(false, "Nothing was focused to receive the '$key' key")
                    } else {
                        Pair(false, "No element matched ${action.selector.value}")
                    }
                }
                ActionTypes.SUBMIT -> {
                    // requestSubmit fires validation and submit handlers the way a real click
                    // does; submit() skips both, so it is only the fallback.
                    val script =
                        "var f = el.form || el; " +
                            "if (f.requestSubmit) { f.requestSubmit(); } else { f.submit(); }"
                    val found = browser.elementScript(action.selector, script)
                        ?: return Pair(false, unsupportedSelector(action.selector))
                    delay(NAVIGATE_SETTLE_MS)
                    if (found) Pair(true, null) else Pair(false, "No element matched ${action.selector.value}")
                }
                ActionTypes.RUN_SCRIPT -> {
                    val script = action.value ?: return Pair(false, "No script specified")
                    browser.executeJavaScript(script)
                    delay(ACTION_SETTLE_MS)
                    Pair(true, null)
                }
                ActionTypes.WAIT -> {
                    val waitTime = action.value?.toLongOrNull() ?: DEFAULT_WAIT_MS
                    delay(waitTime)
                    Pair(true, null)
                }
                ActionTypes.SCROLL -> {
                    val coords = action.value?.split(",")?.map { it.trim().toIntOrNull() ?: 0 }
                    val x = coords?.getOrNull(0) ?: 0
                    val y = coords?.getOrNull(1) ?: 0
                    browser.executeJavaScript("window.scrollTo($x, $y);")
                    delay(ACTION_SETTLE_MS)
                    Pair(true, null)
                }
                ActionTypes.ASSERT -> {
                    val found = browser.elementScript(action.selector, "")
                        ?: return Pair(false, unsupportedSelector(action.selector))
                    if (found) Pair(true, null) else Pair(false, "Assertion failed: element not found")
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
        } catch (e: Exception) {
            Pair(false, "Error executing action: ${e.message}")
        }
    }

    /**
     * Run [body] against the first element [selector] names.
     *
     * Returns null when the selector kind is one this engine cannot resolve, false when it
     * resolved but nothing matched, true when [body] ran. All three are reported differently, so
     * they must stay distinct - see [locateExpression]. The old code had no such distinction: an
     * unresolvable selector hit `else -> "// No selector specified"` and reported success.
     *
     * Every value crosses into JavaScript through [asJsString]. That is not defensive tidying: a
     * generated plan's very first selector is typically `input[name='q']`, and interpolating it
     * raw produced `document.querySelector('input[name='q']')` - a syntax error. XPath is worse,
     * since `//div[@role='tab']` is full of quotes.
     */
    private suspend fun BrowserIntegration.elementScript(
        selector: SelectorInfo,
        body: String,
    ): Boolean? {
        val primary = locateExpression(selector) ?: return null
        // A tag-qualified CSS selector gets its tag dropped as an alternative in the SAME probe.
        // Two sequential 5s deadlines made every miss cost 10s, and the fallback could only win
        // after the primary had exhausted its own - so it is an alternative, not a retry.
        val stripped = selector.value.orEmpty().stripTagQualifier()
        val fallback =
            if (selector.type == SelectorTypes.CSS && stripped != selector.value) {
                visibleQuerySelector(stripped)
            } else {
                null
            }
        val locate = if (fallback == null) primary else "($primary) || ($fallback)"
        if (!awaitElement(locate)) return false
        if (fallback != null && !matches(primary)) {
            addLog(
                LogLevel.WARNING,
                "Selector '${selector.value}' matched nothing; used '$stripped' instead",
            )
        }
        // Wrapped: `var` at eval top level lands on the page's global object, so `el`/`ev`/`f`
        // would clobber a page's own globals of those names.
        return executeJavaScript(
            "(function () { var el = $locate; if (el) { $body } return !!el; })();",
        ).isJsTrue()
    }

    /**
     * Poll until [locate] resolves to an element, or the timeout expires.
     *
     * Existence only: this must never carry the action's body. Probe and mutation used to be one
     * script, so an eval whose completion value did not come back - a body that threw, or a click
     * that navigated and tore down the frame - re-ran the mutation on every poll, turning one
     * submit into fifty.
     */
    private suspend fun BrowserIntegration.awaitElement(locate: String): Boolean {
        val deadline = Clock.System.now().toEpochMilliseconds() + ELEMENT_TIMEOUT_MS
        while (true) {
            if (matches(locate)) return true
            if (Clock.System.now().toEpochMilliseconds() >= deadline) return false
            delay(ELEMENT_POLL_MS)
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

        /** Schemes a `navigate` action may use. */
        val NAVIGABLE_SCHEME = Regex("^(?:https?://|about:)\\S*", RegexOption.IGNORE_CASE)

        /**
         * How long to keep looking for an element before calling the action failed.
         *
         * Deliberately NOT scaled by the execution speed, unlike the inter-action delays: speed
         * is about pacing a run for a watching human, while this is how long a page is given to
         * produce an element. Scaling it would make a fast run fail on a slow site.
         */
        const val ELEMENT_TIMEOUT_MS = 5_000L
        const val ELEMENT_POLL_MS = 100L
    }
}
