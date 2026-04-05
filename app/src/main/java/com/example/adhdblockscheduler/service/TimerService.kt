package com.example.adhdblockscheduler.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.adhdblockscheduler.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TimerService : Service() {

    private val binder = TimerBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
    private var onTransition: (String, Int, Boolean) -> Unit = { _, _, _ -> }
    private var onFinished: () -> Unit = {}

    private var targetEndTimeMillis: Long = 0

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusFlow::TimerWakeLock")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createSilentForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.SILENT_CHANNEL_ID)
            .setContentTitle("Focus Flow")
            .setContentText("타이머가 백그라운드에서 정확하게 실행 중입니다.")
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
        this.onTransition = onTransition
        this.onFinished = onFinished
    }

    fun startTimer(initialTotalRemaining: Int) {
        timerJob?.cancel()
        _isRunning.value = true
        
        // 중요: 재개 시 현재 블록 인덱스를 정확히 동기화하여 중복 알림 방지
        val intervalSeconds = alarmIntervalMinutes * 60
        val initialElapsedSeconds = totalSecondsAtStart - initialTotalRemaining
        _currentBlockIndex.value = if (intervalSeconds > 0) initialElapsedSeconds / intervalSeconds else 0

        // 절대 종료 시간 계산 (SystemClock.elapsedRealtime 사용 - 잠들어도 계속 흐름)
        targetEndTimeMillis = SystemClock.elapsedRealtime() + (initialTotalRemaining * 1000L)
        _totalRemainingSeconds.value = initialTotalRemaining
        
        // WakeLock 획득 (잠들지 않도록 CPU 강제 깨움)
        wakeLock?.acquire(initialTotalRemaining * 1000L + 60000L) 

        startForeground(NotificationHelper.NOTIFICATION_ID, createSilentForegroundNotification())

        timerJob = serviceScope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val remainingMillis = targetEndTimeMillis - now
                
                if (remainingMillis <= 0) break
                
                val currentTotalRemaining = (remainingMillis / 1000).toInt()
                _totalRemainingSeconds.value = currentTotalRemaining
                
                val sessionElapsedSeconds = totalSecondsAtStart - currentTotalRemaining
                val intervalSeconds = alarmIntervalMinutes * 60
                val newBlockIndex = sessionElapsedSeconds / intervalSeconds
                
                // 블록 전환 알림 (15분 등 주기가 지났을 때)
                if (newBlockIndex != _currentBlockIndex.value && currentTotalRemaining > 0) {
                    val elapsedMinutes = (newBlockIndex) * alarmIntervalMinutes
                    onTransition(taskTitle, elapsedMinutes, false)
                    _currentBlockIndex.value = newBlockIndex
                }

                _remainingSeconds.value = intervalSeconds - (sessionElapsedSeconds % intervalSeconds)

                delay(1000L)
            }

            // 완료 처리
            _isRunning.value = false
            _totalRemainingSeconds.value = 0
            onTransition(taskTitle, totalSecondsAtStart / 60, true)

            delay(2000L)
            onFinished()
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    fun pauseTimer() {
        timerJob?.cancel()
        _isRunning.value = false
        releaseWakeLock()
        stopForeground(true)
    }

    fun stopTimer() {
        timerJob?.cancel()
        _isRunning.value = false
        _totalRemainingSeconds.value = 0
        _currentBlockIndex.value = 0
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        releaseWakeLock()
    }
}
