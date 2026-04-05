package com.example.adhdblockscheduler.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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

class TimerService : Service() {

    private val binder = TimerBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var alarmManager: AlarmManager

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

    private var targetEndTimeMillis: Long = 0
    private val pendingAlarms = mutableListOf<PendingIntent>()

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusFlow::TimerWakeLock")
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createSilentForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.SILENT_SERVICE_CHANNEL_ID)
            .setContentTitle("Focus Flow")
            .setContentText("타이머가 실행 중입니다.")
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
        stopAllAlarms() // 기존 알람 취소
        timerJob?.cancel()
        _isRunning.value = true
        
        val intervalSeconds = alarmIntervalMinutes * 60
        val initialElapsedSeconds = totalSecondsAtStart - initialTotalRemaining
        _currentBlockIndex.value = if (intervalSeconds > 0) initialElapsedSeconds / intervalSeconds else 0

        targetEndTimeMillis = SystemClock.elapsedRealtime() + (initialTotalRemaining * 1000L)
        _totalRemainingSeconds.value = initialTotalRemaining
        
        wakeLock?.acquire(initialTotalRemaining * 1000L + 60000L) 
        startForeground(NotificationHelper.NOTIFICATION_ID, createSilentForegroundNotification())

        // AlarmManager에 중간 알람들 예약 (가장 중요한 부분)
        scheduleAllAlarms(initialTotalRemaining)

        timerJob = serviceScope.launch {
            // 첫 번째 정수 초를 즉시 반영하여 1초 지연 현상 방지
            _totalRemainingSeconds.value = initialTotalRemaining
            _remainingSeconds.value = if (initialTotalRemaining % intervalSeconds == 0) intervalSeconds else initialTotalRemaining % intervalSeconds

            while (true) {
                delay(1000L) // 먼저 대기하고 그 다음 초를 계산
                
                val now = SystemClock.elapsedRealtime()
                val remainingMillis = targetEndTimeMillis - now
                
                if (remainingMillis <= 0) break
                
                // +500ms 보정으로 정수 변환 시 1초가 튀는 현상 방지 (Rounding)
                val currentTotalRemaining = ((remainingMillis + 500) / 1000).toInt()
                _totalRemainingSeconds.value = currentTotalRemaining
                
                val sessionElapsedSeconds = totalSecondsAtStart - currentTotalRemaining
                val newBlockIndex = sessionElapsedSeconds / intervalSeconds
                
                if (newBlockIndex != _currentBlockIndex.value && currentTotalRemaining > 0) {
                    _currentBlockIndex.value = newBlockIndex
                }

                val currentBlockRemaining = intervalSeconds - (sessionElapsedSeconds % intervalSeconds)
                _remainingSeconds.value = if (currentBlockRemaining == 0) intervalSeconds else currentBlockRemaining
            }

            // 완료 처리
            _isRunning.value = false
            _totalRemainingSeconds.value = 0
            
            delay(1000L)
            onFinished()
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
        }
    }

    private fun scheduleAllAlarms(initialRemainingSeconds: Int) {
        stopAllAlarms() // 확실히 비우고 시작
        
        val intervalSeconds = alarmIntervalMinutes * 60
        val currentTime = SystemClock.elapsedRealtime()
        
        // 1. 현재 블록이 끝나기까지 남은 시간 계산
        val secondsElapsedInCurrentBlock = (totalSecondsAtStart - initialRemainingSeconds) % intervalSeconds
        val secondsUntilNextBlock = intervalSeconds - secondsElapsedInCurrentBlock
        
        var nextAlarmOffsetSeconds = secondsUntilNextBlock.toLong()
        // 현재까지 진행된 총 시간 (분 단위)
        var totalElapsedMinutes = ((totalSecondsAtStart - initialRemainingSeconds + secondsUntilNextBlock) / 60)

        // 2. 중간 알람들 예약 (종료 전까지)
        while (nextAlarmOffsetSeconds < initialRemainingSeconds) {
            val intent = Intent(this, com.example.adhdblockscheduler.service.TimerAlarmReceiver::class.java).apply {
                putExtra("taskTitle", taskTitle)
                putExtra("elapsedMinutes", totalElapsedMinutes)
                putExtra("isFinished", false)
                putExtra("vibrationEnabled", vibrationEnabled)
            }
            
            // PendingIntent ID를 totalElapsedMinutes로 설정하여 고유성 확보
            val pendingIntent = PendingIntent.getBroadcast(
                this, totalElapsedMinutes, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val alarmTime = currentTime + (nextAlarmOffsetSeconds * 1000L)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTime, pendingIntent)
            pendingAlarms.add(pendingIntent)
            
            nextAlarmOffsetSeconds += intervalSeconds
            totalElapsedMinutes += alarmIntervalMinutes
        }

        // 3. 최종 종료 알람 예약 (남은 시간이 0보다 클 때만)
        if (initialRemainingSeconds > 0) {
            val finishIntent = Intent(this, com.example.adhdblockscheduler.service.TimerAlarmReceiver::class.java).apply {
                putExtra("taskTitle", taskTitle)
                putExtra("elapsedMinutes", totalSecondsAtStart / 60)
                putExtra("isFinished", true)
                putExtra("vibrationEnabled", vibrationEnabled)
            }
            val finishPendingIntent = PendingIntent.getBroadcast(
                this, 99999, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, currentTime + (initialRemainingSeconds * 1000L), finishPendingIntent
            )
            pendingAlarms.add(finishPendingIntent)
        }
    }

    private fun stopAllAlarms() {
        pendingAlarms.forEach { alarmManager.cancel(it) }
        pendingAlarms.clear()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    fun pauseTimer() {
        stopAllAlarms()
        timerJob?.cancel()
        _isRunning.value = false
        releaseWakeLock()
        stopForeground(true)
    }

    fun stopTimer() {
        stopAllAlarms()
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
        stopAllAlarms()
        timerJob?.cancel()
        releaseWakeLock()
    }
}
