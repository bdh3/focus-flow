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

    override fun onCreate() {
        super.onCreate()
        startForeground(NotificationHelper.NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Focus Flow")
            .setContentText("타이머가 실행 중입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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

        timerJob = serviceScope.launch {
            while (_totalRemainingSeconds.value > 0) {
                delay(1000L)
                val currentTotalRemaining = maxOf(0, _totalRemainingSeconds.value - 1)
                
                val sessionElapsedSeconds = totalSecondsAtStart - currentTotalRemaining
                val intervalSeconds = alarmIntervalMinutes * 60
                val newBlockIndex = sessionElapsedSeconds / intervalSeconds
                
                if (newBlockIndex != _currentBlockIndex.value) {
                    val elapsedMinutes = (newBlockIndex) * alarmIntervalMinutes
                    val isFinished = currentTotalRemaining <= 0
                    onTransition(taskTitle, elapsedMinutes, isFinished)
                    _currentBlockIndex.value = newBlockIndex
                }

                _totalRemainingSeconds.value = currentTotalRemaining
                _remainingSeconds.value = intervalSeconds - (sessionElapsedSeconds % intervalSeconds)
                
                // 실시간 10초 단위 알림 갱신 제거 (요구사항 5번)
                // if (currentTotalRemaining % 10 == 0) { 
                //     updateNotification()
                // }
            }

            _isRunning.value = false
            _totalRemainingSeconds.value = 0
            
            // 세션 종료 알림 명시적 호출 (요구사항 7번)
            onTransition(taskTitle, totalSecondsAtStart / 60, true)

            onFinished()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    fun pauseTimer() {
        timerJob?.cancel()
        _isRunning.value = false
    }

    fun stopTimer() {
        timerJob?.cancel()
        _isRunning.value = false
        _totalRemainingSeconds.value = 0
        _currentBlockIndex.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val minutes = _totalRemainingSeconds.value / 60
        val seconds = _totalRemainingSeconds.value % 60
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Focus Flow - $taskTitle")
            .setContentText("남은 시간: ${String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }
}
