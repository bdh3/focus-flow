package com.example.adhdblockscheduler.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adhdblockscheduler.data.prefs.SettingsRepository
import com.example.adhdblockscheduler.data.repository.ScheduleRepository
import com.example.adhdblockscheduler.data.repository.StatsRepository
import com.example.adhdblockscheduler.data.repository.TaskRepository
import com.example.adhdblockscheduler.model.BlockType
import com.example.adhdblockscheduler.model.ScheduleBlock
import com.example.adhdblockscheduler.model.Task
import com.example.adhdblockscheduler.model.TimeBlock
import com.example.adhdblockscheduler.service.TimerService
import com.example.adhdblockscheduler.util.CalendarHelper
import com.example.adhdblockscheduler.util.NotificationHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

data class SchedulerUiState(
    val tasks: List<Task> = emptyList(),
    val selectedTaskId: String? = null,
    val timeBlocks: List<TimeBlock> = emptyList(),
    val currentBlockIndex: Int = 0,
    val remainingSeconds: Int = 0,
    val isRunning: Boolean = false,
    val sessionTotalMinutes: Int = 0,
    val totalRemainingSeconds: Int = 0,
    val vibrationEnabled: Boolean = true,
    val alarmIntervalMinutes: Int = 15,
    val calendarSyncEnabled: Boolean = true,
    val selectedBlocks: Set<Long> = emptySet(),
    val dailySchedules: List<ScheduleBlock> = emptyList(),
    val currentScheduleId: String? = null
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
    val selectedDate = _selectedDate.asStateFlow()

    private val notificationHelper = NotificationHelper(app)

    private var timerService: TimerService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TimerService.TimerBinder
            timerService = binder.getService()
            isBound = true

            viewModelScope.launch {
                timerService?.let { service ->
                    launch {
                        service.isRunning.collect { running ->
                            _uiState.update { it.copy(isRunning = running) }
                        }
                    }
                    launch {
                        service.totalRemainingSeconds.collect { total ->
                            _uiState.update { it.copy(totalRemainingSeconds = total) }
                        }
                    }
                    launch {
                        service.remainingSeconds.collect { rem ->
                            _uiState.update { it.copy(remainingSeconds = rem) }
                        }
                    }
                    launch {
                        service.currentBlockIndex.collect { idx ->
                            _uiState.update { it.copy(currentBlockIndex = idx) }
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            timerService = null
        }
    }

    init {
        Intent(app, TimerService::class.java).also { intent ->
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
        _selectedDate.flatMapLatest { repository.getTasksForDate(it) },
        settingsRepository.calendarSyncEnabled,
        settingsRepository.vibrationEnabled,
        settingsRepository.alarmIntervalMinutes,
        _selectedDate.flatMapLatest { scheduleRepository.getSchedulesForDay(it) }
    ) { params ->
        val state = params[0] as SchedulerUiState
        val tasks = params[1] as List<Task>
        val calendarSync = params[2] as Boolean
        val vibration = params[3] as Boolean
        val alarmInterval = params[4] as Int
        val dailySchedules = params[5] as List<ScheduleBlock>

        state.copy(
            tasks = tasks,
            calendarSyncEnabled = calendarSync,
            vibrationEnabled = vibration,
            alarmIntervalMinutes = alarmInterval,
            dailySchedules = dailySchedules
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SchedulerUiState()
    )

    fun generateDefaultBlocks(alarmInterval: Int, totalMinutes: Int) {
        val blocks = mutableListOf<TimeBlock>()
        var currentTime = System.currentTimeMillis()
        
        val interval = if (alarmInterval <= 0) 15 else alarmInterval
        val fullBlocks = totalMinutes / interval
        val remainder = totalMinutes % interval

        for (i in 0 until fullBlocks) {
            blocks.add(TimeBlock(startTime = currentTime, durationMinutes = interval, type = BlockType.FOCUS))
            currentTime += interval * 60 * 1000L
        }
        
        if (remainder > 0) {
            blocks.add(TimeBlock(startTime = currentTime, durationMinutes = remainder, type = BlockType.FOCUS))
        }

        _uiState.update { it.copy(
            timeBlocks = blocks,
            currentBlockIndex = 0,
            remainingSeconds = if (blocks.isNotEmpty()) blocks[0].durationMinutes * 60 else 0,
            totalRemainingSeconds = blocks.sumOf { it.durationMinutes * 60 },
            sessionTotalMinutes = totalMinutes
        ) }
    }

    fun addSchedule(taskTitle: String, durationMinutes: Int, hourOfDay: Int, startMinutes: Int = 0) {
        viewModelScope.launch {
            val startTime = Calendar.getInstance().apply {
                timeInMillis = _selectedDate.value
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, startMinutes)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val newSchedule = ScheduleBlock(
                taskTitle = taskTitle,
                startTimeMillis = startTime,
                durationMinutes = durationMinutes
            )
            scheduleRepository.insertSchedule(newSchedule)
        }
    }

    fun loadScheduledSession(schedule: ScheduleBlock) {
        if (_uiState.value.isRunning) return
        
        viewModelScope.launch {
            val tasksForDate = repository.getTasksForDate(_selectedDate.value).first()
            val existingTask = tasksForDate.find { it.title == schedule.taskTitle }
            val taskId = existingTask?.id ?: run {
                val newTask = Task(
                    title = schedule.taskTitle,
                    scheduledDateMillis = _selectedDate.value
                )
                repository.insertTask(newTask)
                newTask.id
            }
            
            _uiState.update { it.copy(
                selectedTaskId = taskId,
                sessionTotalMinutes = schedule.durationMinutes,
                currentScheduleId = schedule.id
            ) }
            
            generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, schedule.durationMinutes)
        }
    }

    fun startNewSession(taskTitle: String, totalMinutes: Int, hourOfDay: Int? = null) {
        if (_uiState.value.isRunning) return
        
        viewModelScope.launch {
            val startTime = Calendar.getInstance().apply {
                timeInMillis = _selectedDate.value
                if (hourOfDay != null) set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val newSchedule = ScheduleBlock(
                taskTitle = taskTitle,
                startTimeMillis = startTime,
                durationMinutes = totalMinutes
            )
            scheduleRepository.insertSchedule(newSchedule)
            
            val newTask = Task(
                title = taskTitle,
                scheduledDateMillis = _selectedDate.value
            )
            repository.insertTask(newTask)
            
            _uiState.update { it.copy(
                selectedTaskId = newTask.id, // 여기서 즉시 선택
                currentScheduleId = newSchedule.id,
                sessionTotalMinutes = totalMinutes,
                totalRemainingSeconds = 0 // 시작 버튼을 보여주기 위해 0으로 초기화
            ) }
            // generateDefaultBlocks는 toggleTimer에서 startTimer 직전에 호출됨
        }
    }

    fun selectDate(dateMillis: Long) {
        _selectedDate.value = dateMillis
    }

    fun selectTask(taskId: String?) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(selectedTaskId = taskId) }
    }

    fun addTask(title: String) {
        viewModelScope.launch {
            repository.insertTask(Task(title = title))
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            repository.updateTaskCompletion(task.id, !task.isCompleted)
        }
    }

    fun toggleTimer() {
        val state = _uiState.value
        if (state.isRunning) {
            pauseTimer()
        } else {
            // totalRemainingSeconds가 0이거나 초기 상태일 때만 새로 생성
            if (state.totalRemainingSeconds <= 0) {
                // 새로운 세션 시작 시 초기화
                val totalMin = if (state.sessionTotalMinutes > 0) state.sessionTotalMinutes else 60
                generateDefaultBlocks(state.alarmIntervalMinutes, totalMin)
            }
            
            if (state.selectedTaskId == null && state.tasks.isNotEmpty()) {
                selectTask(state.tasks[0].id)
            }
            startTimer()
        }
    }

    fun stopTimer() {
        timerService?.stopTimer()
        _uiState.update { it.copy(
            isRunning = false,
            currentBlockIndex = 0,
            remainingSeconds = 0,
            totalRemainingSeconds = 0,
            timeBlocks = emptyList(),
            selectedTaskId = null,
            currentScheduleId = null,
            sessionTotalMinutes = 60 // 중지 시 기본 60분으로 리셋
        ) }
    }

    private fun startTimer() {
        val state = _uiState.value
        if (timerService == null) {
            Intent(app, TimerService::class.java).also { intent ->
                app.startForegroundService(intent)
            }
        }

        val taskTitle = state.tasks.find { it.id == state.selectedTaskId }?.title ?: "작업"
        val totalSecAtStart = state.timeBlocks.sumOf { it.durationMinutes * 60 }
        
        timerService?.setTimerConfig(
            interval = state.alarmIntervalMinutes,
            totalSec = totalSecAtStart,
            title = taskTitle,
            vibrate = state.vibrationEnabled,
            onTransition = { title, minutes, isLast -> onBlockTransition(title, minutes, isLast) },
            onFinished = { onSessionFinished() }
        )
        
        // UI 상태의 남은 시간이 있으면 그것부터 시작, 없으면 전체 시간으로 시작
        val initialTotalRemaining = if (state.totalRemainingSeconds > 0) state.totalRemainingSeconds else totalSecAtStart
        timerService?.startTimer(initialTotalRemaining)
    }

    fun pauseTimer() {
        timerService?.pauseTimer()
    }

    fun skipBlock() {
        val state = _uiState.value
        if (state.currentBlockIndex < state.timeBlocks.size - 1) {
            val nextIndex = state.currentBlockIndex + 1
            // 다음 블록의 시작점까지 남은 전체 시간을 계산
            val remainingAfterSkip = state.timeBlocks
                .drop(nextIndex)
                .sumOf { it.durationMinutes * 60 }
            
            // 현재 블록 인덱스를 업데이트하고 남은 시간을 타이머에 전달
            _uiState.update { it.copy(
                currentBlockIndex = nextIndex,
                remainingSeconds = state.timeBlocks[nextIndex].durationMinutes * 60,
                totalRemainingSeconds = remainingAfterSkip
            ) }
            timerService?.startTimer(remainingAfterSkip)
        } else {
            // 마지막 블록에서 넘기면 세션 종료 (완료 처리)
            onSessionFinished()
            timerService?.stopTimer()
        }
    }

    private fun onBlockTransition(taskTitle: String, elapsedMinutes: Int, isFinished: Boolean) {
        notificationHelper.showBlockTransitionNotification(
            taskTitle = taskTitle,
            elapsedMinutes = elapsedMinutes,
            isFinished = isFinished,
            vibrationEnabled = _uiState.value.vibrationEnabled
        )
        
        if (!isFinished) {
            viewModelScope.launch {
                statsRepository.addFocusMinutes(_uiState.value.alarmIntervalMinutes)
            }
        }
    }

    private fun onSessionFinished() {
        val currentState = _uiState.value
        val currentScheduleId = currentState.currentScheduleId
        
        _uiState.update { it.copy(
            timeBlocks = it.timeBlocks.map { b -> b.copy(isCompleted = true) },
            isRunning = false,
            totalRemainingSeconds = 0
        ) }
        
        if (currentScheduleId != null) {
            viewModelScope.launch {
                val schedule = scheduleRepository.getScheduleById(currentScheduleId)
                if (schedule != null) {
                    scheduleRepository.updateSchedule(schedule.copy(isCompleted = true))
                    
                    if (currentState.calendarSyncEnabled) {
                        CalendarHelper.addEventToCalendar(
                            context = app,
                            title = schedule.taskTitle,
                            description = "Focus Flow 집중 세션 완료",
                            startTime = schedule.startTimeMillis,
                            durationMinutes = schedule.durationMinutes
                        )
                    }
                }
            }
        }
    }

    fun updateVibration(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVibrationEnabled(enabled)
            _uiState.update { it.copy(vibrationEnabled = enabled) }
        }
        if (enabled) {
            notificationHelper.vibrateDeviceShort()
        }
    }

    fun updateCalendarSync(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCalendarSyncEnabled(enabled)
            _uiState.update { it.copy(calendarSyncEnabled = enabled) }
        }
    }

    fun saveSettings(interval: Int, vibration: Boolean, calendarSync: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAlarmIntervalMinutes(interval)
            settingsRepository.setVibrationEnabled(vibration)
            settingsRepository.setCalendarSyncEnabled(calendarSync)
            _uiState.update { it.copy(
                alarmIntervalMinutes = interval,
                vibrationEnabled = vibration,
                calendarSyncEnabled = calendarSync
            ) }
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
            val newSelected = state.selectedBlocks.toMutableSet()
            if (newSelected.contains(startTimeMillis)) {
                val remaining = newSelected.filter { it < startTimeMillis }.toSet()
                state.copy(selectedBlocks = remaining)
            } else {
                newSelected.add(startTimeMillis)
                val min = newSelected.min()
                val max = newSelected.max()
                var current = min
                while (current <= max) {
                    newSelected.add(current)
                    current += 15 * 60 * 1000L
                }
                state.copy(selectedBlocks = newSelected)
            }
        }
    }

    fun clearSelectedBlocks() {
        _uiState.update { it.copy(selectedBlocks = emptySet()) }
    }

    companion object {
        fun Factory(
            app: Application,
            repository: TaskRepository,
            settingsRepository: SettingsRepository,
            statsRepository: StatsRepository,
            scheduleRepository: ScheduleRepository
        ): androidx.lifecycle.ViewModelProvider.Factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return SchedulerViewModel(app, repository, settingsRepository, statsRepository, scheduleRepository) as T
            }
        }
    }
}
