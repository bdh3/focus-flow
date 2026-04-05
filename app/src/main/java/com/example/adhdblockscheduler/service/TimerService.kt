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

    private val _config = MutableStateFlow<TimerConfig?>(null)
    val config = _config.asStateFlow()

    data class TimerConfig(
        val intervalMinutes: Int,
        val restMinutes: Int,
        val totalSecondsAtStart: Int,
        val taskTitle: String,
        val vibrationEnabled: Boolean
    )

    // Configuration
    private var alarmIntervalMinutes = 15
    private var restMinutes = 0
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
        rest: Int,
        totalSec: Int,
        title: String,
        vibrate: Boolean,
        onTransition: (String, Int, Boolean) -> Unit,
        onFinished: () -> Unit
    ) {
        this.alarmIntervalMinutes = interval
        this.restMinutes = rest
        this.totalSecondsAtStart = totalSec
        this.taskTitle = title
        this.vibrationEnabled = vibrate
        this.onTransition = onTransition
        this.onFinished = onFinished
        
        _config.value = TimerConfig(interval, rest, totalSec, title, vibrate)
    }

    fun startTimer(initialTotalRemaining: Int) {
        stopAllAlarms() // 기존 알람 취소
        timerJob?.cancel()
        _isRunning.value = true
        
        val focusSeconds = alarmIntervalMinutes * 60
        val restSeconds = restMinutes * 60
        val cycleSeconds = focusSeconds + restSeconds
        
        val initialElapsedSeconds = totalSecondsAtStart - initialTotalRemaining
        
        // 초기 인덱스 계산 (사이클 고려)
        _currentBlockIndex.value = if (restSeconds <= 0) {
            if (focusSeconds > 0) initialElapsedSeconds / focusSeconds else 0
        } else {
            val cycleIdx = initialElapsedSeconds / cycleSeconds
            val offsetInCycle = initialElapsedSeconds % cycleSeconds
            if (offsetInCycle < focusSeconds) (cycleIdx * 2) else (cycleIdx * 2 + 1)
        }

        targetEndTimeMillis = SystemClock.elapsedRealtime() + (initialTotalRemaining * 1000L)
        _totalRemainingSeconds.value = initialTotalRemaining
        
        wakeLock?.acquire(initialTotalRemaining * 1000L + 60000L) 
        startForeground(NotificationHelper.NOTIFICATION_ID, createSilentForegroundNotification())

        // AlarmManager에 중간 알람들 예약
        scheduleAllAlarms(initialTotalRemaining)

        timerJob = serviceScope.launch {
            // 첫 번째 정수 초를 즉시 반영하여 1초 지연 현상 방지
            _totalRemainingSeconds.value = initialTotalRemaining
            
            val initialBlockRemaining = if (restSeconds <= 0) {
                focusSeconds - (initialElapsedSeconds % focusSeconds)
            } else {
                val offset = initialElapsedSeconds % cycleSeconds
                if (offset < focusSeconds) focusSeconds - offset else cycleSeconds - offset
            }
            _remainingSeconds.value = initialBlockRemaining.toInt()

            while (true) {
                delay(1000L) 
                
                val now = SystemClock.elapsedRealtime()
                val remainingMillis = targetEndTimeMillis - now
                
                if (remainingMillis <= 0) break
                
                val currentTotalRemaining = ((remainingMillis + 500) / 1000).toInt()
                _totalRemainingSeconds.value = currentTotalRemaining
                
                val sessionElapsedSeconds = totalSecondsAtStart - currentTotalRemaining
                
                val (newBlockIndex, currentBlockRemaining) = if (restSeconds <= 0) {
                    val idx = sessionElapsedSeconds / focusSeconds
                    val rem = focusSeconds - (sessionElapsedSeconds % focusSeconds)
                    idx to rem
                } else {
                    val cycleIdx = sessionElapsedSeconds / cycleSeconds
                    val offsetInCycle = sessionElapsedSeconds % cycleSeconds
                    if (offsetInCycle < focusSeconds) {
                        (cycleIdx * 2) to (focusSeconds - offsetInCycle)
                    } else {
                        (cycleIdx * 2 + 1) to (cycleSeconds - offsetInCycle)
                    }
                }
                
                if (newBlockIndex != _currentBlockIndex.value && currentTotalRemaining > 0) {
                    _currentBlockIndex.value = newBlockIndex
                }
                _remainingSeconds.value = currentBlockRemaining.toInt()
            }

            // 완료 처리
            _totalRemainingSeconds.value = 0
            _remainingSeconds.value = 0
            _isRunning.value = false
            
            delay(500L) // UI 업데이트 대기
            onFinished()
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
        }
    }

    private fun scheduleAllAlarms(initialRemainingSeconds: Int) {
        stopAllAlarms()
        
        val focusSeconds = alarmIntervalMinutes * 60
        val restSeconds = restMinutes * 60
        val cycleSeconds = focusSeconds + restSeconds
        val currentTime = SystemClock.elapsedRealtime()
        
        var elapsedAtNextAlarm = (totalSecondsAtStart - initialRemainingSeconds).toLong()
        
        // 다음 알람 시점들을 순차적으로 계산하여 예약
        while (true) {
            val cycleIdx = (elapsedAtNextAlarm / cycleSeconds).toInt()
            val offsetInCycle = (elapsedAtNextAlarm % cycleSeconds).toInt()
            
            val secondsUntilNextBoundary = if (restSeconds <= 0) {
                focusSeconds - offsetInCycle
            } else {
                if (offsetInCycle < focusSeconds) focusSeconds - offsetInCycle
                else cycleSeconds - offsetInCycle
            }
            
            elapsedAtNextAlarm += secondsUntilNextBoundary
            if (elapsedAtNextAlarm >= totalSecondsAtStart) break
            
            val totalRemainingAtAlarm = (totalSecondsAtStart - elapsedAtNextAlarm).toInt()
            val nextAlarmTime = currentTime + ((elapsedAtNextAlarm - (totalSecondsAtStart - initialRemainingSeconds)) * 1000L)

            val intent = Intent(this, com.example.adhdblockscheduler.service.TimerAlarmReceiver::class.java).apply {
                putExtra("taskTitle", taskTitle)
                putExtra("elapsedMinutes", (elapsedAtNextAlarm / 60).toInt())
                putExtra("isFinished", false)
                putExtra("vibrationEnabled", vibrationEnabled)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, elapsedAtNextAlarm.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextAlarmTime, pendingIntent)
            pendingAlarms.add(pendingIntent)
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
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun skipToNext() {
        val currentRemaining = _remainingSeconds.value
        val totalRemaining = _totalRemainingSeconds.value
        
        // 현재 남은 블록 시간만큼 전체 남은 시간에서 차감
        val newTotalRemaining = (totalRemaining - currentRemaining).coerceAtLeast(0)
        _totalRemainingSeconds.value = newTotalRemaining
        
        if (newTotalRemaining <= 0) {
            stopTimer()
            onFinished()
            return
        }

        // 다음 블록 인덱스로 이동
        val nextIndex = _currentBlockIndex.value + 1
        _currentBlockIndex.value = nextIndex
        
        // 다음 블록의 성격(집중/휴식) 결정: 짝수(0, 2, 4...) 집중, 홀수(1, 3, 5...) 휴식
        val isRestNext = (restMinutes > 0) && (nextIndex % 2 != 0)
        val nextInterval = if (isRestNext) restMinutes else alarmIntervalMinutes
        
        // 다음 블록의 시간 설정 (전체 남은 시간을 초과하지 않음)
        _remainingSeconds.value = Math.min(nextInterval * 60, newTotalRemaining)
        
        // 타이머 재설정 및 재시작 (알람 재스케줄링 포함)
        startTimer(newTotalRemaining)
        
        // 상태 알림 전송 ("휴식 시작" 또는 "집중 재개")
        onTransition(if (isRestNext) "휴식 시작" else "집중 재개", 0, false)
    }

    fun stopTimer() {
        stopAllAlarms()
        timerJob?.cancel()
        _isRunning.value = false
        _totalRemainingSeconds.value = 0
        _currentBlockIndex.value = 0
        _config.value = null
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
