package com.focusflow.app.ui.screen

import android.app.DatePickerDialog
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.focusflow.app.model.ScheduleBlock
import com.focusflow.app.ui.viewmodel.SchedulerUiState
import com.focusflow.app.ui.viewmodel.SchedulerViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (hours > 0) {
        "${hours}시간 ${remainingMinutes}분"
    } else {
        "${remainingMinutes}분"
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    viewModel: SchedulerViewModel,
    onNavigateToTimer: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduleBlock?>(null) }
    
    // 세션 생성을 위한 빠른 제목 입력 상태
    var quickTaskTitle by remember { mutableStateOf("") }
    
    val isMonthlyView = uiState.isCalendarMonthlyView
    
    // 주간/월간 드래그 확장을 위한 상태
    var calendarHeight by remember { mutableStateOf(100.dp) }
    val density = LocalDensity.current
    
    // [v1.8.2] 주차 수에 따른 가변 높이 계산
    val weeksInMonth = remember(selectedDate) { getWeeksInMonth(selectedDate) }
    val maxCalendarHeight = when(weeksInMonth) {
        4 -> 300.dp
        5 -> 360.dp
        else -> 420.dp
    }
    val minCalendarHeight = 100.dp

    // 높이 제한 보정
    LaunchedEffect(maxCalendarHeight) {
        if (calendarHeight > maxCalendarHeight) {
            calendarHeight = maxCalendarHeight
        }
    }

    var showYearMonthPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    // [v1.8.2] 연/월 전용 선택기 도입
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            showYearMonthPicker = true
                        }
                    ) {
                        Text(
                            text = if (calendarHeight > 200.dp) formatMonth(selectedDate) else formatDate(selectedDate),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "날짜 선택",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.selectDate(System.currentTimeMillis())
                    }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "오늘")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 접이식 캘린더 (한 줄 주간 <-> 월간)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(calendarHeight)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (calendarHeight > 180.dp) {
                        MonthlyCalendarView(
                            selectedDate = selectedDate,
                            allSchedules = uiState.allSchedules,
                            onDateSelected = { viewModel.selectDate(it) },
                            onMonthChange = { viewModel.selectDate(it) }
                        )
                    } else {
                        WeeklyCalendarStrip(
                            selectedDate = selectedDate,
                            allSchedules = uiState.allSchedules,
                            onDateSelected = { viewModel.selectDate(it) }
                        )
                    }
                }
                
                // 드래그 핸들
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                val deltaDp = with(density) { delta.toDp() }
                                calendarHeight = (calendarHeight + deltaDp).coerceIn(minCalendarHeight, maxCalendarHeight)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(32.dp, 4.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.outlineVariant
                    ) {}
                }
            }

            // 타임라인 영역
            Box(modifier = Modifier.weight(1f)) {
                DailyTimelineView(
                    uiState = uiState,
                    selectedDate = selectedDate,
                    onLoadSchedule = { schedule ->
                        editingSchedule = schedule
                    },
                    onToggleBlock = { blockTime ->
                        viewModel.toggleBlock(blockTime)
                    }
                )
            }

            // 하단 퀵 세션 바: 동선을 줄이기 위해 텍스트 필드를 바로 노출
            if (!isMonthlyView && uiState.selectedBlocks.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 12.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatDuration(uiState.selectedBlocks.size * 15)} 선택됨",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { 
                                    viewModel.clearSelectedBlocks()
                                    quickTaskTitle = ""
                                },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text("취소", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = quickTaskTitle,
                                onValueChange = { quickTaskTitle = it },
                                placeholder = { Text("예: 업무, 독서, 명상", style = MaterialTheme.typography.bodyMedium) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            
                            IconButton(
                                onClick = { showAddTaskDialog = true },
                                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "상세 설정", tint = MaterialTheme.colorScheme.primary)
                            }

                            Button(
                                onClick = {
                                    val totalMin = uiState.selectedBlocks.size * 15
                                    val firstBlock = uiState.selectedBlocks.minOrNull() ?: System.currentTimeMillis()
                                    val calendar = Calendar.getInstance().apply { timeInMillis = firstBlock }
                                    
                                    // 설정에 저장된 기본 인터벌과 휴식 시간을 사용
                                    viewModel.addSchedule(
                                        taskTitle = quickTaskTitle.ifBlank { "새 작업" },
                                        durationMinutes = totalMin,
                                        startTimeHour = calendar.get(Calendar.HOUR_OF_DAY),
                                        startTimeMinute = calendar.get(Calendar.MINUTE),
                                        startNewSession = false,
                                        intervalMinutes = uiState.storedAlarmIntervalMinutes,
                                        restMinutes = uiState.storedRestMinutes // 설정된 휴식 시간을 사용 (v1.8.3-patch17)
                                    )
                                    viewModel.clearSelectedBlocks()
                                    quickTaskTitle = ""
                                },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.height(48.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp)
                            ) {
                                Text("확인", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showYearMonthPicker) {
        YearMonthPickerDialog(
            initialDate = selectedDate,
            onDismiss = { showYearMonthPicker = false },
            onDateSelected = { y, m ->
                val newCal = Calendar.getInstance().apply {
                    timeInMillis = selectedDate
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                }
                viewModel.selectDate(newCal.timeInMillis)
            }
        )
    }

    if (showAddTaskDialog) {
        var taskTitle by remember { mutableStateOf(quickTaskTitle) }
        var isCycleMode by remember { mutableStateOf(uiState.storedRestMinutes > 0) } 
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
            mutableIntStateOf(if (isCycleMode) uiState.storedAlarmIntervalMinutes.coerceIn(5, totalMin - 1) else uiState.storedAlarmIntervalMinutes.coerceAtMost(totalMin)) 
        }
        var localRestMinutes by remember { 
            mutableIntStateOf(if (isCycleMode) getValidRest(localInterval, uiState.storedRestMinutes) else 0)
        }

        AlertDialog(
            onDismissRequest = { showAddTaskDialog = false },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 1. 작업 요약
                    Column {
                        Text("할 일 생성", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }

                    // 2. 모드 선택 (탭 스타일)
                    var isCustomExpanded by remember { mutableStateOf(false) }
                    
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
                                    "연속", 
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
                                    "사이클", 
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
                                Text("알람 주기", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(5, 10, 15, 30, 60).forEach { interval ->
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
                                                if (interval >= 60) "${interval / 60}시간" else "${interval}분",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (localInterval == interval) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 인터벌 사이클 모드
                                Text("뽀모도로", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                    val is25Selected = localInterval == 25 && localRestMinutes == getValidRest(25, 5)
                                    val is50Selected = localInterval == 50 && localRestMinutes == getValidRest(50, 10)
                                    
                                    FilterChip(
                                        selected = is25Selected,
                                        onClick = { 
                                            localInterval = 25
                                            localRestMinutes = getValidRest(25, 5)
                                        },
                                        label = { Text("25/5", style = MaterialTheme.typography.labelMedium) },
                                        enabled = totalMin >= 30,
                                        leadingIcon = { Icon(imageVector = Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                    FilterChip(
                                        selected = is50Selected,
                                        onClick = { 
                                            localInterval = 50
                                            localRestMinutes = getValidRest(50, 10)
                                        },
                                        label = { Text("50/10", style = MaterialTheme.typography.labelMedium) },
                                        enabled = totalMin >= 60,
                                        leadingIcon = { Icon(imageVector = Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isCustomExpanded = !isCustomExpanded }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("커스텀 설정", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                    Icon(
                                        imageVector = if (isCustomExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                                
                                if (isCustomExpanded) {
                                    Spacer(Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("집중: ${localInterval}분", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                                            Slider(
                                                value = localInterval.toFloat(),
                                                onValueChange = { 
                                                    val snapped = Math.round(it / 5.0).toInt() * 5
                                                    localInterval = snapped.coerceIn(5, totalMin - 1)
                                                    localRestMinutes = getValidRest(localInterval, localRestMinutes)
                                                },
                                                valueRange = 5f..(totalMin - 1).coerceAtLeast(5).toFloat(),
                                                modifier = Modifier.weight(1f),
                                                steps = if (totalMin > 10) Math.round((totalMin - 1 - 5) / 5.0).toInt() else 0
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("휴식: ${localRestMinutes}분", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                                            Slider(
                                                value = localRestMinutes.toFloat(),
                                                onValueChange = { 
                                                    val requestedRest = Math.round(it).toInt().coerceAtLeast(1)
                                                    val targetSum = divisors.filter { d -> d > localInterval }
                                                        .minByOrNull { d -> Math.abs((d - localInterval) - requestedRest) } ?: totalMin
                                                    localRestMinutes = targetSum - localInterval
                                                },
                                                valueRange = 1f..(totalMin - localInterval).coerceAtLeast(1).toFloat(),
                                                modifier = Modifier.weight(1f),
                                                steps = (totalMin - localInterval - 1).coerceAtLeast(0)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. 미리보기
                    Column {
                        Text(formatDuration(totalMin), style = MaterialTheme.typography.labelSmall)
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val duration = totalMin
                    val firstBlock = uiState.selectedBlocks.minOrNull() ?: System.currentTimeMillis()
                    val calendar = Calendar.getInstance().apply { timeInMillis = firstBlock }
                    val finalTitle = taskTitle.ifBlank { "새 작업" }
                    val finalInterval = localInterval
                    val finalRest = if (isCycleMode) localRestMinutes else 0

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.addSchedule(
                                taskTitle = finalTitle,
                                durationMinutes = duration,
                                startTimeHour = calendar.get(Calendar.HOUR_OF_DAY),
                                startTimeMinute = calendar.get(Calendar.MINUTE),
                                startNewSession = false,
                                intervalMinutes = finalInterval,
                                restMinutes = finalRest
                            )
                            viewModel.clearSelectedBlocks()
                            showAddTaskDialog = false
                        }
                    ) {
                        Text("생성")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // 명시적인 스케줄 객체를 생성하여 loadScheduledSession에 전달함으로써 
                            // addSchedule 비동기 작업 완료 전에도 UI 상태를 즉시 업데이트 (v1.8.0 핫픽스)
                            val newSchedule = ScheduleBlock(
                                taskTitle = finalTitle,
                                startTimeMillis = firstBlock,
                                durationMinutes = duration,
                                intervalMinutes = finalInterval,
                                restMinutes = finalRest
                            )
                            
                            viewModel.addSchedule(
                                taskTitle = finalTitle,
                                durationMinutes = duration,
                                startTimeHour = calendar.get(Calendar.HOUR_OF_DAY),
                                startTimeMinute = calendar.get(Calendar.MINUTE),
                                startNewSession = false,
                                intervalMinutes = finalInterval,
                                restMinutes = finalRest
                            )
                            viewModel.loadScheduledSession(newSchedule)
                            viewModel.clearSelectedBlocks()
                            showAddTaskDialog = false
                            onNavigateToTimer()
                        }
                    ) {
                        Text("시작")
                    }
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
        var durationMinutes by remember(editingSchedule) { mutableIntStateOf(editingSchedule?.durationMinutes ?: 60) }
        val interval = editingSchedule?.intervalMinutes ?: 15
        val rest = editingSchedule?.restMinutes ?: 0
        val isCycle = rest > 0

        AlertDialog(
            onDismissRequest = { editingSchedule = null },
            title = { Text("세션 관리") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 1. 작업명 수정
                    OutlinedTextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it },
                        label = { Text("작업명") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium
                    )
                    
                    // 2. 시간 수정
                    Column {
                        val currentStart = editingSchedule?.startTimeMillis ?: 0L
                        val nextSchedule = uiState.dailySchedules
                            .filter { s -> s.startTimeMillis > currentStart && s.id != editingSchedule?.id }
                            .minByOrNull { s -> s.startTimeMillis }
                        
                        val maxDurationPossible = if (nextSchedule != null) {
                            ((nextSchedule.startTimeMillis - currentStart) / (60 * 1000)).toInt()
                        } else {
                            480 // 최대 8시간 (480분)
                        }
                        
                        val sliderMax = (maxDurationPossible / 15 * 15).coerceAtMost(480).toFloat().coerceAtLeast(15f)
                        
                        Text("작업 시간: ${formatDuration(durationMinutes)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Slider(
                            value = durationMinutes.toFloat(),
                            onValueChange = { 
                                val snapped = Math.round(it / 15.0).toInt() * 15
                                durationMinutes = snapped.coerceIn(15, sliderMax.toInt())
                            },
                            valueRange = 15f..sliderMax,
                            steps = if (sliderMax > 15f) Math.round((sliderMax - 15) / 15.0).toInt() - 1 else 0
                        )
                        if (maxDurationPossible < 480) {
                            Text(
                                "다음 일정 때문에 최대 ${maxDurationPossible}분까지 설정 가능합니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // 3. 집중 방식 정보 (읽기 전용)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isCycle) "🔄 사이클 모드 (${interval}분 집중 / ${rest}분 휴식)" 
                                       else "⚡ 연속 집중 모드 (${interval}분 알람)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(Modifier.height(8.dp))
                            
                            // 미니 미리보기
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (rest <= 0) {
                                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary))
                                } else {
                                    var current = 0
                                    while (current < durationMinutes) {
                                        val fWidth = Math.min(interval, durationMinutes - current).toFloat() / durationMinutes
                                        if (fWidth > 0) Box(Modifier.weight(fWidth).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                                        current += interval
                                        if (current < durationMinutes) {
                                            val rWidth = Math.min(rest, durationMinutes - current).toFloat() / durationMinutes
                                            if (rWidth > 0) Box(Modifier.weight(rWidth).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
                                            current += rest
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = {
                            editingSchedule?.let { 
                                viewModel.updateSchedule(
                                    it, 
                                    taskTitle, 
                                    durationMinutes
                                ) 
                            }
                            editingSchedule = null
                        }) {
                            Text("수정")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            editingSchedule?.let { 
                                // 변경사항 저장 후 실행
                                viewModel.updateSchedule(it, taskTitle, durationMinutes)
                                viewModel.loadScheduledSession(it.copy(taskTitle = taskTitle, durationMinutes = durationMinutes))
                                onNavigateToTimer()
                            }
                            editingSchedule = null
                        }) {
                            Text("수행")
                        }
                    }

                    // 2열: 좌측 끝에 삭제, 우측 끝에 취소
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                editingSchedule?.let { viewModel.deleteSchedule(it) }
                                editingSchedule = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("삭제")
                        }
                        
                        TextButton(onClick = { editingSchedule = null }) {
                            Text("취소")
                        }
                    }
                }
            },
            dismissButton = null
        )
    }
}

@Composable
fun WeeklyCalendarStrip(
    selectedDate: Long,
    allSchedules: List<ScheduleBlock>,
    onDateSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply { 
        timeInMillis = selectedDate
        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    
    val weekDates = remember(selectedDate) {
        (0..6).map {
            val date = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            date
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        weekDates.forEach { date ->
            val isSelected = isSameDay(date, selectedDate)
            val cal = Calendar.getInstance().apply { timeInMillis = date }
            val dayName = when(cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> "일"; Calendar.MONDAY -> "월"; Calendar.TUESDAY -> "화"
                Calendar.WEDNESDAY -> "수"; Calendar.THURSDAY -> "목"; Calendar.FRIDAY -> "금"
                else -> "토"
            }
            val hasTask = allSchedules.any { isSameDay(it.startTimeMillis, date) }
            val dayColor = when(cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> Color.Red
                Calendar.SATURDAY -> Color(0xFF2196F3)
                else -> if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onDateSelected(date) }
                    .padding(8.dp)
            ) {
                Text(
                    text = dayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = dayColor
                )
                Text(
                    text = cal.get(Calendar.DAY_OF_MONTH).toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else dayColor.copy(alpha = 0.8f)
                )
                if (hasTask) {
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                } else {
                    Spacer(modifier = Modifier.size(4.dp))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MonthlyCalendarView(
    selectedDate: Long,
    allSchedules: List<ScheduleBlock>,
    onDateSelected: (Long) -> Unit,
    onMonthChange: (Long) -> Unit
) {
    // [v1.8.3] 기준점을 앱 실행 시점의 '오늘'로 고정하여 오프셋 누적 방지
    val baseDate = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // 500번 페이지가 baseDate(현재 월)가 됨
    val pagerState = rememberPagerState(initialPage = 500) { 1000 }

    // [v1.8.3] 페이저 스크롤이 완전히 끝났을 때만 ViewModel 날짜 업데이트 (핀트 어긋남 방지)
    LaunchedEffect(pagerState) {
        snapshotFlow { 
            if (pagerState.isScrollInProgress) -1 else pagerState.currentPage 
        }.collect { page ->
            if (page != -1) {
                val monthOffset = page - 500
                val targetCal = Calendar.getInstance().apply {
                    timeInMillis = baseDate
                    add(Calendar.MONTH, monthOffset)
                }
                
                // 현재 선택된 날짜와 월이 다른 경우에만 업데이트
                if (!isSameMonth(targetCal.timeInMillis, selectedDate)) {
                    val currentCal = Calendar.getInstance().apply { timeInMillis = selectedDate }
                    val newCal = targetCal.apply {
                        val day = currentCal.get(Calendar.DAY_OF_MONTH).coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH))
                        set(Calendar.DAY_OF_MONTH, day)
                    }
                    onMonthChange(newCal.timeInMillis)
                }
            }
        }
    }

    // 외부에서 selectedDate(오늘 버튼 등) 변경 시 페이저를 해당 월로 이동
    LaunchedEffect(selectedDate) {
        val selCal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val baseCal = Calendar.getInstance().apply { timeInMillis = baseDate }
        
        val monthDiff = (selCal.get(Calendar.YEAR) - baseCal.get(Calendar.YEAR)) * 12 +
                (selCal.get(Calendar.MONTH) - baseCal.get(Calendar.MONTH))
        val targetPage = 500 + monthDiff
        
        if (pagerState.currentPage != targetPage) {
            // 애니메이션 없이 즉시 이동하여 중간 월(June 등)에 의한 오작동 방지
            pagerState.scrollToPage(targetPage)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top
    ) { page ->
        val monthOffset = page - 500
        val displayMonthCal = Calendar.getInstance().apply {
            timeInMillis = baseDate
            add(Calendar.MONTH, monthOffset)
        }
        
        MonthPageContent(
            displayMonth = displayMonthCal.timeInMillis,
            selectedDate = selectedDate,
            allSchedules = allSchedules,
            onDateSelected = onDateSelected
        )
    }
}

@Composable
fun MonthPageContent(
    displayMonth: Long,
    selectedDate: Long,
    allSchedules: List<ScheduleBlock>,
    onDateSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply { 
        timeInMillis = displayMonth
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1

    val days = remember(displayMonth) {
        val list = mutableListOf<Long?>()
        repeat(firstDayOfWeek) { list.add(null) }
        val tempCal = calendar.clone() as Calendar
        for (d in 1..daysInMonth) {
            list.add(tempCal.timeInMillis)
            tempCal.add(Calendar.DAY_OF_MONTH, 1)
        }
        // 6주(42일)를 꽉 채움
        while (list.size < 42) {
            list.add(null)
        }
        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 요일 헤더
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { index, day ->
                val color = when(index) {
                    0 -> Color.Red
                    6 -> Color(0xFF2196F3)
                    else -> MaterialTheme.colorScheme.outline
                }
                Text(day, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(days) { dateMillis ->
                if (dateMillis == null) {
                    Box(modifier = Modifier.aspectRatio(1.2f))
                } else {
                    val isSelected = isSameDay(dateMillis, selectedDate)
                    val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
                    val daySchedules = allSchedules.filter { isSameDay(it.startTimeMillis, dateMillis) }
                    val hasTask = daySchedules.isNotEmpty()
                    
                    val dayColor = when(cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.SUNDAY -> Color.Red
                        Calendar.SATURDAY -> Color(0xFF2196F3)
                        else -> if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    }

                    Box(
                        modifier = Modifier
                            .aspectRatio(1.2f)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onDateSelected(dateMillis) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = cal.get(Calendar.DAY_OF_MONTH).toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = dayColor
                            )
                            if (hasTask) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = daySchedules.size.toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onTertiary
                                        ),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YearMonthPickerDialog(
    initialDate: Long,
    onDismiss: () -> Unit,
    onDateSelected: (year: Int, month: Int) -> Unit
) {
    val cal = Calendar.getInstance().apply { timeInMillis = initialDate }
    var selectedYear by remember { mutableIntStateOf(cal.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(cal.get(Calendar.MONTH)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("연/월 선택") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                YearMonthPickerColumn(
                    items = (2000..2100).toList(),
                    selectedItem = selectedYear,
                    onItemSelected = { selectedYear = it },
                    label = { "${it}년" },
                    modifier = Modifier.weight(1f)
                )
                YearMonthPickerColumn(
                    items = (0..11).toList(),
                    selectedItem = selectedMonth,
                    onItemSelected = { selectedMonth = it },
                    label = { "${it + 1}월" },
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { 
                onDateSelected(selectedYear, selectedMonth)
                onDismiss()
            }) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
fun <T> YearMonthPickerColumn(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    val initialIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialIndex - 2).coerceAtLeast(0))
    
    LazyColumn(
        state = listState,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 80.dp) // 중앙 정렬 효과를 위한 패딩
    ) {
        items(items.size) { index ->
            val item = items[index]
            val isSelected = item == selectedItem
            Text(
                text = label(item),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemSelected(item) }
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
                style = if (isSelected) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        else MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

@Composable
fun DailyTimelineView(
    uiState: SchedulerUiState,
    selectedDate: Long,
    onLoadSchedule: (ScheduleBlock) -> Unit,
    onToggleBlock: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    
    // 현재 시간으로 스크롤 (오늘인 경우)
    LaunchedEffect(key1 = selectedDate) {
        if (isToday(selectedDate)) {
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            listState.scrollToItem(currentHour)
        }
    }

    // 블록 선택 시 하단이 가려지지 않도록 22시 이후 선택 시 자동 스크롤
    LaunchedEffect(uiState.selectedBlocks.size) {
        if (uiState.selectedBlocks.isNotEmpty()) {
            val lastSelectedBlock = uiState.selectedBlocks.maxOrNull() ?: 0L
            val cal = Calendar.getInstance().apply { timeInMillis = lastSelectedBlock }
            if (cal.get(Calendar.HOUR_OF_DAY) >= 22) {
                listState.animateScrollToItem(23)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 16.dp,
            start = 16.dp,
            end = 16.dp,
            bottom = 16.dp // 이제 Column 레이아웃이 영역을 확보해주므로 기본 패딩만 사용
        ),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                schedule != null -> if (schedule.isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                                   else MaterialTheme.colorScheme.secondaryContainer
                                isSelected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                schedule != null -> if (schedule.isCompleted) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) 
                                                   else MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f)
                            },
                            shape = MaterialTheme.shapes.extraSmall
                        )
                        .clickable { 
                            if (schedule != null) onLoadSchedule(schedule)
                            else onToggleBlock(blockStartTime)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (schedule != null && isSameTime(blockStartTime, schedule.startTimeMillis)) {
                        Text(
                            text = schedule.taskTitle, 
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (schedule.isCompleted) FontWeight.Normal else FontWeight.Bold,
                                color = if (schedule.isCompleted) MaterialTheme.colorScheme.outline 
                                        else MaterialTheme.colorScheme.onSecondaryContainer,
                                textDecoration = if (schedule.isCompleted) TextDecoration.LineThrough else null
                            ), 
                            maxLines = 1, 
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
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

private fun isSameMonth(m1: Long, m2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = m1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = m2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH)
}

private fun getWeeksInMonth(dateMillis: Long): Int {
    val cal = Calendar.getInstance().apply {
        timeInMillis = dateMillis
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 for Sunday
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    return Math.ceil((firstDayOfWeek + daysInMonth).toDouble() / 7).toInt()
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
