package com.example.adhdblockscheduler.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adhdblockscheduler.model.BlockType
import com.example.adhdblockscheduler.model.Task
import com.example.adhdblockscheduler.model.TimeBlock
import com.example.adhdblockscheduler.ui.viewmodel.SchedulerViewModel
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                val totalSeconds = uiState.timeBlocks.sumOf { it.durationMinutes * 60 }
                val progress = if (totalSeconds > 0) uiState.totalRemainingSeconds.toFloat() / totalSeconds else 0f
                val currentBlock = uiState.timeBlocks.getOrNull(uiState.currentBlockIndex)

                TimerHeader(
                    remainingSeconds = uiState.totalRemainingSeconds,
                    currentBlockRemaining = uiState.remainingSeconds,
                    isRunning = uiState.isRunning,
                    progress = progress,
                    blockType = currentBlock?.type ?: BlockType.FOCUS,
                    selectedTaskTitle = uiState.tasks.find { it.id == uiState.selectedTaskId }?.title,
                    onToggleTimer = { viewModel.toggleTimer() },
                    onStopTimer = { viewModel.stopTimer() },
                    onSkip = { viewModel.skipBlock() }
                )
            }

            item {
                Column {
                    Text(
                        text = "현재 세션 흐름",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
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
                                            index == uiState.currentBlockIndex -> MaterialTheme.colorScheme.primary
                                            block.type == BlockType.FOCUS -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.secondaryContainer
                                        },
                                        shape = MaterialTheme.shapes.extraSmall
                                    )
                            )
                        }
                    }
                }
            }

            item {
                if (!uiState.isRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "작업 리스트",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onNavigateToCalendar) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("추가")
                        }
                    }
                }
            }

            items(uiState.tasks) { task ->
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
    remainingSeconds: Int,
    currentBlockRemaining: Int,
    isRunning: Boolean,
    progress: Float,
    blockType: BlockType,
    selectedTaskTitle: String?,
    onToggleTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onSkip: () -> Unit
) {
    val totalMin = remainingSeconds / 60
    val totalSec = remainingSeconds % 60
    val timeText = String.format(Locale.getDefault(), "%02d:%02d", totalMin, totalSec)

    val blockMin = currentBlockRemaining / 60
    val blockSec = currentBlockRemaining % 60
    val blockTimeText = String.format(Locale.getDefault(), "%02d:%02d", blockMin, blockSec)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
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
                text = "현재 블록 $blockTimeText 남음",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(220.dp),
                    strokeWidth = 12.dp,
                    color = if (blockType == BlockType.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "전체 남은 시간",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleTimer,
                    modifier = Modifier.weight(1.2f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    val isSessionActive = remainingSeconds > 0 && remainingSeconds < 3600*24
                    Text(
                        when {
                            isRunning -> "일시정지"
                            isSessionActive -> "재개"
                            else -> "시작"
                        },
                        maxLines = 1
                    )
                }
                
                if (isRunning || (remainingSeconds > 0 && remainingSeconds < 3600*24)) {
                    OutlinedButton(
                        onClick = onStopTimer,
                        modifier = Modifier.weight(0.7f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("중지", maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(0.8f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(text = "넘기기", maxLines = 1)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() }
            )
            Text(
                text = task.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            )
            if (!isSelected) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    text = "선택됨",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}
