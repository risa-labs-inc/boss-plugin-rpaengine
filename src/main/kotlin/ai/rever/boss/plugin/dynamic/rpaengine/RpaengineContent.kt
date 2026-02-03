package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Main content for RPA Engine panel
 */
@Composable
fun RpaengineContent(component: RpaengineComponent) {
    BossTheme {
        val selectedConfig by component.selectedConfig.collectAsState()
        val availableConfigs by component.availableConfigs.collectAsState()
        val executionStatus by component.executionStatus.collectAsState()
        val currentActionIndex by component.currentActionIndex.collectAsState()
        val executionResults by component.executionResults.collectAsState()
        val executionLogs by component.executionLogs.collectAsState()
        val executionSpeed by component.executionSpeed.collectAsState()
        val humanLikeMode by component.humanLikeMode.collectAsState()
        val stopOnError by component.stopOnError.collectAsState()
        val executionSummary by component.executionSummary.collectAsState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                item {
                    HeaderSection(
                        executionStatus = executionStatus,
                        onRefresh = { component.loadAvailableConfigurations() }
                    )
                }

                // Configuration selector
                item {
                    ConfigurationSelector(
                        availableConfigs = availableConfigs,
                        selectedConfig = selectedConfig,
                        onConfigSelected = { component.loadConfiguration(it) },
                        enabled = executionStatus == ExecutionStatus.IDLE || executionStatus == ExecutionStatus.ERROR,
                        formatTimestamp = { component.formatTimestamp(it) }
                    )
                }

                if (selectedConfig != null) {
                    // Execution controls
                    item {
                        ExecutionControls(
                            executionStatus = executionStatus,
                            executionSpeed = executionSpeed,
                            humanLikeMode = humanLikeMode,
                            stopOnError = stopOnError,
                            onSpeedChange = { component.setExecutionSpeed(it) },
                            onHumanModeChange = { component.setHumanLikeMode(it) },
                            onStopOnErrorChange = { component.setStopOnError(it) },
                            onStart = { component.startExecution() },
                            onPause = { component.pauseExecution() },
                            onStop = { component.stopExecution() },
                            onReset = { component.resetExecution() }
                        )
                    }

                    // Execution summary
                    executionSummary?.let { summary ->
                        item {
                            ExecutionSummaryCard(summary = summary)
                        }
                    }

                    // Action list with execution progress
                    item {
                        ActionListCard(
                            actions = selectedConfig!!.actions,
                            currentActionIndex = currentActionIndex,
                            executionResults = executionResults,
                            executionStatus = executionStatus
                        )
                    }

                    // Execution logs
                    if (executionLogs.isNotEmpty()) {
                        item {
                            ExecutionLogsCard(
                                logs = executionLogs,
                                onClear = { component.clearLogs() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(
    executionStatus: ExecutionStatus,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = "RPA Engine",
                    modifier = Modifier.size(24.dp),
                    tint = when (executionStatus) {
                        ExecutionStatus.EXECUTING -> Color(0xFF4CAF50)
                        ExecutionStatus.ERROR -> MaterialTheme.colors.error
                        ExecutionStatus.COMPLETED -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colors.primary
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "RPA Engine",
                        style = MaterialTheme.typography.h6,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        getStatusText(executionStatus),
                        style = MaterialTheme.typography.caption,
                        color = when (executionStatus) {
                            ExecutionStatus.EXECUTING -> Color(0xFF4CAF50)
                            ExecutionStatus.ERROR -> MaterialTheme.colors.error
                            ExecutionStatus.COMPLETED -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        }
                    )
                }
            }

            IconButton(
                onClick = onRefresh,
                enabled = executionStatus == ExecutionStatus.IDLE
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh Configurations",
                    tint = if (executionStatus == ExecutionStatus.IDLE)
                        MaterialTheme.colors.primary
                    else
                        MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun ConfigurationSelector(
    availableConfigs: List<ConfigFileInfo>,
    selectedConfig: RpaConfiguration?,
    onConfigSelected: (ConfigFileInfo) -> Unit,
    enabled: Boolean,
    formatTimestamp: (Long) -> String
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "RPA Configuration",
                style = MaterialTheme.typography.subtitle2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { expanded = true }
                        .border(
                            1.dp,
                            if (selectedConfig != null)
                                MaterialTheme.colors.primary.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        ),
                    color = MaterialTheme.colors.surface,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (selectedConfig != null)
                                    MaterialTheme.colors.primary
                                else
                                    MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedConfig?.name ?: if (availableConfigs.isEmpty())
                                    "No configurations found"
                                else
                                    "Select configuration...",
                                style = MaterialTheme.typography.body2
                            )
                        }

                        Icon(
                            if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = "Dropdown",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = expanded && availableConfigs.isNotEmpty(),
                    onDismissRequest = { expanded = false }
                ) {
                    availableConfigs.forEach { configFile ->
                        DropdownMenuItem(
                            onClick = {
                                onConfigSelected(configFile)
                                expanded = false
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        configFile.name,
                                        style = MaterialTheme.typography.body2
                                    )
                                    Text(
                                        "${configFile.actionCount} actions • ${formatTimestamp(configFile.lastModified)}",
                                        style = MaterialTheme.typography.caption,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selectedConfig != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${selectedConfig.actions.size} actions • ${selectedConfig.description}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ExecutionControls(
    executionStatus: ExecutionStatus,
    executionSpeed: Float,
    humanLikeMode: Boolean,
    stopOnError: Boolean,
    onSpeedChange: (Float) -> Unit,
    onHumanModeChange: (Boolean) -> Unit,
    onStopOnErrorChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        elevation = 1.dp,
        backgroundColor = when (executionStatus) {
            ExecutionStatus.EXECUTING -> Color(0xFF4CAF50).copy(alpha = 0.1f)
            ExecutionStatus.PAUSED -> Color(0xFFFF9800).copy(alpha = 0.1f)
            ExecutionStatus.ERROR -> MaterialTheme.colors.error.copy(alpha = 0.1f)
            else -> MaterialTheme.colors.surface
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Start/Resume button
                Button(
                    onClick = onStart,
                    enabled = executionStatus == ExecutionStatus.IDLE ||
                            executionStatus == ExecutionStatus.PAUSED ||
                            executionStatus == ExecutionStatus.ERROR,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (executionStatus == ExecutionStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (executionStatus == ExecutionStatus.PAUSED) "Resume" else "Start")
                }

                // Pause button
                Button(
                    onClick = onPause,
                    enabled = executionStatus == ExecutionStatus.EXECUTING,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFFFF9800)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pause")
                }

                // Stop button
                Button(
                    onClick = onStop,
                    enabled = executionStatus == ExecutionStatus.EXECUTING ||
                            executionStatus == ExecutionStatus.PAUSED,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop")
                }

                // Reset button
                IconButton(
                    onClick = onReset,
                    enabled = executionStatus == ExecutionStatus.COMPLETED ||
                            executionStatus == ExecutionStatus.ERROR
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset",
                        tint = if (executionStatus == ExecutionStatus.COMPLETED ||
                            executionStatus == ExecutionStatus.ERROR)
                            MaterialTheme.colors.primary
                        else
                            MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Speed control
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Speed:",
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.width(50.dp)
                )

                Slider(
                    value = executionSpeed,
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    enabled = executionStatus == ExecutionStatus.IDLE,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    SpeedPresets.getLabel(executionSpeed),
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.width(70.dp),
                    textAlign = TextAlign.End
                )
            }

            // Human-like mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = executionStatus == ExecutionStatus.IDLE) {
                        onHumanModeChange(!humanLikeMode)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = humanLikeMode,
                    onCheckedChange = onHumanModeChange,
                    enabled = executionStatus == ExecutionStatus.IDLE
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Human-like behavior (random delays)",
                    style = MaterialTheme.typography.body2
                )
            }

            // Stop on error toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = executionStatus == ExecutionStatus.IDLE) {
                        onStopOnErrorChange(!stopOnError)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = stopOnError,
                    onCheckedChange = onStopOnErrorChange,
                    enabled = executionStatus == ExecutionStatus.IDLE
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Stop execution on error",
                    style = MaterialTheme.typography.body2
                )
            }
        }
    }
}

@Composable
private fun ExecutionSummaryCard(summary: ExecutionSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Execution Summary",
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                SummaryItem(
                    label = "Total",
                    value = summary.totalActions.toString(),
                    color = MaterialTheme.colors.onSurface
                )
                SummaryItem(
                    label = "Completed",
                    value = summary.completedActions.toString(),
                    color = Color(0xFF4CAF50)
                )
                SummaryItem(
                    label = "Failed",
                    value = summary.failedActions.toString(),
                    color = if (summary.failedActions > 0) MaterialTheme.colors.error else MaterialTheme.colors.onSurface
                )
                SummaryItem(
                    label = "Duration",
                    value = "${summary.totalDuration / 1000}s",
                    color = MaterialTheme.colors.onSurface
                )
            }

            // Progress bar
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = if (summary.totalActions > 0)
                    (summary.completedActions + summary.failedActions).toFloat() / summary.totalActions
                else 0f,
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (summary.failedActions > 0) MaterialTheme.colors.error else Color(0xFF4CAF50)
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ActionListCard(
    actions: List<RpaActionConfig>,
    currentActionIndex: Int,
    executionResults: List<ActionExecutionResult>,
    executionStatus: ExecutionStatus
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp
    ) {
        Column {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colors.primary.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Actions",
                        style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.Bold
                    )

                    if (executionResults.isNotEmpty()) {
                        Text(
                            "${executionResults.count { it.success }}/${actions.size} completed",
                            style = MaterialTheme.typography.caption,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }

            // Action items
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions.forEachIndexed { index, action ->
                    ActionItem(
                        action = action,
                        index = index,
                        isCurrent = index == currentActionIndex && executionStatus == ExecutionStatus.EXECUTING,
                        result = executionResults.find { it.actionIndex == index }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionItem(
    action: RpaActionConfig,
    index: Int,
    isCurrent: Boolean,
    result: ActionExecutionResult?
) {
    val backgroundColor = when {
        isCurrent -> MaterialTheme.colors.primary.copy(alpha = 0.2f)
        result?.success == true -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        result?.success == false -> MaterialTheme.colors.error.copy(alpha = 0.1f)
        else -> MaterialTheme.colors.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = backgroundColor,
        shape = RoundedCornerShape(6.dp),
        elevation = if (isCurrent) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = when {
                    isCurrent -> MaterialTheme.colors.primary
                    result?.success == true -> Color(0xFF4CAF50)
                    result?.success == false -> MaterialTheme.colors.error
                    else -> MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
                }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        isCurrent -> Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Executing",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        result?.success == true -> Icon(
                            Icons.Default.Check,
                            contentDescription = "Success",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        result?.success == false -> Icon(
                            Icons.Default.Close,
                            contentDescription = "Failed",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        else -> Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Action details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    action.name,
                    style = MaterialTheme.typography.body1,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectorTypeBadge(action.type)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        action.selector.value ?: action.value ?: "",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (result?.error != null) {
                    Text(
                        result.error,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.error
                    )
                }

                if (result?.duration != null && result.duration > 0) {
                    Text(
                        "${result.duration}ms",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Action type icon
            Icon(
                imageVector = getActionIcon(action.type),
                contentDescription = action.type,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun SelectorTypeBadge(type: String) {
    val (bgColor, textColor) = when (type) {
        ActionTypes.CLICK -> Color(0xFF2196F3).copy(alpha = 0.2f) to Color(0xFF2196F3)
        ActionTypes.INPUT -> Color(0xFF4CAF50).copy(alpha = 0.2f) to Color(0xFF4CAF50)
        ActionTypes.NAVIGATE -> Color(0xFFFF9800).copy(alpha = 0.2f) to Color(0xFFFF9800)
        ActionTypes.WAIT -> Color(0xFF9C27B0).copy(alpha = 0.2f) to Color(0xFF9C27B0)
        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.1f) to MaterialTheme.colors.onSurface
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = ActionTypes.getDisplayName(type),
            style = MaterialTheme.typography.caption,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ExecutionLogsCard(
    logs: List<ExecutionLogEntry>,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 1.dp
    ) {
        Column {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colors.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Execution Log",
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.Bold
                    )

                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Clear", style = MaterialTheme.typography.caption)
                    }
                }
            }

            // Log entries
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                logs.takeLast(20).reversed().forEach { log ->
                    LogEntry(log)
                }
            }
        }
    }
}

@Composable
private fun LogEntry(log: ExecutionLogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = when (log.level) {
                LogLevel.SUCCESS -> Icons.Default.CheckCircle
                LogLevel.ERROR -> Icons.Default.Error
                LogLevel.WARNING -> Icons.Default.Warning
                LogLevel.INFO -> Icons.Default.Info
                LogLevel.DEBUG -> Icons.Default.BugReport
            },
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = when (log.level) {
                LogLevel.SUCCESS -> Color(0xFF4CAF50)
                LogLevel.ERROR -> MaterialTheme.colors.error
                LogLevel.WARNING -> Color(0xFFFF9800)
                LogLevel.INFO -> MaterialTheme.colors.primary
                LogLevel.DEBUG -> MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            log.message,
            style = MaterialTheme.typography.caption,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
        )
    }
}

private fun getActionIcon(type: String) = when (type) {
    ActionTypes.CLICK -> Icons.Default.TouchApp
    ActionTypes.INPUT -> Icons.Default.Keyboard
    ActionTypes.SELECT -> Icons.Default.ArrowDropDown
    ActionTypes.NAVIGATE -> Icons.Default.Navigation
    ActionTypes.SCROLL -> Icons.Default.SwapVert
    ActionTypes.WAIT -> Icons.Default.Schedule
    ActionTypes.SCREENSHOT -> Icons.Default.PhotoCamera
    ActionTypes.ASSERT -> Icons.Default.CheckCircle
    else -> Icons.Default.Code
}

private fun getStatusText(status: ExecutionStatus): String {
    return when (status) {
        ExecutionStatus.IDLE -> "Ready to execute"
        ExecutionStatus.LOADING -> "Loading configuration..."
        ExecutionStatus.EXECUTING -> "Executing actions..."
        ExecutionStatus.PAUSED -> "Execution paused"
        ExecutionStatus.COMPLETED -> "Execution completed"
        ExecutionStatus.ERROR -> "Execution failed"
    }
}
