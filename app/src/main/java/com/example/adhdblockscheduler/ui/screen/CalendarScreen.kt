package com.example.adhdblockscheduler.ui.screen

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adhdblockscheduler.model.ScheduleBlock
import com.example.adhdblockscheduler.ui.viewmodel.SchedulerUiState
import com.example.adhdblockscheduler.ui.viewmodel.SchedulerViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

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
    val isMonthlyView = uiState.isCalendarMonthlyView
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearSelectedBlocks()
        }
    }

    val datePickerDialog = remember(selectedDate) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        DatePickerDialog(
            context,
            { _, y, m, d ->
                val newCal = Calendar.getInstance().apply {
                    set(y, m, d)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                viewModel.selectDate(newCal.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { datePickerDialog.show() } // 연도 선택을 위한 클릭 (요구사항 2번)
                    ) {
                        Text(
                            text = if (isMonthlyView) formatMonth(selectedDate) else formatDate(selectedDate),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.selectDate(System.currentTimeMillis())
                    }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "오늘")
                    }
                    IconButton(onClick = { viewModel.setCalendarMonthlyView(!isMonthlyView) }) {
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
                    allSchedules = uiState.allSchedules, // 전체 일정 전달 (요구사항 1번)
                    onDateSelected = { 
                        viewModel.selectDate(it)
                        viewModel.setCalendarMonthlyView(false)
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
        var taskTitle by remember { mutableStateOf("") }
        var isCycleMode by remember { mutableStateOf(false) } // 기본값은 연속 집중
        val totalMin = uiState.selectedBlocks.size * 15
        
        val divisors = remember(totalMin) {
            (1..totalMin).filter { totalMin % it == 0 }
        }

        fun getValidRest(focus: Int, currentRest: Int): Int {
            val possibleSums = divisors.filter { it > focus }
            if (possibleSums.isEmpty()) return totalMin - focus
            return possibleSums.minByOrNull { abs((it - focus) - currentRest) }?.let { it - focus } ?: (totalMin - focus)
        }

        var localInterval by remember { 
            mutableIntStateOf(15) 
        }
        var localRestMinutes by remember { 
            mutableIntStateOf(0) 
        }

        AlertDialog(
            onDismissRequest = { showAddTaskDialog = false },
            title = { 
                Column {
                    Text("세션 생성", style = MaterialTheme.typography.headlineSmall)
                    Text("${totalMin}분 집중 계획", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 1. 작업명 입력
                    OutlinedTextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it },
                        placeholder = { Text("예: 딥러닝 논문 읽기") },
                        label = { Text("작업 내용") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium
                    )
                    
                    // 2. 모드 선택 (탭 스타일)
                    Column {
                        Text("집중 방식", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (!isCycleMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { 
                                        isCycleMode = false
                                        localInterval = 15
                                        localRestMinutes = 0
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "연속 집중", 
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (!isCycleMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (isCycleMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { 
                                        isCycleMode = true
                                        localInterval = 25.coerceAtMost(totalMin - 5).coerceAtLeast(5)
                                        localRestMinutes = getValidRest(localInterval, 5)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "인터벌 사이클", 
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isCycleMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 3. 상세 설정 영역
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (!isCycleMode) {
                                // 연속 집중 모드
                                Text("알람 주기 (일정 시간마다 리마인드)", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(5, 10, 15, 30).forEach { interval ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .background(if (localInterval == interval) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                                .clickable { localInterval = interval },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "${interval}분",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (localInterval == interval) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 인터벌 사이클 모드
                                Text("빠른 프리셋", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                    AssistChip(
                                        onClick = { 
                                            localInterval = 25
                                            localRestMinutes = getValidRest(25, 5)
                                        },
                                        label = { Text("25/5", fontSize = 12.sp) },
                                        enabled = totalMin >= 30,
                                        leadingIcon = { Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                    AssistChip(
                                        onClick = { 
                                            localInterval = 50
                                            localRestMinutes = getValidRest(50, 10)
                                        },
                                        label = { Text("50/10", fontSize = 12.sp) },
                                        enabled = totalMin >= 60,
                                        leadingIcon = { Icon(imageVector = Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                
                                Text("상세 값 수정", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(8.dp))
                                
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("집중: ${localInterval}분", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                                        Slider(
                                            value = localInterval.toFloat(),
                                            onValueChange = { 
                                                val snapped = (it.toInt() / 5) * 5
                                                localInterval = snapped.coerceIn(5, totalMin - 1)
                                                localRestMinutes = getValidRest(localInterval, localRestMinutes)
                                            },
                                            valueRange = 5f..(totalMin - 1).coerceAtLeast(5).toFloat(),
                                            modifier = Modifier.weight(1f),
                                            steps = if (totalMin > 10) (totalMin - 6) / 5 else 0
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("휴식: ${localRestMinutes}분", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                                        Slider(
                                            value = localRestMinutes.toFloat(),
                                            onValueChange = { 
                                                val requestedRest = it.toInt().coerceAtLeast(1)
                                                val targetSum = divisors.filter { d -> d > localInterval }
                                                    .minByOrNull { d -> abs((d - localInterval) - requestedRest) } ?: totalMin
                                                localRestMinutes = targetSum - localInterval
                                            },
                                            valueRange = 1f..(totalMin - localInterval).coerceAtLeast(1).toFloat(),
                                            modifier = Modifier.weight(1f),
                                            steps = (totalMin - localInterval - 1).coerceAtLeast(0)
                                        )
                                    }
                                    Text(
                                        "* 전체 시간의 약수에 맞춰 자동 보정됩니다.", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. 미리보기
                    Column {
                        Text("블록 구조 (${totalMin}분)", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            val rest = if (isCycleMode) localRestMinutes else 0
                            
                            if (rest <= 0) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary))
                            } else {
                                var current = 0
                                while (current < totalMin) {
                                    val fWidth = Math.min(localInterval, totalMin - current).toFloat() / totalMin
                                    if (fWidth > 0) Box(Modifier.weight(fWidth).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                                    current += localInterval
                                    if (current < totalMin) {
                                        val rWidth = Math.min(rest, totalMin - current).toFloat() / totalMin
                                        if (rWidth > 0) Box(Modifier.weight(rWidth).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
                                        current += rest
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val duration = totalMin
                        val firstBlock = uiState.selectedBlocks.minOrNull() ?: System.currentTimeMillis()
                        val calendar = Calendar.getInstance().apply { timeInMillis = firstBlock }
                        
                        viewModel.addSchedule(
                            taskTitle = taskTitle.ifBlank { "새 작업" },
                            durationMinutes = duration,
                            startTimeHour = calendar.get(Calendar.HOUR_OF_DAY),
                            startTimeMinute = calendar.get(Calendar.MINUTE),
                            startNewSession = true,
                            intervalMinutes = localInterval,
                            restMinutes = if (isCycleMode) localRestMinutes else 0,
                            onComplete = { onNavigateToTimer() }
                        )
                        viewModel.clearSelectedBlocks()
                        showAddTaskDialog = false
                    }
                ) {
                    Text("전략적 몰입 시작")
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showAddTaskDialog = false }
                ) {
                    Text("취소")
                }
            }
        )
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
                            onNavigateToTimer() // 타이머 탭으로 이동 (요구사항 6번 개선)
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
    allSchedules: List<ScheduleBlock>,
    onDateSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = selectedDate }
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = calendar.apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                Text(day, style = MaterialTheme.typography.labelMedium, color = if (day == "일") Color.Red else if (day == "토") Color.Blue else Color.Unspecified)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        var currentDay = 1
        for (i in 0..5) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                for (j in 0..6) {
                    val index = i * 7 + j
                    if (index < firstDayOfWeek || currentDay > daysInMonth) {
                        Box(modifier = Modifier.size(45.dp))
                    } else {
                        val dayDate = Calendar.getInstance().apply {
                            timeInMillis = selectedDate
                            set(Calendar.DAY_OF_MONTH, currentDay)
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        
                        val isSelected = isSameDay(dayDate, selectedDate)
                        val taskCount = allSchedules.count { isSameDay(it.startTimeMillis, dayDate) } // 태스크 수 계산 (요구사항 1번)
                        
                        Column(
                            modifier = Modifier
                                .size(45.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onDateSelected(dayDate) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = currentDay.toString(),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            if (taskCount > 0) {
                                Text(
                                    text = taskCount.toString(),
                                    fontSize = 9.sp,
                                    color = Color(0xFF2E7D32), // 녹색 표시 (요구사항 1번)
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        currentDay++
                    }
                }
            }
            if (currentDay > daysInMonth) break
        }
    }
}

@Composable
fun DailyTimelineView(
    uiState: SchedulerUiState,
    selectedDate: Long,
    onAddSchedule: (Int, Int, Int) -> Unit,
    onLoadSchedule: (ScheduleBlock) -> Unit,
    onToggleBlock: (Long) -> Unit,
    onClearSelection: () -> Unit
) {
    val listState = rememberLazyListState()
    
    // 현재 시간으로 스크롤 (오늘인 경우)
    LaunchedEffect(key1 = selectedDate) {
        if (isToday(selectedDate)) {
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            listState.scrollToItem(currentHour)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.selectedBlocks.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${uiState.selectedBlocks.size * 15}분 선택됨", style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = onClearSelection) { Text("취소") }
                        Button(onClick = { onAddSchedule(0, 0, 0) }) { Text("세션 생성") }
                    }
                }
            }
        }
        
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(24) { hour ->
                HourRow(
                    hour = hour,
                    selectedDate = selectedDate,
                    uiState = uiState,
                    onToggleBlock = onToggleBlock,
                    onLoadSchedule = onLoadSchedule
                )
            }
        }
    }
}

@Composable
fun HourRow(
    hour: Int,
    selectedDate: Long,
    uiState: SchedulerUiState,
    onToggleBlock: (Long) -> Unit,
    onLoadSchedule: (ScheduleBlock) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                
                val schedule = uiState.dailySchedules.firstOrNull { s ->
                    val sStart = s.startTimeMillis
                    val sEnd = sStart + s.durationMinutes * 60 * 1000L
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

private fun isToday(m1: Long): Boolean {
    return isSameDay(m1, System.currentTimeMillis())
}

private fun isSameTime(m1: Long, m2: Long): Boolean {
    return m1 / 60000 == m2 / 60000
}

private fun formatDate(dateMillis: Long): String {
    val sdf = SimpleDateFormat("M월 d일 (E)", Locale.KOREAN)
    return sdf.format(Date(dateMillis))
}

private fun formatMonth(dateMillis: Long): String {
    val sdf = SimpleDateFormat("yyyy년 M월", Locale.KOREAN)
    return sdf.format(Date(dateMillis))
}
