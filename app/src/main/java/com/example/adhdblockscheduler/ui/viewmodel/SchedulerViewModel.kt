package com.example.adhdblockscheduler.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.adhdblockscheduler.model.BlockType
import com.example.adhdblockscheduler.model.DailyStats
import com.example.adhdblockscheduler.model.ScheduleBlock
import com.example.adhdblockscheduler.model.Task
import com.example.adhdblockscheduler.model.TimeBlock
import com.example.adhdblockscheduler.data.repository.ScheduleRepository
import com.example.adhdblockscheduler.data.prefs.SettingsRepository
import com.example.adhdblockscheduler.data.repository.StatsRepository
import com.example.adhdblockscheduler.data.repository.TaskRepository
import com.example.adhdblockscheduler.service.TimerService
import com.example.adhdblockscheduler.util.NotificationHelper
import com.example.adhdblockscheduler.util.VibrationPattern
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

data class SchedulerUiState(
    val tasks: List<Task> = emptyList(),
    val selectedTaskId: String? = null,
    val selectedTaskTitle: String? = null,
    val timeBlocks: List<TimeBlock> = emptyList(),
    val currentBlockIndex: Int = 0,
    val remainingSeconds: Int = 0,
    val isRunning: Boolean = false,
    val isTimerActive: Boolean = false,
    val sessionTotalMinutes: Int = 60,
    val totalRemainingSeconds: Int = 0,
    val vibrationEnabled: Boolean = true,
    val alarmIntervalMinutes: Int = 15,
    val calendarSyncEnabled: Boolean = false,
    val selectedBlocks: Set<Long> = emptySet(),
    val dailySchedules: List<ScheduleBlock> = emptyList(),
    val allSchedules: List<ScheduleBlock> = emptyList(),
    val currentScheduleId: String? = null,
    val selectionAnchor: Long? = null,
    val activeSessionInterval: Int = 15,
    val restMinutes: Int = 0,
    val focusVibrationPatternId: String = "focus_default",
    val restVibrationPatternId: String = "rest_default",
    val finishVibrationPatternId: String = "rest_default",
    val focusSoundId: String = "default",
    val restSoundId: String = "default",
    val finishSoundId: String = "default",
    val defaultTotalMinutes: Int = 60,
    val soundEnabled: Boolean = true,
    val recentStats: List<DailyStats> = emptyList(),
    val isCalendarMonthlyView: Boolean = true
)

class SchedulerViewModel(
    private val app: Application,
    private val repository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val statsRepository: StatsRepository,
    private val scheduleRepository: ScheduleRepository
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(SchedulerUiState())
    private val _selectedDate = MutableStateFlow(System.currentTimeMillis())
    val selectedDate: StateFlow<Long> = _selectedDate.asStateFlow()

    private val notificationHelper = NotificationHelper(app)
    
    private var timerService: TimerService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.TimerBinder
            timerService = binder.getService()
            isBound = true

            viewModelScope.launch {
                timerService?.totalRemainingSeconds?.collect { seconds ->
                    _uiState.update { it.copy(totalRemainingSeconds = seconds) }
                }
            }

            viewModelScope.launch {
                timerService?.remainingSeconds?.collect { seconds ->
                    _uiState.update { it.copy(remainingSeconds = seconds) }
                }
            }

            viewModelScope.launch {
                timerService?.currentBlockIndex?.collect { index ->
                    _uiState.update { it.copy(currentBlockIndex = index) }
                }
            }

            viewModelScope.launch {
                timerService?.isRunning?.collect { running ->
                    _uiState.update { it.copy(isRunning = running) }
                    
                    val state = _uiState.value
                    if ((running || state.totalRemainingSeconds > 0) && state.timeBlocks.isEmpty()) {
                        generateDefaultBlocks(state.activeSessionInterval, state.sessionTotalMinutes)
                    }
                }
            }

            viewModelScope.launch {
                timerService?.config?.collect { config ->
                    _uiState.update { it.copy(
                        isTimerActive = config != null,
                        sessionTotalMinutes = config?.totalSecondsAtStart?.let { it / 60 } ?: it.sessionTotalMinutes,
                        activeSessionInterval = config?.intervalMinutes ?: it.activeSessionInterval,
                        restMinutes = config?.restMinutes ?: it.restMinutes,
                        vibrationEnabled = config?.vibrationEnabled ?: it.vibrationEnabled,
                        selectedTaskTitle = config?.taskTitle ?: it.selectedTaskTitle
                    ) }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName?) {
            isBound = false
            timerService = null
        }
    }

    init {
        val intent = Intent(app, TimerService::class.java)
        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        viewModelScope.launch {
            settingsRepository.alarmIntervalMinutes.collect { interval ->
                _uiState.update { state ->
                    val isSessionActive = state.isRunning || state.totalRemainingSeconds > 0
                    if (!isSessionActive) {
                        state.copy(
                            alarmIntervalMinutes = interval,
                            activeSessionInterval = interval
                        )
                    } else {
                        state.copy(alarmIntervalMinutes = interval)
                    }
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.restMinutes.collect { rest ->
                _uiState.update { state ->
                    val isSessionActive = state.isRunning || state.totalRemainingSeconds > 0
                    if (!isSessionActive) {
                        state.copy(restMinutes = rest)
                    } else {
                        state.copy(restMinutes = rest)
                    }
                }
                if (!_uiState.value.isRunning && _uiState.value.totalRemainingSeconds <= 0) {
                    generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, _uiState.value.sessionTotalMinutes)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.vibrationEnabled.collect { enabled ->
                _uiState.update { it.copy(vibrationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.focusVibrationPatternId.collect { id ->
                _uiState.update { it.copy(focusVibrationPatternId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.restVibrationPatternId.collect { id ->
                _uiState.update { it.copy(restVibrationPatternId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.focusSoundId.collect { id ->
                _uiState.update { it.copy(focusSoundId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.restSoundId.collect { id ->
                _uiState.update { it.copy(restSoundId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.finishSoundId.collect { id ->
                _uiState.update { it.copy(finishSoundId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.defaultTotalMinutes.collect { mins ->
                _uiState.update { it.copy(defaultTotalMinutes = mins) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            app.unbindService(connection)
            isBound = false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SchedulerUiState> = combine(
        _uiState,
        settingsRepository.vibrationEnabled,
        settingsRepository.soundEnabled,
        settingsRepository.alarmIntervalMinutes,
        settingsRepository.restMinutes,
        settingsRepository.focusVibrationPatternId,
        settingsRepository.restVibrationPatternId,
        settingsRepository.focusSoundId,
        settingsRepository.restSoundId,
        settingsRepository.finishSoundId,
        settingsRepository.defaultTotalMinutes,
        _selectedDate.flatMapLatest { scheduleRepository.getSchedulesForDay(it) },
        scheduleRepository.getAllSchedules(),
        statsRepository.recentStats,
        // 타이머 탭 전용: 무조건 '오늘'의 태스크만 가져옴 (요구사항 6번)
        repository.getTasksForDate(getTodayStartMillis()).flatMapLatest { todayTasks ->
            scheduleRepository.getSchedulesForDay(getTodayStartMillis()).map { todaySchedules ->
                combineTasksAndSchedules(todayTasks, todaySchedules, getTodayStartMillis())
            }
        }
    ) { params ->
        val state = params[0] as SchedulerUiState
        val vibration = params[1] as Boolean
        val sound = params[2] as Boolean
        val alarmInterval = params[3] as Int
        val defaultRest = params[4] as Int
        val focusPatternId = params[5] as String
        val restPatternId = params[6] as String
        val focusSndId = params[7] as String
        val restSndId = params[8] as String
        val finishSndId = params[9] as String
        val defTotalMins = params[10] as Int
        @Suppress("UNCHECKED_CAST")
        val dailySchedules = params[11] as List<ScheduleBlock>
        @Suppress("UNCHECKED_CAST")
        val allSchedules = params[12] as List<ScheduleBlock>
        @Suppress("UNCHECKED_CAST")
        val recentStats = params[13] as List<DailyStats>
        @Suppress("UNCHECKED_CAST")
        val todayTasks = params[14] as List<Task>

        val isSessionActive = state.isRunning || (state.totalRemainingSeconds > 0)
        val isTimerActive = state.isTimerActive
        
        // 타이머 탭에서 세션 활성/일시정지 중이면 진행 중인 작업만 노출 (버그 1 수정)
        val timerTabTasks = if (isTimerActive && state.selectedTaskId != null) {
            val activeTask = todayTasks.find { it.id == state.selectedTaskId } ?: 
                allSchedules.find { "sched_${it.id}" == state.selectedTaskId }?.let { schedule ->
                    Task(
                        id = "sched_${schedule.id}",
                        title = schedule.taskTitle,
                        scheduledDateMillis = schedule.startTimeMillis,
                        isCompleted = schedule.isCompleted,
                        startTimeMillis = schedule.startTimeMillis
                    )
                }
            if (activeTask != null) listOf(activeTask) else emptyList()
        } else if (state.selectedTaskId != null && todayTasks.none { it.id == state.selectedTaskId }) {
            val selectedTask = allSchedules.find { "sched_${it.id}" == state.selectedTaskId }?.let { schedule ->
                Task(
                    id = "sched_${schedule.id}",
                    title = schedule.taskTitle,
                    scheduledDateMillis = schedule.startTimeMillis,
                    isCompleted = schedule.isCompleted,
                    startTimeMillis = schedule.startTimeMillis
                )
            }
            if (selectedTask != null) {
                (todayTasks + selectedTask).sortedWith(compareBy({ it.startTimeMillis == 0L }, { it.startTimeMillis }, { it.createdAt }))
            } else {
                todayTasks
            }
        } else {
            todayTasks
        }

        val effectiveAlarmInterval = if (isSessionActive) {
            state.activeSessionInterval
        } else {
            alarmInterval
        }

        val effectiveRest = if (isSessionActive) {
            state.restMinutes
        } else {
            defaultRest
        }

        state.copy(
            tasks = timerTabTasks,
            vibrationEnabled = vibration,
            soundEnabled = sound,
            alarmIntervalMinutes = effectiveAlarmInterval,
            restMinutes = effectiveRest,
            focusVibrationPatternId = focusPatternId,
            restVibrationPatternId = restPatternId,
            focusSoundId = focusSndId,
            restSoundId = restSndId,
            finishSoundId = finishSndId,
            defaultTotalMinutes = defTotalMins,
            dailySchedules = dailySchedules,
            allSchedules = allSchedules,
            recentStats = recentStats,
            isCalendarMonthlyView = state.isCalendarMonthlyView
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SchedulerUiState()
    )

    private fun getTodayStartMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun combineTasksAndSchedules(tasks: List<Task>, schedules: List<ScheduleBlock>, dayStart: Long): List<Task> {
        val combined = tasks.toMutableList()
        schedules.forEach { schedule ->
            val cal = Calendar.getInstance().apply { timeInMillis = schedule.startTimeMillis }
            val endCal = Calendar.getInstance().apply { 
                timeInMillis = schedule.startTimeMillis + schedule.durationMinutes * 60 * 1000L 
            }
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d ~ %02d:%02d", 
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
                endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE))
            
            val displayTitle = "${schedule.taskTitle} $timeStr"
            val scheduleId = "sched_${schedule.id}"
            
            val existingIndex = combined.indexOfFirst { it.id == scheduleId }
            val task = Task(
                id = scheduleId, 
                title = displayTitle, 
                scheduledDateMillis = dayStart,
                isCompleted = schedule.isCompleted,
                startTimeMillis = schedule.startTimeMillis
            )
            
            if (existingIndex != -1) {
                combined[existingIndex] = task
            } else {
                combined.add(task)
            }
        }
        return combined.sortedWith(compareBy({ it.startTimeMillis == 0L }, { it.startTimeMillis }, { it.createdAt }))
    }

    private fun generateDefaultBlocks(intervalMinutes: Int, totalMinutes: Int) {
        val blocks = mutableListOf<TimeBlock>()
        val restMin = _uiState.value.restMinutes
        
        if (restMin <= 0) {
            val numBlocks = (totalMinutes + intervalMinutes - 1) / intervalMinutes
            for (i in 0 until numBlocks) {
                blocks.add(TimeBlock(
                    startTime = i * intervalMinutes * 60L,
                    durationMinutes = intervalMinutes,
                    type = BlockType.FOCUS
                ))
            }
        } else {
            var currentMinutes = 0
            while (currentMinutes < totalMinutes) {
                val focusDuration = if (currentMinutes + intervalMinutes > totalMinutes) {
                    totalMinutes - currentMinutes
                } else {
                    intervalMinutes
                }
                blocks.add(TimeBlock(
                    startTime = currentMinutes * 60L,
                    durationMinutes = focusDuration,
                    type = BlockType.FOCUS
                ))
                currentMinutes += focusDuration
                
                if (currentMinutes < totalMinutes) {
                    val restDuration = if (currentMinutes + restMin > totalMinutes) {
                        totalMinutes - currentMinutes
                    } else {
                        restMin
                    }
                    blocks.add(TimeBlock(
                        startTime = currentMinutes * 60L,
                        durationMinutes = restDuration,
                        type = BlockType.REST
                    ))
                    currentMinutes += restDuration
                }
            }
        }
        _uiState.update { it.copy(timeBlocks = blocks) }
    }

    fun addSchedule(
        taskTitle: String, 
        startTimeHour: Int, 
        startTimeMinute: Int, 
        durationMinutes: Int, 
        startNewSession: Boolean = false,
        intervalMinutes: Int = _uiState.value.alarmIntervalMinutes,
        restMinutes: Int = _uiState.value.restMinutes,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val startTime = Calendar.getInstance().apply {
                timeInMillis = _selectedDate.value
                set(Calendar.HOUR_OF_DAY, startTimeHour)
                set(Calendar.MINUTE, startTimeMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val schedule = ScheduleBlock(
                taskTitle = taskTitle,
                startTimeMillis = startTime,
                durationMinutes = durationMinutes,
                intervalMinutes = intervalMinutes,
                restMinutes = restMinutes
            )
            val id = scheduleRepository.insertSchedule(schedule)
            val scheduleWithId = schedule.copy(id = id.toString())
            
            loadScheduledSession(scheduleWithId)
            onComplete()
        }
    }

    fun loadScheduledSession(schedule: ScheduleBlock) {
        if (_uiState.value.isRunning) return
        
        val taskInterval = schedule.intervalMinutes
        val taskRest = schedule.restMinutes
        
        _uiState.update { it.copy(
            selectedTaskId = "sched_${schedule.id}",
            selectedTaskTitle = schedule.taskTitle,
            sessionTotalMinutes = schedule.durationMinutes,
            currentScheduleId = schedule.id,
            activeSessionInterval = taskInterval,
            restMinutes = taskRest,
            remainingSeconds = taskInterval * 60,
            totalRemainingSeconds = schedule.durationMinutes * 60
        ) }
        
        generateDefaultBlocks(taskInterval, schedule.durationMinutes)
    }

    fun startNewSession(taskTitle: String, totalMinutes: Int, hourOfDay: Int? = null) {
        if (_uiState.value.isRunning) return
        
        viewModelScope.launch {
            val startTime = Calendar.getInstance().apply {
                timeInMillis = _selectedDate.value
                if (hourOfDay != null) set(Calendar.HOUR_OF_DAY, hourOfDay)
                else {
                    val now = Calendar.getInstance()
                    set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
                }
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val newSchedule = ScheduleBlock(
                taskTitle = taskTitle,
                startTimeMillis = startTime,
                durationMinutes = totalMinutes
            )
            val id = scheduleRepository.insertSchedule(newSchedule)
            val scheduleWithId = newSchedule.copy(id = id.toString())
            
            val currentInterval = _uiState.value.alarmIntervalMinutes
            _uiState.update { it.copy(
                selectedTaskId = "sched_${scheduleWithId.id}",
                currentScheduleId = scheduleWithId.id,
                sessionTotalMinutes = totalMinutes,
                activeSessionInterval = currentInterval,
                totalRemainingSeconds = totalMinutes * 60
            ) }
            
            generateDefaultBlocks(currentInterval, totalMinutes)
        }
    }

    fun selectDate(dateMillis: Long) {
        _selectedDate.value = dateMillis
    }

    fun selectTask(taskId: String?) {
        // 실제 타이머가 작동 중(진행/일시정지)일 때는 선택 변경 불가
        if (_uiState.value.isTimerActive) return

        if (_uiState.value.selectedTaskId == taskId || taskId == null) {
            // 이미 선택된 작업을 다시 누르면 선택 취소 (버그 3 수정)
            _uiState.update { it.copy(
                selectedTaskId = null,
                selectedTaskTitle = null,
                currentScheduleId = null,
                sessionTotalMinutes = 60,
                totalRemainingSeconds = 0
            ) }
            generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, 60)
        } else {
            val scheduleId = if (taskId.startsWith("sched_")) taskId.removePrefix("sched_") else null
            if (scheduleId != null) {
                viewModelScope.launch {
                    scheduleRepository.getScheduleById(scheduleId)?.let { schedule ->
                        _uiState.update { it.copy(
                            selectedTaskId = taskId,
                            selectedTaskTitle = schedule.taskTitle,
                            currentScheduleId = scheduleId,
                            sessionTotalMinutes = schedule.durationMinutes,
                            activeSessionInterval = schedule.intervalMinutes,
                            restMinutes = schedule.restMinutes,
                            remainingSeconds = schedule.intervalMinutes * 60,
                            totalRemainingSeconds = schedule.durationMinutes * 60
                        ) }
                        generateDefaultBlocks(schedule.intervalMinutes, schedule.durationMinutes)
                    }
                }
            } else {
                // Regular task
                val taskTitle = _uiState.value.tasks.find { it.id == taskId }?.title
                _uiState.update { it.copy(
                    selectedTaskId = taskId,
                    selectedTaskTitle = taskTitle,
                    currentScheduleId = null,
                    sessionTotalMinutes = 60,
                    totalRemainingSeconds = 60 * 60 // 1 hour default
                ) }
                generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, 60)
            }
        }
    }

    fun addTask(title: String) {
        viewModelScope.launch {
            repository.insertTask(Task(title = title, scheduledDateMillis = _selectedDate.value))
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            if (task.id.startsWith("sched_")) {
                val scheduleId = task.id.removePrefix("sched_")
                scheduleRepository.getScheduleById(scheduleId)?.let {
                    scheduleRepository.deleteSchedule(it)
                }
            } else {
                repository.deleteTask(task)
            }
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            if (task.id.startsWith("sched_")) {
                val scheduleId = task.id.removePrefix("sched_")
                scheduleRepository.getScheduleById(scheduleId)?.let { current ->
                    scheduleRepository.updateSchedule(current.copy(isCompleted = !current.isCompleted))
                }
            } else {
                repository.updateTask(task.copy(isCompleted = !task.isCompleted))
            }
        }
    }

    fun toggleTimer() {
        if (_uiState.value.isRunning) {
            pauseTimer()
        } else {
            startTimer()
        }
    }

    fun stopTimer() {
        timerService?.stopTimer()
        _uiState.update { it.copy(
            isRunning = false,
            isTimerActive = false,
            totalRemainingSeconds = 0,
            currentBlockIndex = 0,
            remainingSeconds = 0,
            selectedTaskId = null,
            selectedTaskTitle = null,
            currentScheduleId = null,
            sessionTotalMinutes = 60
        ) }
        generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, 60)
    }

    fun startTimer() {
        val state = _uiState.value
        
        // 만약 선택된 태스크가 없고 현재 남은 시간이 0이라면 (완전 신규 독립 작업)
        if (state.selectedTaskId == null && state.totalRemainingSeconds <= 0) {
            // 전역 설정에서 현재 기본 인터벌과 휴식 시간을 가져와서 새로 세팅 (오염 방지)
            val defaultInterval = state.alarmIntervalMinutes
            val defaultRest = state.restMinutes
            val defaultTotalMinutes = state.defaultTotalMinutes
            val totalSeconds = defaultTotalMinutes * 60
            
            _uiState.update { it.copy(
                sessionTotalMinutes = defaultTotalMinutes,
                activeSessionInterval = defaultInterval,
                restMinutes = defaultRest,
                totalRemainingSeconds = totalSeconds,
                remainingSeconds = defaultInterval * 60
            ) }
            generateDefaultBlocks(defaultInterval, defaultTotalMinutes)
            
            timerService?.setTimerConfig(
                interval = defaultInterval,
                rest = defaultRest,
                totalSec = totalSeconds,
                title = "독립 작업",
                vibrate = state.vibrationEnabled,
                sound = state.soundEnabled,
                focusPatternId = state.focusVibrationPatternId,
                restPatternId = state.restVibrationPatternId,
                finishPatternId = state.finishVibrationPatternId,
                focusSound = state.focusSoundId,
                restSound = state.restSoundId,
                finishSound = state.finishSoundId,
                onTransition = { t, e, f, bt -> onBlockTransition(t, e, f, bt) },
                onFinished = { onSessionFinished() }
            )
            timerService?.startTimer(totalSeconds)
            return
        }

        // 기존에 선택된 작업이 있거나 일시정지 중인 세션 재개
        val currentInterval = state.activeSessionInterval
        val currentRest = state.restMinutes

        if (state.totalRemainingSeconds <= 0) {
            // 선택된 태스크가 있는 상태에서 처음 시작
            val totalSeconds = state.sessionTotalMinutes * 60
            // 가공된 Task.title 대신 순수 작업명인 selectedTaskTitle을 최우선 사용
            val title = state.selectedTaskTitle ?: state.tasks.find { it.id == state.selectedTaskId }?.title ?: "작업"
            
            _uiState.update { it.copy(
                totalRemainingSeconds = totalSeconds,
                remainingSeconds = currentInterval * 60
            ) }
            generateDefaultBlocks(currentInterval, state.sessionTotalMinutes)
            
            timerService?.setTimerConfig(
                interval = currentInterval,
                rest = currentRest,
                totalSec = totalSeconds,
                title = title,
                vibrate = state.vibrationEnabled,
                sound = state.soundEnabled,
                focusPatternId = state.focusVibrationPatternId,
                restPatternId = state.restVibrationPatternId,
                finishPatternId = state.finishVibrationPatternId,
                focusSound = state.focusSoundId,
                restSound = state.restSoundId,
                finishSound = state.finishSoundId,
                onTransition = { t, e, f, bt -> onBlockTransition(t, e, f, bt) },
                onFinished = { onSessionFinished() }
            )
            timerService?.startTimer(totalSeconds)
        } else {
            // 일시정지 후 재개
            val title = state.selectedTaskTitle ?: state.tasks.find { it.id == state.selectedTaskId }?.title ?: "작업"
            timerService?.setTimerConfig(
                interval = state.activeSessionInterval,
                rest = state.restMinutes,
                totalSec = state.sessionTotalMinutes * 60,
                title = title,
                vibrate = state.vibrationEnabled,
                sound = state.soundEnabled,
                focusPatternId = state.focusVibrationPatternId,
                restPatternId = state.restVibrationPatternId,
                finishPatternId = state.finishVibrationPatternId,
                focusSound = state.focusSoundId,
                restSound = state.restSoundId,
                finishSound = state.finishSoundId,
                onTransition = { t, e, f, bt -> onBlockTransition(t, e, f, bt) },
                onFinished = { onSessionFinished() }
            )
            timerService?.startTimer(state.totalRemainingSeconds)
        }
    }

    private fun pauseTimer() {
        timerService?.pauseTimer()
    }

    fun skipBlock() {
        if (!_uiState.value.isRunning) return
        timerService?.skipToNext()
    }

    fun onBlockTransition(taskTitle: String, elapsedMinutes: Int, isFinished: Boolean, blockType: BlockType) {
        val state = _uiState.value
        val focusPattern = VibrationPattern.fromId(state.focusVibrationPatternId).pattern
        val restPattern = VibrationPattern.fromId(state.restVibrationPatternId).pattern
        val finishPattern = VibrationPattern.fromId(state.finishVibrationPatternId).pattern

        notificationHelper.showBlockTransitionNotification(
            taskTitle = taskTitle, 
            elapsedMinutes = elapsedMinutes, 
            isFinished = isFinished,
            currentBlockType = blockType,
            focusVibrationPattern = focusPattern,
            restVibrationPattern = restPattern,
            finishVibrationPattern = finishPattern,
            focusSoundId = state.focusSoundId,
            restSoundId = state.restSoundId,
            finishSoundId = state.finishSoundId,
            vibrationEnabled = state.vibrationEnabled,
            soundEnabled = state.soundEnabled
        )
    }

    fun onSessionFinished() {
        timerService?.stopTimer()
        val currentScheduleId = _uiState.value.currentScheduleId
        val focusedMinutes = _uiState.value.sessionTotalMinutes
        
        viewModelScope.launch {
            // 통계 저장
            statsRepository.addFocusMinutes(focusedMinutes)
            statsRepository.incrementTaskCount()

            if (currentScheduleId != null) {
                scheduleRepository.getScheduleById(currentScheduleId)?.let {
                    scheduleRepository.updateSchedule(it.copy(isCompleted = true))
                }
            }
        }
        _uiState.update { it.copy(
            isRunning = false,
            isTimerActive = false,
            totalRemainingSeconds = 0,
            currentBlockIndex = 0,
            remainingSeconds = 0,
            selectedTaskId = null,
            selectedTaskTitle = null,
            currentScheduleId = null,
            sessionTotalMinutes = 60
        ) }
        generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, 60)
    }

    fun saveSettings(
        interval: Int,
        rest: Int,
        vibration: Boolean,
        sound: Boolean,
        calendarSync: Boolean,
        focusPatternId: String,
        restPatternId: String,
        finishPatternId: String,
        focusSoundId: String,
        restSoundId: String,
        finishSoundId: String,
        defaultTotalMinutes: Int
    ) {
        viewModelScope.launch {
            settingsRepository.setAlarmIntervalMinutes(interval)
            settingsRepository.setRestMinutes(rest)
            settingsRepository.setVibrationEnabled(vibration)
            settingsRepository.setSoundEnabled(sound)
            settingsRepository.setCalendarSyncEnabled(calendarSync)
            settingsRepository.setFocusVibrationPatternId(focusPatternId)
            settingsRepository.setRestVibrationPatternId(restPatternId)
            settingsRepository.setFinishVibrationPatternId(finishPatternId)
            settingsRepository.setFocusSoundId(focusSoundId)
            settingsRepository.setRestSoundId(restSoundId)
            settingsRepository.setFinishSoundId(finishSoundId)
            settingsRepository.setDefaultTotalMinutes(defaultTotalMinutes)

            _uiState.update { it.copy(
                alarmIntervalMinutes = interval,
                restMinutes = rest,
                vibrationEnabled = vibration,
                soundEnabled = sound,
                calendarSyncEnabled = calendarSync,
                focusVibrationPatternId = focusPatternId,
                restVibrationPatternId = restPatternId,
                finishVibrationPatternId = finishPatternId,
                focusSoundId = focusSoundId,
                restSoundId = restSoundId,
                finishSoundId = finishSoundId,
                defaultTotalMinutes = defaultTotalMinutes
            ) }
        }
    }

    fun previewVibration(patternId: String) {
        val pattern = VibrationPattern.fromId(patternId).pattern
        notificationHelper.vibratePreview(pattern)
    }

    fun previewSound(soundId: String) {
        notificationHelper.playSound(soundId)
    }

    fun stopSoundPreview() {
        notificationHelper.stopSound()
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }

    fun requestIgnoreBatteryOptimizations() {
        if (!isIgnoringBatteryOptimizations()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${app.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            app.startActivity(intent)
        }
    }

    fun deleteSchedule(schedule: ScheduleBlock) {
        viewModelScope.launch {
            scheduleRepository.deleteSchedule(schedule)
        }
    }

    fun updateSchedule(schedule: ScheduleBlock, newTitle: String) {
        viewModelScope.launch {
            val updated = schedule.copy(taskTitle = newTitle)
            scheduleRepository.updateSchedule(updated)
        }
    }

    fun toggleBlock(startTimeMillis: Long) {
        _uiState.update { state ->
            val anchor = state.selectionAnchor
            if (anchor == null) {
                state.copy(selectedBlocks = setOf(startTimeMillis), selectionAnchor = startTimeMillis)
            } else if (anchor == startTimeMillis) {
                // Re-tapping the anchor clears the selection
                state.copy(selectedBlocks = emptySet(), selectionAnchor = null)
            } else {
                val start = Math.min(anchor, startTimeMillis)
                val end = Math.max(anchor, startTimeMillis)
                val newRange = mutableSetOf<Long>()
                var current = start
                while (current <= end) {
                    newRange.add(current)
                    current += 15 * 60 * 1000L
                }
                state.copy(selectedBlocks = newRange)
            }
        }
    }

    fun clearSelectedBlocks() {
        _uiState.update { it.copy(selectedBlocks = emptySet(), selectionAnchor = null) }
    }

    fun setCalendarMonthlyView(isMonthly: Boolean) {
        _uiState.update { it.copy(isCalendarMonthlyView = isMonthly) }
    }

    fun clearSelectionIfNotActive() {
        if (!_uiState.value.isTimerActive) {
            _uiState.update { it.copy(
                selectedTaskId = null,
                selectedTaskTitle = null,
                currentScheduleId = null,
                sessionTotalMinutes = 60,
                totalRemainingSeconds = 0
            ) }
            generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, 60)
        }
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
