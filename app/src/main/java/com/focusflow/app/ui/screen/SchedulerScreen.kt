package com.focusflow.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

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
                    progress = progress,
                    blockType = currentBlock?.type ?: BlockType.FOCUS,
                    selectedTaskTitle = uiState.selectedTaskTitle,
                    onToggleTimer = { viewModel.toggleTimer() },
                    onStopTimer = { viewModel.stopTimer() },
                    onSkip = { viewModel.skipBlock() },
                    selectedTaskId = uiState.selectedTaskId,
                    sessionTotalMinutes = uiState.sessionTotalMinutes
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

            if (!uiState.isRunning) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "오늘 작업 리스트",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        TextButton(onClick = {
                            viewModel.selectDate(System.currentTimeMillis())
                            onNavigateToCalendar()
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("추가")
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = "현재 진행 중인 작업",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                
                // [v1.7.3-patch1] 작업을 선택하지 않고 "독립 세션"으로 시작한 경우 가상 아이템 표시
                if (uiState.isRunning && uiState.selectedTaskId == null) {
                    item {
                        TaskItem(
                            task = Task(
                                id = "independent_focus",
                                title = "독립 세션",
                                durationMinutes = uiState.sessionTotalMinutes,
                                isCompleted = false
                            ),
                            isSelected = true,
                            onSelect = {},
                            onToggle = {},
                            onDelete = {}
                        )
                    }
                }
            }

            val tasksToShow = uiState.tasks

            if (tasksToShow.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "오늘 추가된 작업이 없습니다.\n상단의 '추가' 버튼을 눌러 등록하세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            items(tasksToShow, key = { it.id }) { task ->
                val isSelected = uiState.selectedTaskId == task.id
                var showDeleteDialog by remember { mutableStateOf(false) }

                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text("작업 삭제") },
                        text = { Text("'${task.title}' 작업을 삭제하시겠습니까? 캘린더에 등록된 일정도 함께 삭제됩니다.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.deleteTask(task)
                                    showDeleteDialog = false
                                }
                            ) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) { Text("취소") }
                        }
                    )
                }

                if (uiState.isRunning) {
                    if (isSelected) {
                        TaskItem(
                            task = task,
                            isSelected = true,
                            onSelect = {},
                            onToggle = {},
                            onDelete = {}
                        )
                    }
                } else {
                    TaskItem(
                        task = task,
                        isSelected = isSelected,
                        onSelect = { viewModel.selectTask(task.id) },
                        onToggle = { viewModel.toggleTaskCompletion(task) },
                        onDelete = { showDeleteDialog = true }
                    )
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
    progress: Float,
    blockType: BlockType,
    selectedTaskTitle: String?,
    onToggleTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onSkip: () -> Unit,
    selectedTaskId: String?,
    sessionTotalMinutes: Int
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = selectedTaskTitle ?: "작업을 선택하세요",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            Text(
                text = if (isRunning || totalRemainingSeconds > 0) "현재 블록 $blockTimeText 남음" else "준비 완료",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(220.dp),
                    strokeWidth = 10.dp,
                    color = if (blockType == BlockType.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeText,
                        style = if (timeText.length > 5) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = if (blockType == BlockType.FOCUS) "전체 남은 시간" else "휴식 중",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (blockType == BlockType.FOCUS) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // [v1.7.3] 작업이 선택되지 않았을 때는 무조건 '시작' 버튼만 표시
                val isTaskSelected = selectedTaskTitle != null

                Button(
                    onClick = onToggleTimer,
                    modifier = Modifier.weight(if (isTaskSelected && (isRunning || totalRemainingSeconds > 0)) 1.2f else 1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    val isSessionActive = totalRemainingSeconds > 0
                    val totalSecs = sessionTotalMinutes * 60
                    val isInitialState = totalRemainingSeconds >= totalSecs || totalRemainingSeconds == 0
                    
                    Icon(
                        if (isRunning) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            isRunning -> "일시정지"
                            !isTaskSelected || isInitialState -> "시작"
                            isSessionActive -> "재개"
                            else -> "시작"
                        },
                        maxLines = 1
                    )
                }
                
                // 작업이 선택되었고 활성 세션이 있을 때만 중지/넘기기 표시
                if (isTaskSelected && (isRunning || totalRemainingSeconds > 0)) {
                    OutlinedButton(
                        onClick = onStopTimer,
                        modifier = Modifier.weight(0.7f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("중지", maxLines = 1)
                    }

                    if (isRunning) {
                        OutlinedButton(
                            onClick = onSkip,
                            modifier = Modifier.weight(0.8f),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(text = "넘기기", maxLines = 1)
                        }
                    }
                }
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
