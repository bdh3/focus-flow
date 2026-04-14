package com.focusflow.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusflow.app.model.Task
import com.focusflow.app.model.TimeBlock
import com.focusflow.app.util.BlockType
import com.focusflow.app.ui.viewmodel.SchedulerViewModel
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(viewModel: SchedulerViewModel, onNavigateToCalendar: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var isTaskListVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Focus Flow") }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    val totalSeconds = uiState.timeBlocks.sumOf { it.durationMinutes * 60L }.toInt()
                    val progress = if (totalSeconds > 0) uiState.totalRemainingSeconds.toFloat() / totalSeconds else 0f
                    val currentBlock = uiState.timeBlocks.getOrNull(uiState.currentBlockIndex)

                    TimerHeader(
                        totalRemainingSeconds = uiState.totalRemainingSeconds,
                        remainingSeconds = uiState.remainingSeconds,
                        isRunning = uiState.isRunning,
                        isTimerActive = uiState.isTimerActive,
                        progress = progress,
                        blockType = currentBlock?.type ?: BlockType.FOCUS,
                        selectedTaskTitle = uiState.selectedTaskTitle,
                        onToggleTimer = { viewModel.toggleTimer() },
                        onStopTimer = { viewModel.stopTimer() },
                        onSkip = { viewModel.skipBlock() },
                        selectedTaskId = uiState.selectedTaskId,
                        sessionTotalMinutes = uiState.sessionTotalMinutes,
                        onSelectTaskClick = { isTaskListVisible = true },
                        onCancelTask = { viewModel.selectTask(null) }
                    )
                }

                item {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "현재 세션 흐름",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            uiState.timeBlocks.forEachIndexed { index, block ->
                                Box(
                                    modifier = Modifier
                                        .weight(block.durationMinutes.toFloat())
                                        .fillMaxHeight()
                                        .background(
                                            color = when {
                                                index < uiState.currentBlockIndex -> MaterialTheme.colorScheme.outlineVariant
                                                index == uiState.currentBlockIndex -> {
                                                    if (block.type == BlockType.FOCUS) MaterialTheme.colorScheme.primary 
                                                    else MaterialTheme.colorScheme.tertiary
                                                }
                                                block.type == BlockType.FOCUS -> MaterialTheme.colorScheme.primaryContainer
                                                else -> MaterialTheme.colorScheme.tertiaryContainer
                                            },
                                            shape = MaterialTheme.shapes.extraSmall
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // [v1.8.0] Task Selection Dialog (Overlay Layer)
        if (isTaskListVisible && !uiState.isRunning) {
            Dialog(
                onDismissRequest = { isTaskListVisible = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.6f),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "오늘 작업 리스트",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { isTaskListVisible = false }) {
                                Icon(Icons.Default.Close, contentDescription = "닫기")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (uiState.tasks.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "오늘 추가된 작업이 없습니다.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    TextButton(onClick = {
                                        isTaskListVisible = false
                                        viewModel.selectDate(System.currentTimeMillis())
                                        onNavigateToCalendar()
                                    }) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("작업 추가하기")
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(uiState.tasks, key = { it.id }) { task ->
                                    val isSelected = uiState.selectedTaskId == task.id
                                    TaskItem(
                                        task = task,
                                        isSelected = isSelected,
                                        onSelect = { 
                                            viewModel.selectTask(task.id)
                                            isTaskListVisible = false
                                        },
                                        onToggle = { viewModel.toggleTaskCompletion(task) },
                                        onDelete = { viewModel.deleteTask(task) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimerHeader(
    totalRemainingSeconds: Int,
    remainingSeconds: Int,
    isRunning: Boolean,
    isTimerActive: Boolean,
    progress: Float,
    blockType: BlockType,
    selectedTaskTitle: String?,
    onToggleTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onSkip: () -> Unit,
    selectedTaskId: String?,
    sessionTotalMinutes: Int,
    onSelectTaskClick: () -> Unit,
    onCancelTask: () -> Unit
) {
    val totalMin = totalRemainingSeconds / 60
    val totalSec = totalRemainingSeconds % 60
    
    val hours = totalMin / 60
    val mins = totalMin % 60
    val timeText = if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, mins, totalSec)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", mins, totalSec)
    }

    val blockTotalMin = remainingSeconds / 60
    val blockSec = remainingSeconds % 60
    val blockHours = blockTotalMin / 60
    val blockMins = blockTotalMin % 60
    val blockTimeText = if (blockHours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", blockHours, blockMins, blockSec)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", blockMins, blockSec)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // [v1.8.0] Minimalist Task Selection
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .wrapContentSize()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = !isRunning) { onSelectTaskClick() }
                .padding(vertical = 4.dp, horizontal = 12.dp)
        ) {
            Text(
                text = selectedTaskTitle ?: "작업을 선택하세요",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (selectedTaskId == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
            )
            
            if (selectedTaskId != null && !isRunning) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    onClick = onCancelTask,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(18.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "선택 취소",
                        modifier = Modifier.padding(3.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!isRunning) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Main Timer Circle
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(240.dp),
                strokeWidth = 8.dp,
                color = if (blockType == BlockType.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                strokeCap = StrokeCap.Round
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = blockTimeText,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-2).sp
                    ),
                    maxLines = 1
                )
                Text(
                    text = if (blockType == BlockType.FOCUS) "FOCUS" else "REST",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (blockType == BlockType.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Session Total Remaining (Subtle)
        Text(
            text = if (isRunning || totalRemainingSeconds > 0) "Total $timeText left" else "Ready",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Modern Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(0.8f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isTimerActive) {
                IconButton(
                    onClick = onStopTimer,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.width(24.dp))
            }

            FloatingActionButton(
                onClick = onToggleTimer,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
            }

            if (isRunning) {
                Spacer(modifier = Modifier.width(24.dp))
                IconButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Skip")
                }
            } else if (isTimerActive) {
                // Placeholder to keep play button centered
                Spacer(modifier = Modifier.width(72.dp))
            }
        }
    }
}

@Composable
fun TaskItem(
    task: Task, 
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit, 
    onDelete: () -> Unit
) {
    // 시작 시간과 종료 시간 계산 (예: 14:00 ~ 15:15)
    val timeRangeText = if (task.startTimeMillis > 0) {
        val startCal = Calendar.getInstance().apply { timeInMillis = task.startTimeMillis }
        val endCal = Calendar.getInstance().apply { 
            timeInMillis = task.startTimeMillis 
            add(Calendar.MINUTE, task.durationMinutes)
        }
        String.format(Locale.getDefault(), "%02d:%02d ~ %02d:%02d", 
            startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE),
            endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE)
        )
    } else {
        "--:--"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                task.isCompleted -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp, 
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(end = 4.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    maxLines = 1
                )
                Text(
                    text = timeRangeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}
