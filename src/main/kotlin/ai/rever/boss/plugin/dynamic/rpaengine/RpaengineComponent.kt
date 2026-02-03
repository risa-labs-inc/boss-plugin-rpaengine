package ai.rever.boss.plugin.dynamic.rpaengine

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
    private val browserService: BrowserService? = null
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

    // Browser service availability
    val hasBrowserService: Boolean get() = browserService != null

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
        scope.launch {
            _availableConfigs.value = settingsManager.findAvailableConfigurations()
            addLog(LogLevel.INFO, "Found ${_availableConfigs.value.size} configuration(s)")
        }
    }

    /**
     * Load a specific configuration
     */
    fun loadConfiguration(configInfo: ConfigFileInfo) {
        scope.launch {
            _executionStatus.value = ExecutionStatus.LOADING
            addLog(LogLevel.INFO, "Loading configuration: ${configInfo.name}")

            val config = settingsManager.loadConfiguration(configInfo.path)

            if (config != null) {
                _selectedConfig.value = config
                _currentActionIndex.value = -1
                _executionResults.value = emptyList()
                _executionSummary.value = null
                _executionStatus.value = ExecutionStatus.IDLE

                settingsManager.addToRecent(configInfo.path)
                addLog(LogLevel.SUCCESS, "Loaded ${config.actions.size} actions from ${config.name}")
            } else {
                _executionStatus.value = ExecutionStatus.ERROR
                addLog(LogLevel.ERROR, "Failed to load configuration: ${configInfo.name}")
            }
        }
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
     * Start or resume execution
     */
    fun startExecution() {
        val config = _selectedConfig.value ?: return

        if (_executionStatus.value == ExecutionStatus.PAUSED) {
            // Resume from paused state
            _executionStatus.value = ExecutionStatus.EXECUTING
            addLog(LogLevel.INFO, "Resuming execution from action ${_currentActionIndex.value + 1}")
        } else {
            // Start fresh
            _executionStatus.value = ExecutionStatus.EXECUTING
            _currentActionIndex.value = 0
            _executionResults.value = emptyList()
            _executionSummary.value = ExecutionSummary(
                totalActions = config.actions.size,
                completedActions = 0,
                failedActions = 0,
                skippedActions = 0,
                totalDuration = 0,
                startTime = Clock.System.now().toEpochMilliseconds()
            )
            addLog(LogLevel.INFO, "Starting execution of ${config.name} (${config.actions.size} actions)")
        }

        executionJob = scope.launch {
            executeActions()
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
            val result = executeAction(action, index)

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
     * Execute a single action (simulation mode)
     */
    private suspend fun executeAction(action: RpaActionConfig, index: Int): Pair<Boolean, String?> {
        // Simulate action execution with delays based on action type
        val executionTime = when (action.type) {
            ActionTypes.CLICK -> (200..500).random().toLong()
            ActionTypes.INPUT -> (action.value?.length ?: 10) * 50L + 200
            ActionTypes.SELECT -> (300..600).random().toLong()
            ActionTypes.NAVIGATE -> (1000..3000).random().toLong()
            ActionTypes.WAIT -> action.value?.toLongOrNull() ?: 1000L
            ActionTypes.SCROLL -> (200..400).random().toLong()
            ActionTypes.SCREENSHOT -> (500..1000).random().toLong()
            ActionTypes.ASSERT -> (100..300).random().toLong()
            else -> (200..500).random().toLong()
        }

        // Apply speed modifier
        val adjustedTime = (executionTime / _executionSpeed.value).toLong()
        delay(adjustedTime)

        // Simulate success (95% success rate in simulation mode)
        val success = Math.random() > 0.05

        return if (success) {
            Pair(true, null)
        } else {
            Pair(false, "Simulated error: Element not found or action failed")
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
