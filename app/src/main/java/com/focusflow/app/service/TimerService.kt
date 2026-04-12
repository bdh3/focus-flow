package com.focusflow.app.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.focusflow.app.model.BlockType
import com.focusflow.app.util.NotificationHelper
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
        val vibrationEnabled: Boolean,
        val soundEnabled: Boolean,
        val focusVibrationPatternId: String = "focus_default",
        val restVibrationPatternId: String = "rest_default",
        val finishVibrationPatternId: String = "finish_triple",
        val focusSoundId: String = "focus_default",
        val restSoundId: String = "rest_default",
        val finishSoundId: String = "finish_triple"
    )

    // Configuration
    private var alarmIntervalMinutes = 15
    private var restMinutes = 0
    private var totalSecondsAtStart = 0
    private var taskTitle = "작업"
    private var vibrationEnabled = true
    private var soundEnabled = true
    private var focusVibrationPatternId = "focus_default"
    private var restVibrationPatternId = "rest_default"
    private var finishVibrationPatternId = "finish_triple"
    private var focusSoundId = "focus_default"
    private var restSoundId = "rest_default"
    private var finishSoundId = "finish_triple"
    private var onTransition: (String, Int, Boolean, BlockType) -> Unit = { _, _, _, _ -> }
    private var onFinished: () -> Unit = {}

    private var targetEndTimeMillis: Long = 0
    private val pendingAlarms = mutableListOf<PendingIntent>()

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopDisplayUpdateLoop()
                Intent.ACTION_SCREEN_ON -> startDisplayUpdateLoop()
            }
        }
    }

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusFlow::TimerWakeLock")
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("stop_alarm", false) == true) {
            NotificationHelper(this).stopSound()
        }
        return super.onStartCommand(intent, flags, startId)
    }

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
        interval: Int, rest: Int, totalSec: Int, title: String, 
        vibrate: Boolean, sound: Boolean, focusPatternId: String, 
        restPatternId: String, finishPatternId: String, focusSound: String, 
        restSound: String, finishSound: String, 
        onTransition: (String, Int, Boolean, BlockType) -> Unit, onFinished: () -> Unit
    ) {
        this.alarmIntervalMinutes = interval
        this.restMinutes = rest
        this.totalSecondsAtStart = totalSec
        this.taskTitle = title
        this.vibrationEnabled = vibrate
        this.soundEnabled = sound
        this.focusVibrationPatternId = focusPatternId
        this.restVibrationPatternId = restPatternId
        this.finishVibrationPatternId = finishPatternId
        this.focusSoundId = focusSound
        this.restSoundId = restSound
        this.finishSoundId = finishSound
        this.onTransition = onTransition
        this.onFinished = onFinished
        
        _config.value = TimerConfig(
            interval, rest, totalSec, title, vibrate, sound, 
            focusPatternId, restPatternId, finishPatternId, 
            focusSound, restSound, finishSound
        )
    }

    fun startTimer(initialTotalRemaining: Int) {
        stopAllAlarms()
        timerJob?.cancel()
        _isRunning.value = true
        
        val initialElapsedSeconds = totalSecondsAtStart - initialTotalRemaining
        targetEndTimeMillis = SystemClock.elapsedRealtime() + (initialTotalRemaining * 1000L)
        _totalRemainingSeconds.value = initialTotalRemaining
        
        startForeground(NotificationHelper.NOTIFICATION_ID, createSilentForegroundNotification())
        scheduleAllAlarms(initialTotalRemaining)

        if (initialElapsedSeconds == 0) {
            onTransition("$taskTitle (집중 시작)", 0, false, BlockType.FOCUS)
        }

        startDisplayUpdateLoop()
    }

    private fun startDisplayUpdateLoop() {
        if (!_isRunning.value) return
        timerJob?.cancel()
        
        timerJob = serviceScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val remainingMillis = targetEndTimeMillis - now
                
                if (remainingMillis <= 0) break
                
                val currentTotalRemaining = ((remainingMillis + 500) / 1000).toInt()
                _totalRemainingSeconds.value = currentTotalRemaining
                updateTimeStates(currentTotalRemaining)
                
                delay(1000L) 
            }

            if (_isRunning.value && SystemClock.elapsedRealtime() >= targetEndTimeMillis) {
                handleTimerFinished()
            }
        }
    }

    private fun stopDisplayUpdateLoop() {
        timerJob?.cancel()
    }

    private fun updateTimeStates(currentTotalRemaining: Int) {
        val focusSeconds = alarmIntervalMinutes * 60
        val restSeconds = restMinutes * 60
        val cycleSeconds = focusSeconds + restSeconds
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

    private fun handleTimerFinished() {
        _totalRemainingSeconds.value = 0
        _remainingSeconds.value = 0
        _isRunning.value = false
        
        val totalElapsedMinutes = totalSecondsAtStart / 60
        onTransition(taskTitle, totalElapsedMinutes, true, BlockType.FOCUS)
        
        serviceScope.launch {
            delay(500L) 
            onFinished()
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
        
        while (true) {
            val offsetInCycle = (elapsedAtNextAlarm % cycleSeconds).toInt()
            val secondsUntilNextBoundary = if (restSeconds <= 0) focusSeconds - (offsetInCycle % focusSeconds)
            else if (offsetInCycle < focusSeconds) focusSeconds - offsetInCycle
            else cycleSeconds - offsetInCycle
            
            elapsedAtNextAlarm += secondsUntilNextBoundary
            if (elapsedAtNextAlarm >= totalSecondsAtStart) break
            
            val nextAlarmTime = currentTime + ((elapsedAtNextAlarm - (totalSecondsAtStart - initialRemainingSeconds)) * 1000L)
            val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
                val currentInx = pendingAlarms.size
                val currentBlockType = if (restSeconds <= 0) BlockType.FOCUS 
                                       else if (currentInx % 2 == 0) BlockType.REST 
                                       else BlockType.FOCUS
                val nextLabel = if (restSeconds <= 0) "집중" else if (currentInx % 2 == 0) "휴식 시작" else "집중 재개"
                putExtra("taskTitle", "$taskTitle ($nextLabel)")
                putExtra("elapsedMinutes", (elapsedAtNextAlarm / 60).toInt())
                putExtra("isFinished", false)
                putExtra("vibrationEnabled", vibrationEnabled)
                putExtra("soundEnabled", soundEnabled)
                putExtra("blockType", currentBlockType.name)
                putExtra("focusVibrationPatternId", focusVibrationPatternId)
                putExtra("restVibrationPatternId", restVibrationPatternId)
                putExtra("finishVibrationPatternId", finishVibrationPatternId)
                putExtra("focusSoundId", focusSoundId)
                putExtra("restSoundId", restSoundId)
                putExtra("finishSoundId", finishSoundId)
            }
            val pendingIntent = PendingIntent.getBroadcast(this, elapsedAtNextAlarm.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextAlarmTime, pendingIntent)
            pendingAlarms.add(pendingIntent)
        }

        if (initialRemainingSeconds > 0) {
            val finishIntent = Intent(this, TimerAlarmReceiver::class.java).apply {
                putExtra("taskTitle", taskTitle)
                putExtra("elapsedMinutes", totalSecondsAtStart / 60)
                putExtra("isFinished", true)
                putExtra("vibrationEnabled", vibrationEnabled)
                putExtra("soundEnabled", soundEnabled)
                putExtra("blockType", BlockType.FOCUS.name)
                putExtra("focusVibrationPatternId", focusVibrationPatternId)
                putExtra("restVibrationPatternId", restVibrationPatternId)
                putExtra("finishVibrationPatternId", finishVibrationPatternId)
                putExtra("focusSoundId", focusSoundId)
                putExtra("restSoundId", restSoundId)
                putExtra("finishSoundId", finishSoundId)
            }
            val finishPI = PendingIntent.getBroadcast(this, 99999, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, currentTime + (initialRemainingSeconds * 1000L), finishPI)
            pendingAlarms.add(finishPI)
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
        stopDisplayUpdateLoop()
        _isRunning.value = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun skipToNext() {
        val totalRemaining = _totalRemainingSeconds.value
        val currentRemaining = _remainingSeconds.value
        val newTotalRemaining = (totalRemaining - currentRemaining).coerceAtLeast(0)
        
        if (newTotalRemaining <= 0) {
            stopTimer()
            handleTimerFinished()
            return
        }

        startTimer(newTotalRemaining)
        val isRestNext = (restMinutes > 0) && ((_currentBlockIndex.value + 1) % 2 != 0)
        onTransition("$taskTitle (${if (isRestNext) "휴식 시작" else "집중 재개"})", (totalSecondsAtStart - newTotalRemaining) / 60, false, if (isRestNext) BlockType.REST else BlockType.FOCUS)
    }

    fun stopTimer() {
        stopAllAlarms()
        stopDisplayUpdateLoop()
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
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) {}
        stopAllAlarms()
        stopDisplayUpdateLoop()
        releaseWakeLock()
    }
}
