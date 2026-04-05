package com.example.adhdblockscheduler.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adhdblockscheduler.model.ScheduleBlock
import com.example.adhdblockscheduler.model.Task
import com.example.adhdblockscheduler.model.TimeBlock
import com.example.adhdblockscheduler.model.BlockType
import com.example.adhdblockscheduler.data.repository.TaskRepository
import com.example.adhdblockscheduler.data.prefs.SettingsRepository
import com.example.adhdblockscheduler.data.repository.StatsRepository
import com.example.adhdblockscheduler.data.repository.ScheduleRepository
import com.example.adhdblockscheduler.service.TimerService
import com.example.adhdblockscheduler.util.NotificationHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

data class SchedulerUiState(
    val tasks: List<Task> = emptyList(),
    val selectedTaskId: String? = null,
    val timeBlocks: List<TimeBlock> = emptyList(),
    val currentBlockIndex: Int = 0,
    val remainingSeconds: Int = 0,
    val isRunning: Boolean = false,
    val sessionTotalMinutes: Int = 60,
    val totalRemainingSeconds: Int = 0,
    val vibrationEnabled: Boolean = true,
    val alarmIntervalMinutes: Int = 15,
    val calendarSyncEnabled: Boolean = false,
    val selectedBlocks: Set<Long> = emptySet(),
    val dailySchedules: List<ScheduleBlock> = emptyList(),
    val allSchedules: List<ScheduleBlock> = emptyList(),
    val currentScheduleId: String? = null,
    val selectionAnchor: Long? = null
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
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.TimerBinder
            timerService = binder.getService()
            isBound = true

            viewModelScope.launch {
                timerService?.remainingSeconds?.collect { sec ->
                    _uiState.update { it.copy(remainingSeconds = sec) }
                }
            }
            viewModelScope.launch {
                timerService?.totalRemainingSeconds?.collect { sec ->
                    _uiState.update { it.copy(totalRemainingSeconds = sec) }
                }
            }
            viewModelScope.launch {
                timerService?.isRunning?.collect { running ->
                    _uiState.update { it.copy(isRunning = running) }
                }
            }
            viewModelScope.launch {
                timerService?.currentBlockIndex?.collect { index ->
                    _uiState.update { it.copy(currentBlockIndex = index) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            timerService = null
            isBound = false
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
        _selectedDate.flatMapLatest { 
            val today = Calendar.getInstance().apply {
                timeInMillis = it
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            combine(
                repository.getTasksForDate(today),
                scheduleRepository.getSchedulesForDay(today)
            ) { tasks, schedules ->
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
                        scheduledDateMillis = today,
                        isCompleted = schedule.isCompleted,
                        startTimeMillis = schedule.startTimeMillis
                    )
                    
                    if (existingIndex != -1) {
                        combined[existingIndex] = task
                    } else {
                        combined.add(task)
                    }
                }
                combined.sortedWith(compareBy({ it.startTimeMillis == 0L }, { it.startTimeMillis }, { it.createdAt }))
            }
        },
        settingsRepository.vibrationEnabled,
        settingsRepository.alarmIntervalMinutes,
        _selectedDate.flatMapLatest { scheduleRepository.getSchedulesForDay(it) },
        scheduleRepository.getAllSchedules()
    ) { params ->
        val state = params[0] as? SchedulerUiState ?: SchedulerUiState()
        @Suppress("UNCHECKED_CAST")
        val tasks = params[1] as? List<Task> ?: emptyList()
        val vibration = params[2] as? Boolean ?: true
        val alarmInterval = params[3] as? Int ?: 15
        @Suppress("UNCHECKED_CAST")
        val dailySchedules = params[4] as? List<ScheduleBlock> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val allSchedules = params[5] as? List<ScheduleBlock> ?: emptyList()

        // 세션이 활성화(실행 중 또는 일시정지 중 남은 시간이 있음)된 상태인지 체크
        val isSessionActive = state.isRunning || (state.totalRemainingSeconds > 0)
        
        // 세션 활성 시, 현재 선택된 작업만 리스트에 보이도록 필터링 (다른 작업으로의 이탈 방지)
        val filteredTasks = if (isSessionActive && state.selectedTaskId != null) {
            tasks.filter { it.id == state.selectedTaskId }
        } else {
            tasks
        }

        state.copy(
            tasks = filteredTasks,
            vibrationEnabled = vibration,
            alarmIntervalMinutes = alarmInterval,
            dailySchedules = dailySchedules,
            allSchedules = allSchedules
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SchedulerUiState()
    )

    fun generateDefaultBlocks(interval: Int, totalMinutes: Int) {
        val blocksCount = totalMinutes / interval
        val blocks = List(blocksCount) { index ->
            TimeBlock(
                id = index.toString(),
                startTime = System.currentTimeMillis() + (index * interval * 60 * 1000L),
                durationMinutes = interval,
                type = BlockType.FOCUS,
                isCompleted = false
            )
        }
        _uiState.update { it.copy(
            timeBlocks = blocks,
            currentBlockIndex = 0,
            remainingSeconds = interval * 60,
            totalRemainingSeconds = totalMinutes * 60
        ) }
    }

    fun addSchedule(taskTitle: String, durationMinutes: Int, hourOfDay: Int, startMinutes: Int) {
        viewModelScope.launch {
            val startTime = Calendar.getInstance().apply {
                timeInMillis = _selectedDate.value
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, startMinutes)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val schedule = ScheduleBlock(
                taskTitle = taskTitle,
                startTimeMillis = startTime,
                durationMinutes = durationMinutes
            )
            scheduleRepository.insertSchedule(schedule)
            loadScheduledSession(schedule)
        }
    }

    fun loadScheduledSession(schedule: ScheduleBlock) {
        if (_uiState.value.isRunning) return
        
        _uiState.update { it.copy(
            selectedTaskId = "sched_${schedule.id}",
            sessionTotalMinutes = schedule.durationMinutes,
            currentScheduleId = schedule.id,
            remainingSeconds = it.alarmIntervalMinutes * 60,
            totalRemainingSeconds = schedule.durationMinutes * 60
        ) }
        
        generateDefaultBlocks(_uiState.value.alarmIntervalMinutes, schedule.durationMinutes)
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
            scheduleRepository.insertSchedule(newSchedule)
            
            _uiState.update { it.copy(
                selectedTaskId = "sched_${newSchedule.id}",
                currentScheduleId = newSchedule.id,
                sessionTotalMinutes = totalMinutes,
                totalRemainingSeconds = 0 
            ) }
            
            loadScheduledSession(newSchedule)
        }
    }

    fun selectDate(dateMillis: Long) {
        _selectedDate.value = dateMillis
    }

    fun selectTask(taskId: String?) {
        _uiState.update { state ->
            // 세션 진행 중(실행 중이거나 일시정지 상태에서 남은 시간이 있는 경우) 선택 변경 차단
            val isSessionActive = state.isRunning || (state.totalRemainingSeconds > 0)
            
            if (isSessionActive && state.selectedTaskId != null) {
                // 이미 선택된 작업이 있고 세션이 활성 상태라면 변경 불가
                // UI에서 이 상태를 인지할 수 있도록 그대로 유지
                return@update state
            }

            if (state.selectedTaskId == taskId) {
                state.copy(selectedTaskId = null, currentScheduleId = null)
            } else {
                val scheduleId = if (taskId?.startsWith("sched_") == true) taskId.removePrefix("sched_") else null
                state.copy(selectedTaskId = taskId, currentScheduleId = scheduleId)
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
            repository.deleteTask(task)
            if (task.id.startsWith("sched_")) {
                val scheduleId = task.id.removePrefix("sched_")
                val schedule = scheduleRepository.getScheduleById(scheduleId)
                if (schedule != null) scheduleRepository.deleteSchedule(schedule)
            }
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            if (task.id.startsWith("sched_")) {
                val scheduleId = task.id.removePrefix("sched_")
                val schedule = scheduleRepository.getScheduleById(scheduleId)
                if (schedule != null) {
                    scheduleRepository.updateSchedule(schedule.copy(isCompleted = !schedule.isCompleted))
                }
            } else {
                repository.updateTaskCompletion(task.id, !task.isCompleted)
            }
        }
    }

    fun toggleTimer() {
        val state = _uiState.value
        if (state.isRunning) {
            pauseTimer()
        } else {
            if (state.totalRemainingSeconds == 0) {
                generateDefaultBlocks(state.alarmIntervalMinutes, state.sessionTotalMinutes)
            }
            startTimer()
        }
    }

    fun stopTimer() {
        timerService?.stopTimer()
        _uiState.update { it.copy(
            isRunning = false,
            currentBlockIndex = 0,
            totalRemainingSeconds = 0,
            sessionTotalMinutes = 60 
        ) }
    }

    private fun startTimer() {
        val state = uiState.value 
        val task = state.tasks.find { it.id == state.selectedTaskId }
        val finalTitle = task?.title ?: "작업"
        
        timerService?.setTimerConfig(
            interval = state.alarmIntervalMinutes,
            totalSec = state.sessionTotalMinutes * 60,
            title = finalTitle,
            vibrate = state.vibrationEnabled,
            onTransition = { title, elapsed, finished ->
                onBlockTransition(title, elapsed, finished)
            },
            onFinished = {
                onSessionFinished()
            }
        )
        timerService?.startTimer(state.totalRemainingSeconds)
    }

    private fun pauseTimer() {
        timerService?.pauseTimer()
    }

    fun skipBlock() {
        val state = uiState.value
        if (!state.isRunning) return
        
        val intervalSeconds = state.alarmIntervalMinutes * 60
        val nextBlockIndex = state.currentBlockIndex + 1
        val totalBlocks = state.timeBlocks.size
        
        if (nextBlockIndex < totalBlocks) {
            val newTotalRemainingSeconds = (totalBlocks - nextBlockIndex) * intervalSeconds
            _uiState.update { it.copy(
                currentBlockIndex = nextBlockIndex,
                remainingSeconds = intervalSeconds,
                totalRemainingSeconds = newTotalRemainingSeconds
            ) }
            // 서비스에 즉시 반영
            timerService?.startTimer(newTotalRemainingSeconds)
        } else {
            val task = state.tasks.find { it.id == state.selectedTaskId }
            onBlockTransition(task?.title ?: "작업", state.sessionTotalMinutes, true)
            onSessionFinished()
            timerService?.stopTimer()
        }
    }

    private fun onBlockTransition(taskTitle: String, elapsedMinutes: Int, isFinished: Boolean) {
        val currentState = uiState.value
        notificationHelper.showBlockTransitionNotification(
            taskTitle = taskTitle,
            elapsedMinutes = elapsedMinutes,
            isFinished = isFinished,
            vibrationEnabled = currentState.vibrationEnabled
        )
        
        if (!isFinished) {
            viewModelScope.launch {
                statsRepository.addFocusMinutes(currentState.alarmIntervalMinutes)
            }
        }
    }

    private fun onSessionFinished() {
        val currentState = _uiState.value
        val currentTaskId = currentState.selectedTaskId
        
        _uiState.update { it.copy(
            timeBlocks = it.timeBlocks.map { b -> b.copy(isCompleted = true) },
            isRunning = false,
            totalRemainingSeconds = 0
        ) }
        
        viewModelScope.launch {
            if (currentTaskId != null) {
                if (currentTaskId.startsWith("sched_")) {
                    val scheduleId = currentTaskId.removePrefix("sched_")
                    val schedule = scheduleRepository.getScheduleById(scheduleId)
                    if (schedule != null) {
                        scheduleRepository.updateSchedule(schedule.copy(isCompleted = true))
                    }
                } else {
                    repository.updateTaskCompletion(currentTaskId, true)
                }
            }
        }
    }

    fun saveSettings(interval: Int, vibration: Boolean, calendarSync: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAlarmIntervalMinutes(interval)
            settingsRepository.setVibrationEnabled(vibration)
            _uiState.update { it.copy(
                alarmIntervalMinutes = interval,
                vibrationEnabled = vibration
            ) }
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }

    fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${app.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(settingsIntent)
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
            } else if (startTimeMillis == anchor) {
                state.copy(selectedBlocks = emptySet(), selectionAnchor = null)
            } else {
                val start = minOf(anchor, startTimeMillis)
                val end = maxOf(anchor, startTimeMillis)
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
