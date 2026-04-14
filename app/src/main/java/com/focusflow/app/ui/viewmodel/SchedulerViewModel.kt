package com.focusflow.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.focusflow.app.data.prefs.SettingsRepository
import com.focusflow.app.data.repository.ScheduleRepository
import com.focusflow.app.data.repository.StatsRepository
import com.focusflow.app.data.repository.TaskRepository
import com.focusflow.app.model.DailyStats
import com.focusflow.app.model.ScheduleBlock
import com.focusflow.app.model.Task
import com.focusflow.app.model.TimeBlock
import com.focusflow.app.service.TimerService
import com.focusflow.app.util.BlockType
import com.focusflow.app.util.NotificationHelper
import com.focusflow.app.util.VibrationPattern
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Calendar
import java.util.UUID

data class SchedulerUiState(
    val tasks: List<Task> = emptyList(),
    val allSchedules: List<ScheduleBlock> = emptyList(),
    val dailySchedules: List<ScheduleBlock> = emptyList(),
    val selectedBlocks: Set<Long> = emptySet(),
    val selectionAnchor: Long? = null,
    val isTimerActive: Boolean = false,
    val isRunning: Boolean = false,
    val remainingSeconds: Int = 0,
    val totalRemainingSeconds: Int = 0,
    val currentBlockIndex: Int = 0,
    val timeBlocks: List<TimeBlock> = emptyList(),
    val selectedTaskId: String? = null,
    val selectedTaskTitle: String? = "작업을 선택하세요",
    val currentScheduleId: String? = null,
    val recentStats: List<DailyStats> = emptyList(),
    val sessionTotalMinutes: Int = 60,
    val alarmIntervalMinutes: Int = 15,
    val restMinutes: Int = 0,
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val calendarSyncEnabled: Boolean = false,
    val focusVibrationPatternId: String = "focus_default",
    val restVibrationPatternId: String = "rest_default",
    val finishVibrationPatternId: String = "finish_triple",
    val focusSoundId: String = "focus_default",
    val restSoundId: String = "rest_default",
    val finishSoundId: String = "finish_triple",
    val defaultTotalMinutes: Int = 60,
    val darkMode: Int = 0,
    val fontSizeScale: Float = 1.0f,
    val useFullScreenAlarm: Boolean = false,
    val focusRingtoneUri: String? = null,
    val restRingtoneUri: String? = null,
    val finishRingtoneUri: String? = null,
    val storedAlarmIntervalMinutes: Int = 15,
    val storedRestMinutes: Int = 0,
    val isCalendarMonthlyView: Boolean = false
)

class SchedulerViewModel(
    private val app: Application,
    private val repository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val statsRepository: StatsRepository,
    private val scheduleRepository: ScheduleRepository
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(SchedulerUiState())
    val uiState: StateFlow<SchedulerUiState> = _uiState.asStateFlow()

    private val _selectedDate = MutableStateFlow(System.currentTimeMillis())
    val selectedDate: StateFlow<Long> = _selectedDate.asStateFlow()

    private var timerService: TimerService? = null
    private val notificationHelper = NotificationHelper.getInstance(app)

    init {
        loadData()
        observeDailySchedules()
        observeStats()
        observeTodayTasks() // 오늘 작업 구독 추가
    }

    private fun observeTodayTasks() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val endOfDay = calendar.timeInMillis

        // [수정] Task와 ScheduleBlock을 결합하여 UI에 표시 및 정렬
        combine(
            repository.getTasksForRange(startOfDay, endOfDay),
            scheduleRepository.getAllSchedules()
        ) { tasks, schedules ->
            val todaySchedules = schedules.filter { 
                it.startTimeMillis in startOfDay..endOfDay 
            }
            
            val combined = tasks.map { it } + todaySchedules.map { schedule ->
                com.focusflow.app.model.Task(
                    id = schedule.id,
                    title = schedule.taskTitle,
                    scheduledDateMillis = startOfDay,
                    startTimeMillis = schedule.startTimeMillis, // 정렬용 실제 시간
                    isCompleted = schedule.isCompleted,
                    durationMinutes = schedule.durationMinutes // [수정] 기간 정보 명시적 전달
                )
            }
            
            // 중복 제거 및 정렬 (startTimeMillis -> scheduledDateMillis -> createdAt 순)
            combined.distinctBy { it.id }.sortedWith(
                compareBy<com.focusflow.app.model.Task> { 
                    if (it.startTimeMillis == 0L) Long.MAX_VALUE else it.startTimeMillis 
                }.thenBy { 
                    if (it.scheduledDateMillis == 0L) Long.MAX_VALUE else it.scheduledDateMillis 
                }
            )
        }.onEach { combinedTasks ->
            _uiState.update { it.copy(tasks = combinedTasks) }
        }.launchIn(viewModelScope)
    }

    private fun observeStats() {
        statsRepository.recentStats.onEach { stats ->
            _uiState.update { it.copy(recentStats = stats) }
        }.launchIn(viewModelScope)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeDailySchedules() {
        selectedDate.flatMapLatest { date ->
            scheduleRepository.getSchedulesForDay(date)
        }.onEach { daily ->
            _uiState.update { it.copy(dailySchedules = daily) }
        }.launchIn(viewModelScope)
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                // repository.allTasks 대신 개별 구독(observeTodayTasks)으로 관리하므로 여기서는 제외하거나 필요한 설정값만 결합
                scheduleRepository.getAllSchedules(),
                settingsRepository.alarmIntervalMinutes,
                settingsRepository.restMinutes,
                settingsRepository.vibrationEnabled,
                settingsRepository.soundEnabled,
                settingsRepository.calendarSyncEnabled,
                settingsRepository.focusVibrationPatternId,
                settingsRepository.restVibrationPatternId,
                settingsRepository.finishVibrationPatternId,
                settingsRepository.focusSoundId,
                settingsRepository.restSoundId,
                settingsRepository.finishSoundId,
                settingsRepository.defaultTotalMinutes,
                settingsRepository.darkMode,
                settingsRepository.fontSizeScale,
                settingsRepository.focusRingtoneUri,
                settingsRepository.restRingtoneUri,
                settingsRepository.finishRingtoneUri,
                settingsRepository.useFullScreenAlarm
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val allSchedules = values[0] as List<ScheduleBlock>
                val interval = values[1] as Int
                val rest = values[2] as Int
                val vibration = values[3] as Boolean
                val sound = values[4] as Boolean
                val calendarSync = values[5] as Boolean
                val focusVib = values[6] as String
                val restVib = values[7] as String
                val finishVib = values[8] as String
                val focusSnd = values[9] as String
                val restSnd = values[10] as String
                val finishSnd = values[11] as String
                val defTotal = values[12] as Int
                val dark = values[13] as Int
                val fontSize = values[14] as Float
                val focusUri = values[15] as String?
                val restUri = values[16] as String?
                val finishUri = values[17] as String?
                val fullScreen = values[18] as Boolean

                _uiState.update { state ->
                    val newSessionTotal = if (!state.isTimerActive) defTotal else state.sessionTotalMinutes
                    val newInterval = if (!state.isTimerActive) interval else state.alarmIntervalMinutes
                    val newRest = if (!state.isTimerActive) rest else state.restMinutes
                    
                    state.copy(
                        allSchedules = allSchedules,
                        alarmIntervalMinutes = newInterval,
                        restMinutes = newRest,
                        sessionTotalMinutes = newSessionTotal,
                        vibrationEnabled = vibration,
                        soundEnabled = sound,
                        calendarSyncEnabled = calendarSync,
                        focusVibrationPatternId = focusVib,
                        restVibrationPatternId = restVib,
                        finishVibrationPatternId = finishVib,
                        focusSoundId = focusSnd,
                        restSoundId = restSnd,
                        finishSoundId = finishSnd,
                        defaultTotalMinutes = defTotal,
                        darkMode = dark,
                        fontSizeScale = fontSize,
                        focusRingtoneUri = focusUri,
                        restRingtoneUri = restUri,
                        finishRingtoneUri = finishUri,
                        useFullScreenAlarm = fullScreen,
                        storedAlarmIntervalMinutes = interval,
                        storedRestMinutes = rest,
                        timeBlocks = if (!state.isTimerActive) {
                            generateBlocks(newInterval, newRest, newSessionTotal * 60)
                        } else state.timeBlocks
                    )
                }
            }.collect()
        }
    }

    private fun generateDefaultBlocks(interval: Int, totalMinutes: Int) {
        // Logic to generate default blocks based on settings
    }

    private var serviceCollectorJob: Job? = null

    fun setTimerService(service: TimerService?) {
        this.timerService = service
        serviceCollectorJob?.cancel()
        
        if (service == null) {
            _uiState.update { it.copy(isRunning = false) }
            return
        }

        serviceCollectorJob = viewModelScope.launch {
            val s = service
            // [v1.7.3] 서비스 연결 시 콜백을 항상 최신화하여 세션 종료 시 onSessionFinished가 반드시 호출되도록 합니다.
            val currentState = _uiState.value
            
            // 기존 config가 있든 없든, UI 상태에 맞춰서 콜백(onTransition, onFinished)을 다시 바인딩합니다.
            val config = s.config.value
            val taskTitleForService = config?.title ?: currentState.selectedTaskTitle ?: ""
            val interval = config?.intervalMinutes ?: currentState.alarmIntervalMinutes
            val rest = config?.restMinutes ?: currentState.restMinutes
            val totalSec = config?.totalSecondsAtStart ?: (currentState.sessionTotalMinutes * 60)
            
            s.setTimerConfig(
                interval = interval,
                rest = rest,
                totalSec = totalSec,
                title = taskTitleForService,
                vibrate = config?.vibrate ?: currentState.vibrationEnabled,
                sound = config?.sound ?: currentState.soundEnabled,
                useFullScreen = config?.useFullScreen ?: currentState.useFullScreenAlarm,
                focusPatternId = config?.focusPatternId ?: currentState.focusVibrationPatternId,
                restPatternId = config?.restPatternId ?: currentState.restVibrationPatternId,
                finishPatternId = config?.finishPatternId ?: currentState.finishVibrationPatternId,
                focusSound = config?.focusSound ?: currentState.focusSoundId,
                restSound = config?.restSound ?: currentState.restSoundId,
                finishSound = config?.finishSound ?: currentState.finishSoundId,
                focusRingtoneUri = config?.focusRingtoneUri ?: currentState.focusRingtoneUri,
                restRingtoneUri = config?.restRingtoneUri ?: currentState.restRingtoneUri,
                finishRingtoneUri = config?.finishRingtoneUri ?: currentState.finishRingtoneUri,
                onTransition = { t, e, f, bt, isSkip -> onBlockTransition(t, e, f, bt, isSkip) },
                onFinished = { onSessionFinished() }
            )

            combine(s.remainingSeconds, s.totalRemainingSeconds, s.isRunning, s.currentBlockIndex) { rem, total, running, idx ->
                _uiState.update { state ->
                    // [v1.7.3] 상태 판단 로직 강화: total이 0이면 세션 자체가 끝난 것으로 판단
                    val active = total > 0 || (running && total > 0)
                    state.copy(
                        remainingSeconds = if (active) rem else 0,
                        totalRemainingSeconds = if (active) total else 0,
                        isRunning = running,
                        currentBlockIndex = idx,
                        isTimerActive = active
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            service.config.collect { config ->
                config?.let {
                    val blocks = generateBlocks(it.intervalMinutes, it.restMinutes, it.totalSecondsAtStart)
                    _uiState.update { state -> 
                        state.copy(
                            timeBlocks = blocks,
                            // [v1.7.3] 서비스 설정에서 제목을 복구하여 UI 버튼 노출 상태 유지
                            selectedTaskTitle = if (state.selectedTaskTitle.isNullOrEmpty()) it.title else state.selectedTaskTitle,
                            alarmIntervalMinutes = it.intervalMinutes,
                            restMinutes = it.restMinutes,
                            sessionTotalMinutes = it.totalSecondsAtStart / 60
                        )
                    }
                }
            }
        }
    }

    private fun generateBlocks(intervalMinutes: Int, restMinutes: Int, totalSeconds: Int, startTime: Long? = null): List<TimeBlock> {
        val blocks = mutableListOf<TimeBlock>()
        var remaining = totalSeconds
        val focusSec = intervalMinutes * 60
        val restSec = restMinutes * 60
        var currentTime = startTime ?: System.currentTimeMillis()

        while (remaining > 0) {
            // Focus block
            val focusDuration = if (remaining < focusSec) remaining else focusSec
            blocks.add(TimeBlock(startTime = currentTime, durationMinutes = (focusDuration + 59) / 60, type = BlockType.FOCUS))
            remaining -= focusDuration
            currentTime += focusDuration * 1000L

            if (remaining <= 0) break

            // Rest block
            if (restSec > 0) {
                val restDuration = if (remaining < restSec) remaining else restSec
                blocks.add(TimeBlock(startTime = currentTime, durationMinutes = (restDuration + 59) / 60, type = BlockType.REST))
                remaining -= restDuration
                currentTime += restDuration * 1000L
            }
        }
        return blocks
    }

    fun startSession(taskId: String?, title: String?, scheduleId: String? = null) {
        val state = _uiState.value
        _uiState.update { it.copy(
            isTimerActive = true,
            isRunning = true,
            selectedTaskId = taskId,
            selectedTaskTitle = title,
            currentScheduleId = scheduleId,
            totalRemainingSeconds = state.sessionTotalMinutes * 60
        ) }
        
        viewModelScope.launch {
            timerService?.setTimerConfig(
                interval = state.alarmIntervalMinutes,
                rest = state.restMinutes,
                totalSec = state.sessionTotalMinutes * 60,
                title = title ?: "",
                vibrate = state.vibrationEnabled,
                sound = state.soundEnabled,
                useFullScreen = state.useFullScreenAlarm,
                focusPatternId = state.focusVibrationPatternId,
                restPatternId = state.restVibrationPatternId,
                finishPatternId = state.finishVibrationPatternId,
                focusSound = state.focusSoundId,
                restSound = state.restSoundId,
                finishSound = state.finishSoundId,
                focusRingtoneUri = state.focusRingtoneUri,
                restRingtoneUri = state.restRingtoneUri,
                finishRingtoneUri = state.finishRingtoneUri,
                onTransition = { t, e, f, bt, isSkip -> onBlockTransition(t, e, f, bt, isSkip) },
                onFinished = { onSessionFinished() }
            )
            timerService?.startTimer(state.sessionTotalMinutes * 60)
        }
    }

    fun skipBlock() {
        if (!_uiState.value.isRunning) return
        // [정책 9] 넘기기 시에는 팝업으로 알림을 주기 위해 플래그 전달
        timerService?.skipToNext()
    }

    fun onBlockTransition(
        taskTitle: String, 
        elapsedMinutes: Int, 
        isFinished: Boolean, 
        blockType: BlockType,
        isManualSkip: Boolean = false // [정책 9] 파라미터 추가
    ) {
        val state = _uiState.value
        
        // [v1.7.3] isRunning 조작 제거 (서비스의 상태 Flow를 절대적 진실로 따름)
        if (isFinished) {
            _uiState.update { it.copy(isTimerActive = false) }
        }
        
        val focusPattern = VibrationPattern.fromId(state.focusVibrationPatternId).pattern
        val restPattern = VibrationPattern.fromId(state.restVibrationPatternId).pattern
        val finishVibPattern = VibrationPattern.fromId(state.finishVibrationPatternId).pattern

        val ringtoneUri = when {
            isFinished -> state.finishRingtoneUri
            blockType == BlockType.FOCUS -> state.focusRingtoneUri
            else -> state.restRingtoneUri
        }
        
        val focusSound = state.focusSoundId
        val restSound = state.restSoundId
        val finishSound = state.finishSoundId
        
        val currentSoundId = when {
            isFinished -> finishSound
            blockType == BlockType.FOCUS -> focusSound
            else -> restSound
        }

        // [v1.7.3] 알람 노출 모드 결정 트리 (README_ALARM.md 규칙 1, 2, 3 준수)
        // 1. 벨소리(ringtone)라면 무조건 전체 화면
        // 2. 그 외에는 유저의 useFullScreenAlarm 설정을 따름 (종료 알람 포함)
        val useFullScreen = currentSoundId == "ringtone" || state.useFullScreenAlarm

        notificationHelper.showBlockTransitionNotification(
            taskTitle = taskTitle, 
            elapsedMinutes = elapsedMinutes, 
            isFinished = isFinished,
            currentBlockType = blockType,
            focusVibrationPattern = focusPattern,
            restVibrationPattern = restPattern,
            finishVibrationPattern = finishVibPattern,
            focusSoundId = focusSound,
            restSoundId = restSound,
            finishSoundId = finishSound,
            vibrationEnabled = state.vibrationEnabled,
            soundEnabled = state.soundEnabled,
            ringtoneUri = ringtoneUri,
            useFullScreen = useFullScreen,
            isManualSkip = isManualSkip // 정책 반영
        )
    }

    fun onSessionFinished() {
        val state = _uiState.value
        val taskId = state.selectedTaskId
        val scheduleId = state.currentScheduleId

        viewModelScope.launch {
            // [v1.7.3] 세션 완료 시 해당 작업 자동 완료 처리
            if (scheduleId != null) {
                scheduleRepository.getScheduleById(scheduleId)?.let { schedule ->
                    scheduleRepository.updateSchedule(schedule.copy(isCompleted = true))
                    repository.updateTaskCompletion(scheduleId, true)
                }
            } else if (taskId != null) {
                repository.updateTaskCompletion(taskId, true)
                // [v1.8.0] 현재 로드된 오늘 작업 리스트에서도 즉시 체크 표시 반영
                _uiState.update { currentState ->
                    val updatedTasks = currentState.tasks.map { 
                        if (it.id == taskId) it.copy(isCompleted = true) else it 
                    }
                    currentState.copy(tasks = updatedTasks)
                }
            }
            
            // [v1.8.0-fix] 세션 종료 시 즉시 stopTimer를 호출하면 알람 소리/진동이 즉시 정지되는 버그가 있어,
            // 서비스 종료 및 알람 정지 처리는 AlarmActivity의 '중단' 버튼 클릭이나 20초 타임아웃 시점으로 위임합니다.
            // timerService?.stopTimer()

            _uiState.update { currentState ->
                val defTotalSec = currentState.defaultTotalMinutes * 60
                val defIntervalSec = currentState.storedAlarmIntervalMinutes * 60
                currentState.copy(
                    isRunning = false,
                    isTimerActive = false,
                    totalRemainingSeconds = defTotalSec,
                    remainingSeconds = defIntervalSec,
                    currentBlockIndex = 0,
                    selectedTaskId = null,
                    selectedTaskTitle = "작업을 선택하세요",
                    currentScheduleId = null,
                    timeBlocks = generateBlocks(currentState.storedAlarmIntervalMinutes, currentState.storedRestMinutes, defTotalSec)
                )
            }
        }
    }

    fun stopSoundPreview() {
        notificationHelper.stopSound()
    }

    fun selectDate(date: Long) {
        _selectedDate.value = date
    }

    fun setCalendarMonthlyView(isMonthly: Boolean) {
        _uiState.update { it.copy(isCalendarMonthlyView = isMonthly) }
    }

    fun clearSelectedBlocks() {
        _uiState.update { it.copy(selectedBlocks = emptySet(), selectionAnchor = null) }
    }

    fun toggleBlock(blockTime: Long) {
        _uiState.update { state ->
            val anchor = state.selectionAnchor
            if (anchor == null) {
                // 첫 선택: 앵커로 설정
                state.copy(
                    selectedBlocks = setOf(blockTime),
                    selectionAnchor = blockTime
                )
            } else if (anchor == blockTime) {
                // 앵커를 다시 누르면 전체 취소
                state.copy(
                    selectedBlocks = emptySet(),
                    selectionAnchor = null
                )
            } else {
                // 범위 선택: 앵커와 현재 터치 지점 사이의 블록 선택
                // [v1.8.0] 장애물(기존 일정)이 있으면 그 전까지만 선택되도록 개선
                val intervalMillis = 15 * 60 * 1000L
                val isForward = blockTime > anchor
                
                val newSelected = mutableSetOf<Long>()
                
                if (isForward) {
                    var current = anchor
                    while (current <= blockTime) {
                        val isOverlapping = state.dailySchedules.any { s ->
                            val sStart = s.startTimeMillis
                            val sEnd = sStart + s.durationMinutes * 60 * 1000L
                            current >= sStart && current < sEnd
                        }
                        if (isOverlapping && current != anchor) break // 앵커 제외 장애물 발견 시 중단
                        newSelected.add(current)
                        current += intervalMillis
                    }
                } else {
                    var current = anchor
                    while (current >= blockTime) {
                        val isOverlapping = state.dailySchedules.any { s ->
                            val sStart = s.startTimeMillis
                            val sEnd = sStart + s.durationMinutes * 60 * 1000L
                            current >= sStart && current < sEnd
                        }
                        if (isOverlapping && current != anchor) break
                        newSelected.add(current)
                        current -= intervalMillis
                    }
                }
                
                state.copy(
                    selectedBlocks = newSelected
                )
            }
        }
    }

    fun loadScheduledSession(schedule: ScheduleBlock) {
        _uiState.update { it.copy(
            selectedTaskId = schedule.id, // [v1.7.6-patch] 스케줄 ID를 taskId로 활용하여 추적성 확보
            selectedTaskTitle = schedule.taskTitle,
            currentScheduleId = schedule.id,
            sessionTotalMinutes = schedule.durationMinutes,
            alarmIntervalMinutes = schedule.intervalMinutes,
            restMinutes = schedule.restMinutes,
            isTimerActive = false
        ) }
    }

    fun addSchedule(
        taskTitle: String,
        durationMinutes: Int,
        startTimeHour: Int,
        startTimeMinute: Int,
        startNewSession: Boolean = false,
        intervalMinutes: Int = 15,
        restMinutes: Int = 0
    ) {
        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = _selectedDate.value
                set(Calendar.HOUR_OF_DAY, startTimeHour)
                set(Calendar.MINUTE, startTimeMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            
            val schedule = ScheduleBlock(
                taskTitle = taskTitle,
                startTimeMillis = calendar.timeInMillis,
                durationMinutes = durationMinutes,
                intervalMinutes = intervalMinutes,
                restMinutes = restMinutes
            )
            scheduleRepository.insertSchedule(schedule)
            
            // Task 테이블에도 추가 (오늘 날짜인 경우)
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val scheduleDate = Calendar.getInstance().apply {
                timeInMillis = calendar.timeInMillis
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            if (scheduleDate == todayStart) {
                repository.insertTask(Task(
                    id = schedule.id,
                    title = taskTitle,
                    scheduledDateMillis = scheduleDate,
                    startTimeMillis = calendar.timeInMillis
                ))
            }

            if (startNewSession) {
                loadScheduledSession(schedule)
            }
        }
    }

    fun updateSchedule(schedule: ScheduleBlock, newTitle: String, newDuration: Int) {
        viewModelScope.launch {
            val updated = schedule.copy(taskTitle = newTitle, durationMinutes = newDuration)
            scheduleRepository.updateSchedule(updated)
            // Task 테이블 동기화
            repository.updateTask(Task(
                id = updated.id,
                title = newTitle,
                scheduledDateMillis = updated.startTimeMillis, // 단순화
                startTimeMillis = updated.startTimeMillis,
                durationMinutes = updated.durationMinutes
            ))
        }
    }

    fun stopTimer() {
        timerService?.stopTimer()
        _uiState.update { state ->
            val defTotalMin = state.defaultTotalMinutes
            val defIntervalMin = state.storedAlarmIntervalMinutes
            val defRestMin = state.storedRestMinutes
            val defTotalSec = defTotalMin * 60
            val defIntervalSec = defIntervalMin * 60
            
            state.copy(
                isRunning = false,
                isTimerActive = false,
                selectedTaskId = null,
                selectedTaskTitle = "작업을 선택하세요",
                currentScheduleId = null,
                sessionTotalMinutes = defTotalMin,
                alarmIntervalMinutes = defIntervalMin,
                restMinutes = defRestMin,
                totalRemainingSeconds = defTotalSec,
                remainingSeconds = defIntervalSec,
                currentBlockIndex = 0,
                timeBlocks = generateBlocks(defIntervalMin, defRestMin, defTotalSec)
            )
        }
    }

    fun toggleTimer() {
        val state = _uiState.value
        val service = timerService ?: return
        
        // [v1.7.3-patch2] UI 상태가 아닌 서비스의 실제 상태를 직접 체크하여 상태 전이 루프 방지
        if (service.isRunning.value) {
            service.pauseTimer()
        } else {
            // 만약 현재 활성화된 세션이 없다면 선택된 제목이나 작업으로 시작
            if (!state.isTimerActive) {
                val selectedTask = state.tasks.find { it.id == state.selectedTaskId }
                
                // [v1.8.0] 안내 문구("작업을 선택하세요")인 경우 "집중 세션"으로 치환하여 시작
                val currentTitle = state.selectedTaskTitle
                val finalTitle = if (currentTitle == "작업을 선택하세요" || currentTitle == null) {
                    selectedTask?.title ?: "집중 세션"
                } else {
                    currentTitle
                }
                
                startSession(state.selectedTaskId, finalTitle, state.currentScheduleId)
            } else {
                service.startTimer(state.totalRemainingSeconds)
            }
        }
    }

    fun selectTask(taskId: String?) {
        // 이미 선택된 작업을 다시 터치하면 선택 해제 (일반 모드로 복구)
        if (taskId != null && _uiState.value.selectedTaskId == taskId) {
            selectTask(null)
            return
        }

        if (taskId == null) {
            _uiState.update { state ->
                val defTotalMin = state.defaultTotalMinutes
                val defIntervalMin = state.storedAlarmIntervalMinutes
                val totalSec = defTotalMin * 60
                val intervalSec = defIntervalMin * 60
                
                state.copy(
                    selectedTaskId = null,
                    selectedTaskTitle = "작업을 선택하세요",
                    currentScheduleId = null,
                    sessionTotalMinutes = defTotalMin,
                    alarmIntervalMinutes = defIntervalMin,
                    restMinutes = state.storedRestMinutes,
                    totalRemainingSeconds = totalSec,
                    remainingSeconds = intervalSec,
                    currentBlockIndex = 0,
                    isTimerActive = false,
                    isRunning = false,
                    timeBlocks = generateBlocks(defIntervalMin, state.storedRestMinutes, totalSec)
                )
            }
            timerService?.stopTimer()
            return
        }

        val task = _uiState.value.tasks.find { it.id == taskId } ?: return
        // allSchedules에서 일치하는 스케줄이 있는지 확인 (taskId가 schedule.id와 동일할 수 있음)
        val schedule = _uiState.value.allSchedules.find { it.id == taskId || it.taskTitle == task.title }
        
        _uiState.update { state ->
            if (schedule != null) {
                // 스케줄 정보가 있는 경우 타이머 설정 동기화
                val totalSec = schedule.durationMinutes * 60
                state.copy(
                    selectedTaskId = taskId,
                    selectedTaskTitle = task.title,
                    currentScheduleId = schedule.id,
                    sessionTotalMinutes = schedule.durationMinutes,
                    alarmIntervalMinutes = schedule.intervalMinutes,
                    restMinutes = schedule.restMinutes,
                    totalRemainingSeconds = totalSec,
                    remainingSeconds = schedule.intervalMinutes * 60,
                    isTimerActive = false,
                    timeBlocks = generateBlocks(
                        schedule.intervalMinutes, 
                        schedule.restMinutes, 
                        totalSec,
                        startTime = schedule.startTimeMillis
                    )
                )
            } else {
                // 일반 Task인 경우 기본 설정 사용
                val totalSec = state.defaultTotalMinutes * 60
                state.copy(
                    selectedTaskId = taskId,
                    selectedTaskTitle = task.title,
                    currentScheduleId = null,
                    sessionTotalMinutes = state.defaultTotalMinutes,
                    alarmIntervalMinutes = state.storedAlarmIntervalMinutes,
                    restMinutes = state.storedRestMinutes,
                    totalRemainingSeconds = totalSec,
                    remainingSeconds = state.storedAlarmIntervalMinutes * 60,
                    isTimerActive = false,
                    timeBlocks = generateBlocks(state.storedAlarmIntervalMinutes, state.storedRestMinutes, totalSec)
                )
            }
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            val newStatus = !task.isCompleted
            // Task 테이블 업데이트
            repository.updateTaskCompletion(task.id, newStatus)
            
            // ScheduleBlock 테이블 업데이트 (동기화)
            val schedule = _uiState.value.allSchedules.find { it.id == task.id }
            if (schedule != null) {
                scheduleRepository.updateSchedule(schedule.copy(isCompleted = newStatus))
            }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            // Task 테이블에서 삭제
            repository.deleteTask(task)
            
            // ScheduleBlock 테이블에서도 삭제 (동기화)
            val schedule = _uiState.value.allSchedules.find { it.id == task.id }
            if (schedule != null) {
                scheduleRepository.deleteSchedule(schedule)
            }
            
            // 현재 선택된 작업이 삭제된 경우 선택 해제
            if (_uiState.value.selectedTaskId == task.id) {
                selectTask(null)
            }
        }
    }

    fun deleteSchedule(schedule: ScheduleBlock) {
        viewModelScope.launch {
            // ScheduleBlock 삭제
            scheduleRepository.deleteSchedule(schedule)
            
            // Task 테이블에서도 연동 삭제
            repository.getTaskById(schedule.id)?.let {
                repository.deleteTask(it)
            }
            
            // 현재 선택된 작업이 삭제된 경우 선택 해제
            if (_uiState.value.currentScheduleId == schedule.id || _uiState.value.selectedTaskId == schedule.id) {
                selectTask(null)
            }
        }
    }

    fun setDarkMode(mode: Int) {
        viewModelScope.launch {
            settingsRepository.setDarkMode(mode)
        }
    }

    fun setFontSizeScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.setFontSizeScale(scale)
        }
    }

    fun setFocusRingtoneUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setFocusRingtoneUri(uri)
        }
    }

    fun setRestRingtoneUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setRestRingtoneUri(uri)
        }
    }

    fun setFinishRingtoneUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setFinishRingtoneUri(uri)
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSoundEnabled(enabled)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVibrationEnabled(enabled)
        }
    }

    fun setFocusSound(soundId: String) {
        viewModelScope.launch {
            settingsRepository.setFocusSoundId(soundId)
        }
    }

    fun setRestSound(soundId: String) {
        viewModelScope.launch {
            settingsRepository.setRestSoundId(soundId)
        }
    }

    fun setFinishSound(soundId: String) {
        viewModelScope.launch {
            settingsRepository.setFinishSoundId(soundId)
        }
    }

    fun setFocusVibrationPattern(patternId: String) {
        viewModelScope.launch {
            settingsRepository.setFocusVibrationPatternId(patternId)
        }
    }

    fun setRestVibrationPattern(patternId: String) {
        viewModelScope.launch {
            settingsRepository.setRestVibrationPatternId(patternId)
        }
    }

    fun setFinishVibrationPattern(patternId: String) {
        viewModelScope.launch {
            settingsRepository.setFinishVibrationPatternId(patternId)
        }
    }

    fun previewSound(soundId: String, type: String) {
        val state = _uiState.value
        val uri = when (type) {
            "focus" -> state.focusRingtoneUri
            "rest" -> state.restRingtoneUri
            else -> state.finishRingtoneUri
        }
        notificationHelper.playSound(soundId, uri, isLooping = false)
    }

    fun previewVibration(patternId: String) {
        notificationHelper.vibratePreview(patternId)
    }

    fun saveSettings(
        alarmInterval: Int,
        restMinutes: Int,
        vibrationEnabled: Boolean,
        soundEnabled: Boolean,
        calendarSyncEnabled: Boolean,
        focusPatternId: String,
        restPatternId: String,
        finishPatternId: String,
        focusSoundId: String,
        restSoundId: String,
        finishSoundId: String,
        defaultTotalMinutes: Int,
        darkMode: Int,
        focusRingtoneUri: String?,
        restRingtoneUri: String?,
        finishRingtoneUri: String?,
        useFullScreen: Boolean
    ) {
        viewModelScope.launch {
            settingsRepository.setAlarmIntervalMinutes(alarmInterval)
            settingsRepository.setRestMinutes(restMinutes)
            settingsRepository.setVibrationEnabled(vibrationEnabled)
            settingsRepository.setSoundEnabled(soundEnabled)
            settingsRepository.setCalendarSyncEnabled(calendarSyncEnabled)
            settingsRepository.setFocusVibrationPatternId(focusPatternId)
            settingsRepository.setRestVibrationPatternId(restPatternId)
            settingsRepository.setFinishVibrationPatternId(finishPatternId)
            settingsRepository.setFocusSoundId(focusSoundId)
            settingsRepository.setRestSoundId(restSoundId)
            settingsRepository.setFinishSoundId(finishSoundId)
            settingsRepository.setDefaultTotalMinutes(defaultTotalMinutes)
            settingsRepository.setDarkMode(darkMode)
            settingsRepository.setFocusRingtoneUri(focusRingtoneUri)
            settingsRepository.setRestRingtoneUri(restRingtoneUri)
            settingsRepository.setFinishRingtoneUri(finishRingtoneUri)
            settingsRepository.setUseFullScreenAlarm(useFullScreen)
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${app.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
    }

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(app)
    }

    fun requestDrawOverlaysPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${app.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
    }

    fun vibratePreview(patternId: String) {
        notificationHelper.vibratePreview(patternId)
    }

    fun playSound(soundId: String, uri: String? = null) {
        notificationHelper.playSound(soundId, uri, isLooping = false)
    }

    companion object {
        fun Factory(
            app: Application,
            repository: TaskRepository,
            settingsRepository: SettingsRepository,
            statsRepository: StatsRepository,
            scheduleRepository: ScheduleRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return SchedulerViewModel(app, repository, settingsRepository, statsRepository, scheduleRepository) as T
            }
        }
    }
}
