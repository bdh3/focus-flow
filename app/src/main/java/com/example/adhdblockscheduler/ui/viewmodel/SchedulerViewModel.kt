package com.example.adhdblockscheduler.ui.viewmodel

import android.app.Application
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

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        _uiState.update { it.copy(isRunning = true) }
        
        timerJob = viewModelScope.launch {
            val totalSecondsAtStart = _uiState.value.timeBlocks.sumOf { it.durationMinutes * 60 }
            if (_uiState.value.totalRemainingSeconds <= 0) {
                _uiState.update { it.copy(totalRemainingSeconds = totalSecondsAtStart) }
            }

            val startTime = System.currentTimeMillis()
            val initialTotalRemaining = _uiState.value.totalRemainingSeconds

            while (_uiState.value.totalRemainingSeconds > 0) {
                delay(1000L)
                val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                val currentTotalRemaining = maxOf(0, initialTotalRemaining - elapsedSeconds)
                
                // 경과 시간 계산
                val sessionElapsedSeconds = totalSecondsAtStart - currentTotalRemaining
                val currentBlockIndex = (sessionElapsedSeconds / (_uiState.value.alarmIntervalMinutes * 60)).toInt()
                val totalBlocks = (totalSecondsAtStart / (_uiState.value.alarmIntervalMinutes * 60)).toInt()
                
                if (currentBlockIndex != _uiState.value.currentBlockIndex) {
                    val taskTitle = _uiState.value.tasks.find { it.id == _uiState.value.selectedTaskId }?.title ?: "작업"
                    val elapsedMinutes = currentBlockIndex * _uiState.value.alarmIntervalMinutes
                    val isLast = currentBlockIndex >= totalBlocks
                    
                    onBlockTransition(taskTitle, elapsedMinutes, isLast)
                    _uiState.update { it.copy(currentBlockIndex = currentBlockIndex) }
                }

                _uiState.update { it.copy(
                    totalRemainingSeconds = currentTotalRemaining,
                    remainingSeconds = (_uiState.value.alarmIntervalMinutes * 60) - (sessionElapsedSeconds % (_uiState.value.alarmIntervalMinutes * 60))
                ) }
            }

            _uiState.update { it.copy(isRunning = false, totalRemainingSeconds = 0) }
            onSessionFinished()
        }
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
        val currentScheduleId = _uiState.value.currentScheduleId
        _uiState.update { it.copy(
            timeBlocks = it.timeBlocks.map { b -> b.copy(isCompleted = true) }
        ) }
        
        if (currentScheduleId != null) {
            viewModelScope.launch {
                val schedule = scheduleRepository.getScheduleById(currentScheduleId)
                if (schedule != null) {
                    scheduleRepository.updateSchedule(schedule.copy(isCompleted = true))
                }
            }
        }
    }

    private fun pauseTimer() {
        timerJob?.cancel()
        _uiState.update { it.copy(isRunning = false) }
    }

    fun skipBlock() {
        val state = _uiState.value
        if (state.currentBlockIndex < state.timeBlocks.size - 1) {
            val nextIndex = state.currentBlockIndex + 1
            _uiState.update { it.copy(
                currentBlockIndex = nextIndex,
                remainingSeconds = state.timeBlocks[nextIndex].durationMinutes * 60
            ) }
        } else {
            _uiState.update { it.copy(totalRemainingSeconds = 0) }
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
        _uiState.update { it.copy(calendarSyncEnabled = enabled) }
    }

    fun updateVibration(enabled: Boolean) {
        _uiState.update { it.copy(vibrationEnabled = enabled) }
        if (enabled) {
            notificationHelper.vibrateDeviceShort()
        }
    }

    fun toggleBlock(startTimeMillis: Long) {
        _uiState.update { state ->
            val newSelected = state.selectedBlocks.toMutableSet()
            if (newSelected.contains(startTimeMillis)) {
                newSelected.remove(startTimeMillis)
            } else {
                newSelected.add(startTimeMillis)
            }
            // 선택된 블록들 중 최소값과 최대값 사이를 채우는 로직 (드래그 효과 보완)
            if (newSelected.size >= 2) {
                val min = newSelected.min()
                val max = newSelected.max()
                var current = min
                while (current <= max) {
                    newSelected.add(current)
                    current += 15 * 60 * 1000L
                }
            }
            state.copy(selectedBlocks = newSelected)
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
