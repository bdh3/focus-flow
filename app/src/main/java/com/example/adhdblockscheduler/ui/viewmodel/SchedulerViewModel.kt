package com.example.adhdblockscheduler.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.adhdblockscheduler.ADHDBlockSchedulerApplication
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class SchedulerViewModel(
    private val app: Application,
    private val repository: TaskRepository,
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository
) : AndroidViewModel(app) {
    
    private val notificationHelper = NotificationHelper(app)
    private val _uiState = MutableStateFlow(SchedulerUiState())
    
    private val _selectedDate = MutableStateFlow(System.currentTimeMillis())
    val selectedDate: StateFlow<Long> = _selectedDate.asStateFlow()

    private var timerService: TimerService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TimerService.TimerBinder
            timerService = binder.getService()
            isBound = true
            
            // Sync state from service
            viewModelScope.launch {
                timerService?.let { service ->
                    launch {
                        service.isRunning.collect { running ->
                            _uiState.update { it.copy(isRunning = running) }
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
        repository.allTasks,
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

        // 전체 남은 시간 계산
        val currentBlockRemaining = state.remainingSeconds
        val futureBlocksRemaining = state.timeBlocks
            .drop(state.currentBlockIndex + 1)
            .sumOf { it.durationMinutes * 60 }
        val totalRemaining = currentBlockRemaining + futureBlocksRemaining

        state.copy(
            tasks = tasks,
            calendarSyncEnabled = calendarSync,
            vibrationEnabled = vibration,
            alarmIntervalMinutes = alarmInterval,
            totalRemainingSeconds = totalRemaining,
            dailySchedules = dailySchedules
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SchedulerUiState()
    )

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.alarmIntervalMinutes.collect { interval ->
                if (!_uiState.value.isRunning) {
                    generateDefaultBlocks(interval, 60)
                }
            }
        }
    }

    private fun generateDefaultBlocks(alarmInterval: Int, totalMinutes: Int) {
        val blocks = mutableListOf<TimeBlock>()
        var currentTime = System.currentTimeMillis()

        val fullBlocks = totalMinutes / alarmInterval
        val remainder = totalMinutes % alarmInterval

        for (i in 0 until fullBlocks) {
            blocks.add(TimeBlock(startTime = currentTime, durationMinutes = alarmInterval, type = BlockType.FOCUS))
            currentTime += alarmInterval * 60 * 1000L
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
        if (_uiState.value.isRunning) return // 이미 실행 중이면 다른 스케줄 로드 불가
        
        viewModelScope.launch {
            // 기존 할 일이 있는지 확인하거나 새로 생성
            val existingTasks = repository.allTasks.first()
            val existingTask = existingTasks.find { it.title == schedule.taskTitle }
            val taskId = existingTask?.id ?: run {
                val newTask = Task(title = schedule.taskTitle)
                repository.insertTask(newTask)
                newTask.id
            }
            
            selectTask(taskId)
            
            // 알림 주기에 따라 블록 생성
            val currentAlarmInterval = _uiState.value.alarmIntervalMinutes
            generateDefaultBlocks(currentAlarmInterval, schedule.durationMinutes)
            
            // 스케줄 ID 저장 (완료 시 업데이트를 위해)
            _uiState.update { it.copy(currentScheduleId = schedule.id) }
        }
    }

    fun startNewSession(taskTitle: String, totalMinutes: Int, hourOfDay: Int? = null) {
        if (_uiState.value.isRunning) return // 이미 실행 중이면 새 세션 시작 불가
        
        viewModelScope.launch {
            // 1. 스케줄 블록 생성 및 DB 저장
            val startTime = if (hourOfDay != null) {
                Calendar.getInstance().apply {
                    timeInMillis = _selectedDate.value
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else {
                System.currentTimeMillis()
            }

            val newSchedule = ScheduleBlock(
                taskTitle = taskTitle,
                startTimeMillis = startTime,
                durationMinutes = totalMinutes
            )
            scheduleRepository.insertSchedule(newSchedule)

            // 2. 할 일 추가 및 선택
            val newTask = Task(title = taskTitle)
            repository.insertTask(newTask)
            selectTask(newTask.id)
            
            // 3. 알림 주기에 따라 블록 생성
            val currentAlarmInterval = _uiState.value.alarmIntervalMinutes
            generateDefaultBlocks(currentAlarmInterval, totalMinutes)
            
            // 4. 스케줄 ID 저장 및 타이머 시작
            _uiState.update { it.copy(currentScheduleId = newSchedule.id) }
            startTimer()
        }
    }

    fun selectDate(dateMillis: Long) {
        _selectedDate.value = dateMillis
    }

    fun selectTask(taskId: String) {
        if (_uiState.value.isRunning) return // 실행 중에는 작업 변경 불가
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
        if (_uiState.value.isRunning) {
            pauseTimer()
        } else {
            if (_uiState.value.selectedTaskId == null && _uiState.value.tasks.isNotEmpty()) {
                // 할 일을 선택하지 않고 시작하면 첫 번째 할 일 자동 선택
                selectTask(_uiState.value.tasks[0].id)
            }
            startTimer()
        }
    }

    fun stopTimer() {
        timerService?.stopTimer()
        _uiState.update { it.copy(
            isRunning = false,
            currentBlockIndex = 0,
            remainingSeconds = if (it.timeBlocks.isNotEmpty()) it.timeBlocks[0].durationMinutes * 60 else 0,
            totalRemainingSeconds = it.timeBlocks.sumOf { b -> b.durationMinutes * 60 }
        ) }
    }

    private fun startTimer() {
        val state = _uiState.value
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
        
        val initialTotalRemaining = if (state.totalRemainingSeconds <= 0) totalSecAtStart else state.totalRemainingSeconds
        
        val intent = Intent(app, TimerService::class.java)
        app.startForegroundService(intent)
        timerService?.startTimer(initialTotalRemaining)
    }

    private fun onBlockTransition(taskTitle: String, elapsedMinutes: Int, isLast: Boolean) {
        val message = if (isLast) "$taskTitle 작업 종료" else "$taskTitle 작업의 ${elapsedMinutes}분 경과"
        
        notificationHelper.showSimpleNotification(
            title = "Focus Flow",
            message = message,
            vibrationEnabled = _uiState.value.vibrationEnabled
        )
        
        viewModelScope.launch {
            statsRepository.addFocusMinutes(_uiState.value.alarmIntervalMinutes)
        }
    }

    private fun onSessionFinished() {
        // 모든 블록이 끝났을 때의 처리
        val currentState = _uiState.value
        val currentScheduleId = currentState.currentScheduleId
        _uiState.update { it.copy(
            timeBlocks = it.timeBlocks.map { b -> b.copy(isCompleted = true) },
            isRunning = false
        ) }
        
        if (currentScheduleId != null) {
            viewModelScope.launch {
                val schedule = scheduleRepository.getScheduleById(currentScheduleId)
                if (schedule != null) {
                    val updatedSchedule = schedule.copy(isCompleted = true)
                    scheduleRepository.updateSchedule(updatedSchedule)

                    // 캘린더 연동
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

    private fun pauseTimer() {
        timerService?.pauseTimer()
        _uiState.update { it.copy(isRunning = false) }
    }

    fun skipBlock() {
        val state = _uiState.value
        if (state.currentBlockIndex < state.timeBlocks.size - 1) {
            val nextIndex = state.currentBlockIndex + 1
            
            // Simplified skip logic for now, should ideally be handled by service
            timerService?.stopTimer() // Reset and restart with new remaining
            
            val newCurrentBlockIndex = nextIndex
            val newRemainingSeconds = state.timeBlocks[nextIndex].durationMinutes * 60
            val newTotalRemaining = state.timeBlocks.drop(nextIndex).sumOf { it.durationMinutes * 60 }

            _uiState.update { it.copy(
                currentBlockIndex = newCurrentBlockIndex,
                remainingSeconds = newRemainingSeconds,
                totalRemainingSeconds = newTotalRemaining
            ) }
            
            if (state.isRunning) {
                startTimer()
            }
        } else {
            stopTimer()
        }
    }

    private fun onBlockFinished(
        showNotification: Boolean = true,
        finishedType: BlockType? = null,
        nextType: BlockType? = null
    ) {
        val currentState = _uiState.value
        val currentIndex = currentState.currentBlockIndex
        val finishedBlock = currentState.timeBlocks[currentIndex]
        val blockType = finishedType ?: finishedBlock.type
        
        // 블록 전환 알림 (자동 진행 중에도 알림 발생)
        if (showNotification) {
            notificationHelper.showBlockTransitionNotification(
                finishedType = blockType,
                nextType = nextType,
                vibrationEnabled = currentState.vibrationEnabled
            )
        }

        // 통계 저장 및 캘린더 연동
        viewModelScope.launch {
            if (blockType == BlockType.FOCUS) {
                statsRepository.addFocusMinutes(finishedBlock.durationMinutes)
                
                if (currentState.calendarSyncEnabled) {
                    CalendarHelper.addEventToCalendar(
                        context = app,
                        title = "집중 완료",
                        description = "ADHD 블록 스케줄러를 통한 집중 세션",
                        startTime = finishedBlock.startTime,
                        durationMinutes = finishedBlock.durationMinutes
                    )
                }
            }
        }

        val updatedBlocks = currentState.timeBlocks.mapIndexed { index, block ->
            if (index == currentIndex) block.copy(isCompleted = true) else block
        }

        _uiState.update { it.copy(timeBlocks = updatedBlocks) }
    }

    fun updateBlocksPerHour(count: Int) {
        viewModelScope.launch {
            settingsRepository.setBlocksPerHour(count)
        }
    }

    fun updateRestMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setRestMinutes(minutes)
        }
    }

    fun updateCalendarSync(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCalendarSyncEnabled(enabled)
        }
    }

    fun updateVibration(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVibrationEnabled(enabled)
        }
        if (enabled) {
            notificationHelper.vibrateDeviceShort()
        }
    }

    fun toggleBlock(startTimeMillis: Long) {
        _uiState.update { state ->
            val newSelected = state.selectedBlocks.toMutableSet()
            if (newSelected.contains(startTimeMillis)) {
                // 이미 선택된 블록을 누른 경우: 해당 블록보다 늦은 모든 블록 선택 해제 (범위 축소)
                val remaining = newSelected.filter { it < startTimeMillis }.toSet()
                state.copy(selectedBlocks = remaining)
            } else {
                newSelected.add(startTimeMillis)
                // 새로운 블록 선택 시 범위 채우기
                val min = newSelected.min()
                val max = newSelected.max()
                var current = min
                val filled = mutableSetOf<Long>()
                while (current <= max) {
                    filled.add(current)
                    current += 15 * 60 * 1000L
                }
                state.copy(selectedBlocks = filled)
            }
        }
    }

    fun clearSelectedBlocks() {
        _uiState.update { it.copy(selectedBlocks = emptySet()) }
    }

    fun saveSettings(alarmInterval: Int, vibration: Boolean, calendarSync: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAlarmIntervalMinutes(alarmInterval)
            settingsRepository.setVibrationEnabled(vibration)
            settingsRepository.setCalendarSyncEnabled(calendarSync)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as ADHDBlockSchedulerApplication
                return SchedulerViewModel(
                    application,
                    application.taskRepository,
                    application.statsRepository,
                    application.settingsRepository,
                    application.scheduleRepository
                ) as T
            }
        }
    }
}

data class SchedulerUiState(
    val timeBlocks: List<TimeBlock> = emptyList(),
    val dailySchedules: List<ScheduleBlock> = emptyList(),
    val selectedBlocks: Set<Long> = emptySet(),
    val tasks: List<Task> = emptyList(),
    val selectedTaskId: String? = null,
    val currentBlockIndex: Int = 0,
    val remainingSeconds: Int = 0,
    val totalRemainingSeconds: Int = 0,
    val sessionTotalMinutes: Int = 60,
    val isRunning: Boolean = false,
    val calendarSyncEnabled: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val alarmIntervalMinutes: Int = 15,
    val currentScheduleId: String? = null
)
