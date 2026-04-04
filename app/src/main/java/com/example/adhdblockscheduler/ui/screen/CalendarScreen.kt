package com.example.adhdblockscheduler.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adhdblockscheduler.model.ScheduleBlock
import com.example.adhdblockscheduler.ui.viewmodel.SchedulerViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: SchedulerViewModel,
    onNavigateToTimer: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduleBlock?>(null) }
    var isMonthlyView by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (isMonthlyView) formatMonth(selectedDate) else formatDate(selectedDate),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.selectDate(System.currentTimeMillis())
                    }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "오늘")
                    }
                    IconButton(onClick = { isMonthlyView = !isMonthlyView }) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = if (isMonthlyView) "일간 보기" else "월간 보기",
                            tint = if (!isMonthlyView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = {
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = selectedDate
                            add(if (isMonthlyView) Calendar.MONTH else Calendar.DAY_OF_YEAR, -1)
                        }
                        viewModel.selectDate(cal.timeInMillis)
                    }) {
                        Text("<")
                    }
                    IconButton(onClick = {
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = selectedDate
                            add(if (isMonthlyView) Calendar.MONTH else Calendar.DAY_OF_YEAR, 1)
                        }
                        viewModel.selectDate(cal.timeInMillis)
                    }) {
                        Text(">")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (isMonthlyView) {
                MonthlyCalendarView(
                    selectedDate = selectedDate,
                    onDateSelected = { 
                        viewModel.selectDate(it)
                        isMonthlyView = false
                    }
                )
            } else {
                DailyTimelineView(
                    uiState = uiState,
                    selectedDate = selectedDate,
                    onAddSchedule = { _, _, _ ->
                        showAddTaskDialog = true
                    },
                    onLoadSchedule = { schedule ->
                        editingSchedule = schedule
                    },
                    onToggleBlock = { blockTime ->
                        viewModel.toggleBlock(blockTime)
                    },
                    onClearSelection = { viewModel.clearSelectedBlocks() }
                )
            }
        }
    }

    if (showAddTaskDialog) {
        // ... (기존 추가 다이얼로그)
    }

    if (editingSchedule != null) {
        var taskTitle by remember(editingSchedule) { mutableStateOf(editingSchedule?.taskTitle ?: "") }
        
        AlertDialog(
            onDismissRequest = { editingSchedule = null },
            title = { Text("세션 수정") },
            text = {
                Column {
                    TextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it },
                        label = { Text("작업명") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            editingSchedule?.let { viewModel.deleteSchedule(it) }
                            editingSchedule = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("삭제")
                    }
                    Button(onClick = {
                        editingSchedule?.let { viewModel.updateSchedule(it, taskTitle) }
                        editingSchedule = null
                    }) {
                        Text("수정")
                    }
                    Button(onClick = {
                        editingSchedule?.let { 
                            viewModel.loadScheduledSession(it)
                            onNavigateToTimer()
                        }
                        editingSchedule = null
                    }) {
                        Text("수행")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSchedule = null }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun MonthlyCalendarView(
    selectedDate: Long,
    onDateSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = selectedDate
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val monthStartDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    
    val days = mutableListOf<Long?>()
    for (i in 0 until monthStartDayOfWeek) days.add(null)
    for (i in 1..daysInMonth) {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, i)
        days.add(cal.timeInMillis)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    color = if (day == "일") Color.Red else if (day == "토") Color.Blue else Color.Unspecified
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxSize()
        ) {
            items(days) { dateMillis ->
                if (dateMillis != null) {
                    val isSelected = isSameDay(dateMillis, selectedDate)
                    val isToday = isToday(dateMillis)
                    val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(4.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else if (isToday) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .clickable { onDateSelected(dateMillis) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cal.get(Calendar.DAY_OF_MONTH).toString(),
                            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.aspectRatio(1f))
                }
            }
        }
    }
}

@Composable
fun DailyTimelineView(
    uiState: com.example.adhdblockscheduler.ui.viewmodel.SchedulerUiState,
    selectedDate: Long,
    onAddSchedule: (Int, Int, Int) -> Unit,
    onLoadSchedule: (ScheduleBlock) -> Unit,
    onToggleBlock: (Long) -> Unit,
    onClearSelection: () -> Unit
) {
    val listState = rememberLazyListState()
    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    
    // 현재 시간 위치로 자동 스크롤
    LaunchedEffect(Unit) {
        listState.scrollToItem(currentHour)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.selectedBlocks.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("${uiState.selectedBlocks.size}개 블록 선택됨", fontWeight = FontWeight.Bold)
                        Text("총 ${uiState.selectedBlocks.size * 15}분 세션", style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        TextButton(onClick = onClearSelection) { Text("취소") }
                        Button(onClick = { onAddSchedule(0, 0, 0) }) { Text("세션 생성") }
                    }
                }
            }
        }
        
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items((0..23).toList()) { hour ->
                HourRow(
                    hour = hour,
                    selectedDate = selectedDate,
                    uiState = uiState,
                    onToggleBlock = onToggleBlock,
                    onLoadSchedule = onLoadSchedule
                )
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun HourRow(
    hour: Int,
    selectedDate: Long,
    uiState: com.example.adhdblockscheduler.ui.viewmodel.SchedulerUiState,
    onToggleBlock: (Long) -> Unit,
    onLoadSchedule: (ScheduleBlock) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = String.format(Locale.getDefault(), "%02d:00", hour),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(45.dp),
            color = Color.Gray
        )
        
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            (0..3).forEach { quarter ->
                val blockStartTime = Calendar.getInstance().apply {
                    timeInMillis = selectedDate
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, quarter * 15)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                
                val schedule = uiState.dailySchedules.find { 
                    val sCal = Calendar.getInstance().apply { timeInMillis = it.startTimeMillis }
                    val sStart = sCal.timeInMillis
                    val sEnd = sStart + it.durationMinutes * 60 * 1000L
                    blockStartTime >= sStart && blockStartTime < sEnd
                }

                val isSelected = uiState.selectedBlocks.contains(blockStartTime)
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(45.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            when {
                                schedule != null -> if (schedule.isCompleted) MaterialTheme.colorScheme.surfaceVariant 
                                                   else MaterialTheme.colorScheme.secondaryContainer
                                isSelected -> MaterialTheme.colorScheme.primary
                                else -> Color.LightGray.copy(alpha = 0.2f)
                            }
                        )
                        .clickable { 
                            if (schedule != null) onLoadSchedule(schedule)
                            else onToggleBlock(blockStartTime)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (schedule != null && isSameTime(blockStartTime, schedule.startTimeMillis)) {
                        Text(schedule.taskTitle, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
                    } else if (isSelected) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

private fun isSameDay(m1: Long, m2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = m1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = m2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isToday(millis: Long): Boolean {
    val cal1 = Calendar.getInstance()
    val cal2 = Calendar.getInstance().apply { timeInMillis = millis }
    return isSameDay(cal1.timeInMillis, cal2.timeInMillis)
}

private fun isSameTime(m1: Long, m2: Long): Boolean {
    return (m1 / 60000) == (m2 / 60000)
}

private fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("MM월 dd일 (EEE)", Locale.KOREA)
    return sdf.format(Date(millis))
}

private fun formatMonth(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy년 MM월", Locale.KOREA)
    return sdf.format(Date(millis))
}
