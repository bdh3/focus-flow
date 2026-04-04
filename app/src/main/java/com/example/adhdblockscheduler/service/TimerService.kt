package com.example.adhdblockscheduler.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.adhdblockscheduler.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class TimerService : Service() {

    private val binder = TimerBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds = _remainingSeconds.asStateFlow()

    private val _totalRemainingSeconds = MutableStateFlow(0)
    val totalRemainingSeconds = _totalRemainingSeconds.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _currentBlockIndex = MutableStateFlow(0)
    val currentBlockIndex = _currentBlockIndex.asStateFlow()

    // Configuration
    private var alarmIntervalMinutes = 15
    private var totalSecondsAtStart = 0
    private var taskTitle = "작업"
    private var vibrationEnabled = true
    private var onTransition: (String, Int, Boolean) -> Unit = { _, _, _ -> }
    private var onFinished: () -> Unit = {}

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createSilentForegroundNotification(): Notification {
        // 무음 채널(SILENT_CHANNEL_ID)을 사용하여 알림이 방해되지 않도록 함
        return NotificationCompat.Builder(this, NotificationHelper.SILENT_CHANNEL_ID)
            .setContentTitle("Focus Flow")
            .setContentText("타이머가 백그라운드에서 실행 중입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    fun setTimerConfig(
        interval: Int,
        totalSec: Int,
        title: String,
        vibrate: Boolean,
        onTransition: (String, Int, Boolean) -> Unit,
        onFinished: () -> Unit
    ) {
        this.alarmIntervalMinutes = interval
        this.totalSecondsAtStart = totalSec
        this.taskTitle = title
        this.vibrationEnabled = vibrate
        this.onTransition = onTransition
        this.onFinished = onFinished
    }

    fun startTimer(initialTotalRemaining: Int) {
        timerJob?.cancel()
        _isRunning.value = true
        _totalRemainingSeconds.value = initialTotalRemaining
        
        // 포그라운드 서비스 시작 (무음 알림 사용 - 사용자에게 방해 안 됨)
        startForeground(NotificationHelper.NOTIFICATION_ID, createSilentForegroundNotification())

        timerJob = serviceScope.launch {
            while (_totalRemainingSeconds.value > 0) {
                val currentTotalRemaining = _totalRemainingSeconds.value
                
                val sessionElapsedSeconds = totalSecondsAtStart - currentTotalRemaining
                val intervalSeconds = alarmIntervalMinutes * 60
                val newBlockIndex = sessionElapsedSeconds / intervalSeconds
                
                // 설정된 주기(15분 등)가 되었을 때만 고순위 팝업 알림 발생
                if (newBlockIndex != _currentBlockIndex.value && currentTotalRemaining > 0) {
                    val elapsedMinutes = (newBlockIndex) * alarmIntervalMinutes
                    onTransition(taskTitle, elapsedMinutes, false)
                    _currentBlockIndex.value = newBlockIndex
                }

                _remainingSeconds.value = intervalSeconds - (sessionElapsedSeconds % intervalSeconds)

                delay(1000L)
                _totalRemainingSeconds.value = maxOf(0, currentTotalRemaining - 1)
            }

            // 루프 종료 = 작업 완료 시점
            _isRunning.value = false
            _totalRemainingSeconds.value = 0
            
            // 세션 종료 알림 (onFinished 호출 전에 실행하여 시스템 유실 방지)
            onTransition(taskTitle, totalSecondsAtStart / 60, true)

            delay(2000L) // 알림 팝업이 뜰 수 있도록 충분한 지연 시간 확보
            onFinished()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    fun pauseTimer() {
        timerJob?.cancel()
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun stopTimer() {
        timerJob?.cancel()
        _isRunning.value = false
        _totalRemainingSeconds.value = 0
        _currentBlockIndex.value = 0
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
