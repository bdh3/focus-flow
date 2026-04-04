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
fun SchedulerScreen(viewModel: SchedulerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddTaskDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus Flow") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 타이머 섹션
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

            Spacer(modifier = Modifier.height(24.dp))

            // 집중 흐름 인디케이터 (단순화)
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

            Spacer(modifier = Modifier.height(24.dp))

            // 진행한 작업 상태 (스크롤 가능하게 수정)
            if (!uiState.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "진행할 작업 선택",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { showAddTaskDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("추가")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(uiState.tasks) { task ->
                    val isSelected = uiState.selectedTaskId == task.id
                    // 타이머 실행 중일 때는 현재 선택된 Task만 보여줌
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
                            onDelete = { viewModel.deleteTask(task) }
                        )
                    }
                }
            }
        }
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onAdd = { title ->
                viewModel.addTask(title)
                showAddTaskDialog = false
            }
        )
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleTimer,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    val isSessionActive = remainingSeconds > 0 && remainingSeconds < 3600*24
                    Text(
                        when {
                            isRunning -> "일시정지"
                            isSessionActive -> "재개"
                            else -> "시작"
                        }
                    )
                }
                
                if (isRunning || remainingSeconds > 0) {
                    OutlinedButton(
                        onClick = onStopTimer,
                        modifier = Modifier.weight(0.6f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("중지")
                    }
                }

                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(0.6f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("넘기기")
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

@Composable
fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새로운 할 일") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("할 일을 입력하세요") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onAdd(text) },
                enabled = text.isNotBlank()
            ) {
                Text("추가")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
