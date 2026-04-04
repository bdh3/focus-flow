package com.example.adhdblockscheduler.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.adhdblockscheduler.ADHDBlockSchedulerApplication
import com.example.adhdblockscheduler.data.prefs.SettingsRepository
import com.example.adhdblockscheduler.data.repository.StatsRepository
import com.example.adhdblockscheduler.data.repository.TaskRepository
import com.example.adhdblockscheduler.model.BlockType
import com.example.adhdblockscheduler.model.Task
import com.example.adhdblockscheduler.model.TimeBlock
import com.example.adhdblockscheduler.util.CalendarHelper
import com.example.adhdblockscheduler.util.NotificationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SchedulerViewModel(
    private val app: Application,
    private val repository: TaskRepository,
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(app) {
    
    private val notificationHelper = NotificationHelper(app)
    private val _uiState = MutableStateFlow(SchedulerUiState())
    
    val uiState: StateFlow<SchedulerUiState> = combine(
        _uiState,
        repository.allTasks,
        settingsRepository.calendarSyncEnabled,
        settingsRepository.vibrationEnabled,
        settingsRepository.blocksPerHour,
        settingsRepository.focusBlocksCount,
        settingsRepository.notificationIntervalMinutes
    ) { Array ->
        val state = Array[0] as SchedulerUiState
        val tasks = Array[1] as List<Task>
        val calendarSync = Array[2] as Boolean
        val vibration = Array[3] as Boolean
        val blocksPerHour = Array[4] as Int
        val focusCount = Array[5] as Int
        val interval = Array[6] as Float

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
            blocksPerHour = blocksPerHour,
            focusBlocksCount = focusCount,
            notificationInterval = interval.toInt(),
            totalRemainingSeconds = totalRemaining
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SchedulerUiState()
    )

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.blocksPerHour,
                settingsRepository.focusBlocksCount
            ) { perHour, focus -> perHour to focus }.collect { (perHour, focus) ->
                if (!_uiState.value.isRunning) {
                    generateDefaultBlocks(perHour, focus)
                }
            }
        }
    }

    private fun generateDefaultBlocks(blocksPerHour: Int, focusCount: Int) {
        val blockDuration = 60 / blocksPerHour
        val blocks = mutableListOf<TimeBlock>()
        var currentTime = System.currentTimeMillis()

        for (i in 0 until blocksPerHour) {
            val type = if (i < focusCount) BlockType.FOCUS else BlockType.REST
            blocks.add(TimeBlock(startTime = currentTime, durationMinutes = blockDuration, type = type))
            currentTime += blockDuration * 60 * 1000L
        }
        
        _uiState.update { it.copy(
            timeBlocks = blocks,
            currentBlockIndex = 0,
            remainingSeconds = blocks[0].durationMinutes * 60
        ) }
    }

    fun toggleTimer() {
        if (_uiState.value.isRunning) {
            pauseTimer()
        } else {
            startTimer()
        }
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        _uiState.update { it.copy(isRunning = true) }
        
        timerJob = viewModelScope.launch {
            while (true) {
                val currentState = _uiState.value
                val currentIndex = currentState.currentBlockIndex
                val currentBlock = currentState.timeBlocks.getOrNull(currentIndex) ?: break
                
                val initialSeconds = currentState.remainingSeconds
                val startTime = System.currentTimeMillis()
                var lastIntervalNotificationTime = initialSeconds

                while (_uiState.value.remainingSeconds > 0) {
                    delay(500L)
                    val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                    val newRemaining = maxOf(0, initialSeconds - elapsedSeconds)
                    
                    // 중간 알림 로직 (설정된 간격마다)
                    val interval = _uiState.value.notificationInterval
                    if (interval > 0 && (lastIntervalNotificationTime - newRemaining) >= (interval * 60)) {
                        if (_uiState.value.vibrationEnabled) {
                            notificationHelper.vibrateDeviceShort()
                        }
                        lastIntervalNotificationTime = newRemaining
                    }

                    _uiState.update { it.copy(remainingSeconds = newRemaining) }
                }

                // 블록 종료 및 자동 다음 블록 전환
                val nextIndex = currentIndex + 1
                val nextBlock = currentState.timeBlocks.getOrNull(nextIndex)
                
                onBlockFinished(
                    showNotification = true,
                    finishedType = currentBlock.type,
                    nextType = nextBlock?.type
                )

                if (nextBlock != null) {
                    _uiState.update { it.copy(
                        currentBlockIndex = nextIndex,
                        remainingSeconds = nextBlock.durationMinutes * 60
                    ) }
                    // 다음 블록으로 루프 계속 진행
                } else {
                    // 모든 블록 끝
                    _uiState.update { it.copy(isRunning = false) }
                    break
                }
            }
        }
    }

    private fun pauseTimer() {
        timerJob?.cancel()
        _uiState.update { it.copy(isRunning = false) }
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

    fun updateFocusBlocksCount(count: Int) {
        viewModelScope.launch {
            settingsRepository.setFocusBlocksCount(count)
        }
    }

    fun updateCalendarSync(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCalendarSyncEnabled(enabled)
        }
    }

    fun skipBlock() {
        val currentState = _uiState.value
        val currentIndex = currentState.currentBlockIndex
        val nextIndex = currentIndex + 1
        val nextBlock = currentState.timeBlocks.getOrNull(nextIndex)

        pauseTimer()
        onBlockFinished(
            showNotification = false,
            finishedType = currentState.timeBlocks[currentIndex].type,
            nextType = nextBlock?.type
        )

        if (nextBlock != null) {
            _uiState.update { it.copy(
                currentBlockIndex = nextIndex,
                remainingSeconds = nextBlock.durationMinutes * 60
            ) }
        }
    }

    fun addTask(title: String) {
        viewModelScope.launch {
            repository.insertTask(Task(title = title))
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            val updatedTask = task.copy(isCompleted = !task.isCompleted)
            repository.updateTask(updatedTask)
            if (updatedTask.isCompleted) {
                statsRepository.incrementTaskCount()
            }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }

    fun updateVibration(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVibrationEnabled(enabled)
        }
    }

    fun updateNotificationInterval(minutes: Float) {
        viewModelScope.launch {
            settingsRepository.setNotificationIntervalMinutes(minutes)
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
                    application.settingsRepository
                ) as T
            }
        }
    }
}

data class SchedulerUiState(
    val timeBlocks: List<TimeBlock> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val currentBlockIndex: Int = 0,
    val remainingSeconds: Int = 0,
    val totalRemainingSeconds: Int = 0,
    val isRunning: Boolean = false,
    val calendarSyncEnabled: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val blocksPerHour: Int = 4,
    val focusBlocksCount: Int = 3,
    val notificationInterval: Int = 0
)
