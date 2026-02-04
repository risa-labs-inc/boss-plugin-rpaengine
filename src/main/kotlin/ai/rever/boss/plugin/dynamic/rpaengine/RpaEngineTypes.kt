package ai.rever.boss.plugin.dynamic.rpaengine

import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Execution status for RPA actions
 */
enum class ExecutionStatus {
    IDLE,
    LOADING,
    EXECUTING,
    PAUSED,
    COMPLETED,
    ERROR
}

/**
 * Execution result for tracking action outcomes
 */
data class ActionExecutionResult(
    val actionIndex: Int,
    val actionName: String,
    val success: Boolean,
    val error: String? = null,
    val duration: Long = 0,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

/**
 * Configuration file info
 */
@Serializable
data class ConfigFileInfo(
    val name: String,
    val path: String,
    val lastModified: Long,
    val actionCount: Int = 0
)

/**
 * Selector information for locating elements
 */
@Serializable
data class SelectorInfo(
    val type: String = "xpath", // css, xpath, text, id, none
    val value: String? = null,
    val isUnique: Boolean? = null
)

/**
 * RPA configuration for execution
 */
@Serializable
data class RpaConfiguration(
    val name: String,
    val description: String = "",
    val actions: List<RpaActionConfig>
)

/**
 * RPA action configuration
 */
@Serializable
data class RpaActionConfig(
    val name: String = "",
    val actionType: String = "default", // default, assertion, screenshot, network, custom
    val type: String, // click, input, navigate, wait, select, scroll, switch_frame, run_script, screenshot, assert
    val selector: SelectorInfo,
    val value: String? = null,
    val meta: Map<String, String>? = null
)

/**
 * Execution settings
 */
data class ExecutionSettings(
    val speed: Float = 1.0f, // 0.5 = slow, 1.0 = normal, 2.0 = fast
    val humanLikeMode: Boolean = true,
    val stopOnError: Boolean = true,
    val screenshotOnError: Boolean = false,
    val retryFailedActions: Boolean = false,
    val maxRetries: Int = 3
)

/**
 * Execution log entry
 */
data class ExecutionLogEntry(
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val level: LogLevel,
    val message: String,
    val actionIndex: Int? = null
)

/**
 * Log levels
 */
enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
    DEBUG
}

/**
 * Execution summary
 */
data class ExecutionSummary(
    val totalActions: Int,
    val completedActions: Int,
    val failedActions: Int,
    val skippedActions: Int,
    val totalDuration: Long,
    val startTime: Long,
    val endTime: Long? = null
)

/**
 * Action types
 */
object ActionTypes {
    const val CLICK = "click"
    const val INPUT = "input"
    const val SELECT = "select"
    const val NAVIGATE = "navigate"
    const val WAIT = "wait"
    const val SCROLL = "scroll"
    const val SCREENSHOT = "screenshot"
    const val ASSERT = "assert"
    const val SWITCH_FRAME = "switch_frame"
    const val RUN_SCRIPT = "run_script"

    fun getDisplayName(type: String): String = when (type) {
        CLICK -> "Click"
        INPUT -> "Type Input"
        SELECT -> "Select Option"
        NAVIGATE -> "Navigate"
        WAIT -> "Wait"
        SCROLL -> "Scroll"
        SCREENSHOT -> "Screenshot"
        ASSERT -> "Assert"
        SWITCH_FRAME -> "Switch Frame"
        RUN_SCRIPT -> "Run Script"
        else -> type.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Speed presets
 */
object SpeedPresets {
    val SLOW = 0.5f
    val NORMAL = 1.0f
    val FAST = 1.5f
    val VERY_FAST = 2.0f

    fun getLabel(speed: Float): String = when {
        speed <= 0.5f -> "Slow"
        speed <= 1.0f -> "Normal"
        speed <= 1.5f -> "Fast"
        else -> "Very Fast"
    }
}

/**
 * Selector types
 */
object SelectorTypes {
    const val ID = "id"
    const val CSS = "css"
    const val XPATH = "xpath"
    const val TEXT = "text"
    const val NONE = "none"
}
