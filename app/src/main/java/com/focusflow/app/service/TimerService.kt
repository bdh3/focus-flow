package com.focusflow.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import com.focusflow.app.R
import com.focusflow.app.ui.MainActivity
import com.focusflow.app.util.BlockType
import com.focusflow.app.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

data class TimerConfig(
    val intervalMinutes: Int, 
    val restMinutes: Int, 
    val totalSecondsAtStart: Int, 
    val title: String,
    val vibrate: Boolean, 
    val sound: Boolean, 
    val useFullScreen: Boolean,
    val focusPatternId: String, 
    val restPatternId: String, 
    val finishPatternId: String,
    val focusSound: String, 
    val restSound: String, 
    val finishSound: String, 
    val focusRingtoneUri: String?, 
    val restRingtoneUri: String?, 
    val finishRingtoneUri: String?
)

class TimerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null
    private var delayedFinishJob: Job? = null
    private var targetEndTimeMillis: Long = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var alarmManager: AlarmManager
    private val pendingAlarms = CopyOnWriteArrayList<PendingIntent>()

    // ViewModel에서 관찰하는 인스턴스 상태 (StateFlow)
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds

    private val _totalRemainingSeconds = MutableStateFlow(0)
    val totalRemainingSeconds: StateFlow<Int> = _totalRemainingSeconds

    private val _currentBlockIndex = MutableStateFlow(0)
    val currentBlockIndex: StateFlow<Int> = _currentBlockIndex

    private val _config = MutableStateFlow<TimerConfig?>(null)
    val config: StateFlow<TimerConfig?> = _config

    // 내부 관리 변수
    private var alarmIntervalMinutes = 15
    private var restMinutes = 0
    private var totalSecondsAtStart = 0
    private var taskTitle = ""
    private var vibrationEnabled = true
    private var soundEnabled = true
    private var useFullScreenAlarm = false
    private var focusVibrationPatternId: String? = null
    private var restVibrationPatternId: String? = null
    private var finishVibrationPatternId: String? = null
    private var focusSoundId = "default"
    private var restSoundId = "default"
    private var finishSoundId = "default"
    private var focusRingtoneUri: String? = null
    private var restRingtoneUri: String? = null
    private var finishRingtoneUri: String? = null

    private var onTransition: ((String, Int, Boolean, BlockType, Boolean) -> Unit)? = null
    private var onFinished: (() -> Unit)? = null

    companion object {
        private const val FINISH_ALARM_ID = 9999
        @Volatile
        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusFlow::TimerWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.getBooleanExtra("stop_alarm", false)) {
                // 알람 액티비티에서 '중단'을 눌렀을 때의 처리
                if (it.getBooleanExtra("isFinished", false)) {
                    // 최종 종료 알람이었으면 서비스 전체 종료
                    stopTimer()
                } else {
                    // [v1.8.0-fix] 알림 액션 버튼 등으로 중단 시 소리/진동 정지 보장
                    NotificationHelper.getInstance(this).stopAllAlerts()
                    // 구간 전환 알람이었으면 소리만 끄고 타이머는 그대로 둠
                    // 만약 화면이 꺼져있을 때 타이머가 멈추지 않도록 다시 한번 보장
                    if (_isRunning.value && timerJob?.isActive != true) {
                        startDisplayUpdateLoop()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = TimerBinder()

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    fun setTimerConfig(
        interval: Int, rest: Int, totalSec: Int, title: String, 
        vibrate: Boolean, sound: Boolean, useFullScreen: Boolean,
        focusPatternId: String, 
        restPatternId: String, finishPatternId: String, focusSound: String, 
        restSound: String, finishSound: String, 
        focusRingtoneUri: String?, restRingtoneUri: String?, finishRingtoneUri: String?,
        onTransition: (String, Int, Boolean, BlockType, Boolean) -> Unit, onFinished: () -> Unit
    ) {
        this.alarmIntervalMinutes = interval
        this.restMinutes = rest
        this.totalSecondsAtStart = totalSec
        this.taskTitle = title
        this.vibrationEnabled = vibrate
        this.soundEnabled = sound
        this.useFullScreenAlarm = useFullScreen
        this.focusVibrationPatternId = focusPatternId
        this.restVibrationPatternId = restPatternId
        this.finishVibrationPatternId = finishPatternId
        this.focusSoundId = focusSound
        this.restSoundId = restSound
        this.finishSoundId = finishSound
        this.focusRingtoneUri = focusRingtoneUri
        this.restRingtoneUri = restRingtoneUri
        this.finishRingtoneUri = finishRingtoneUri
        this.onTransition = onTransition
        this.onFinished = onFinished
        
        _config.value = TimerConfig(
            interval, rest, totalSec, title, vibrate, sound, useFullScreen,
            focusPatternId, restPatternId, finishPatternId, 
            focusSound, restSound, finishSound, 
            focusRingtoneUri, restRingtoneUri, finishRingtoneUri
        )
    }

    fun startTimer(initialTotalRemaining: Int) {
        delayedFinishJob?.cancel() // [v1.8.0-fix] 이전 종료 알람 대기 작업이 있다면 취소
        stopAllAlarms(false)
        timerJob?.cancel()
        _isRunning.value = true
        
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(initialTotalRemaining * 1000L + 60000L)
        }

        targetEndTimeMillis = SystemClock.elapsedRealtime() + (initialTotalRemaining * 1000L)
        _totalRemainingSeconds.value = initialTotalRemaining
        
        val notification = createSilentForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID, 
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }

        scheduleAllAlarms(initialTotalRemaining)
        updateTimeStates(initialTotalRemaining)
        startDisplayUpdateLoop()
    }

    private fun startDisplayUpdateLoop() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            while (isActive && _isRunning.value) {
                val now = SystemClock.elapsedRealtime()
                val remainingMillis = targetEndTimeMillis - now
                
                if (!powerManager.isInteractive && remainingMillis > 5000) {
                    delay(1000) 
                    continue
                }

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

    private fun updateTimeStates(currentTotalRemaining: Int, isManualSkip: Boolean = false) {
        val focusSeconds = alarmIntervalMinutes * 60
        val restSeconds = restMinutes * 60
        val cycleSeconds = focusSeconds + restSeconds
        
        val effectiveTotalSeconds = if (totalSecondsAtStart < currentTotalRemaining) currentTotalRemaining else totalSecondsAtStart
        val sessionElapsedSeconds = (effectiveTotalSeconds - currentTotalRemaining).coerceAtLeast(0)

        val (newBlockIndex, currentBlockRemaining) = if (restSeconds <= 0) {
            (sessionElapsedSeconds / focusSeconds) to (focusSeconds - (sessionElapsedSeconds % focusSeconds))
        } else {
            val cycleIdx = sessionElapsedSeconds / cycleSeconds
            val offsetInCycle = sessionElapsedSeconds % cycleSeconds
            if (offsetInCycle < focusSeconds) (cycleIdx * 2) to (focusSeconds - offsetInCycle)
            else (cycleIdx * 2 + 1) to (cycleSeconds - offsetInCycle)
        }
        
        if (newBlockIndex != _currentBlockIndex.value && currentTotalRemaining > 0) {
            _currentBlockIndex.value = newBlockIndex
            val transitioningTo = if (restSeconds > 0 && (sessionElapsedSeconds % cycleSeconds) >= focusSeconds) BlockType.REST else BlockType.FOCUS
            
            val isDefault = taskTitle.isEmpty()
            val displayTitle = if (isDefault) "집중 세션" else taskTitle
            
            val statusTitle = when {
                transitioningTo == BlockType.REST -> "$displayTitle: 휴식 시작"
                newBlockIndex >= 2 && restSeconds <= 0 -> "$displayTitle: 집중"
                else -> "$displayTitle: 집중 시작"
            }
            
            updateForegroundNotification(if(transitioningTo == BlockType.REST) "$displayTitle: 휴식 중" else "$displayTitle: 집중 중")
            onTransition?.invoke(statusTitle, (sessionElapsedSeconds / 60), false, transitioningTo, isManualSkip)

            // [v1.7.6-fix] 구간 전환 알람이 울린 직후, 다음 구간 알람을 예약합니다.
            // (Single Step Scheduling)
            if (currentTotalRemaining > 0) {
                scheduleAllAlarms(currentTotalRemaining)
            }
        }
        _remainingSeconds.value = currentBlockRemaining
    }

    private fun handleTimerFinished() {
        _totalRemainingSeconds.value = 0
        _remainingSeconds.value = 0
        _isRunning.value = false
        // isServiceRunning은 stopTimer에서만 false로 변경하여 UI 일관성 유지
        
        val displayTitle = taskTitle.ifEmpty { "집중 세션" }
        updateForegroundNotification("$displayTitle: 종료")
        onTransition?.invoke("$displayTitle: 종료", totalSecondsAtStart / 60, true, BlockType.FOCUS, false)
        onFinished?.invoke()
        
        delayedFinishJob = serviceScope.launch {
            // 알람이 울리는 동안(20초)은 서비스 상태를 유지하다가 자동 종료
            delay(NotificationHelper.ALARM_TIMEOUT_MS + 2000) // 2초 여유
            if (!_isRunning.value) { // 그 사이에 유저가 다시 시작하지 않았다면
                stopAllAlarms(true) // 노티피케이션 제거 및 사운드 정지 보장
                stopServiceImmediately()
            }
        }
    }

    private fun scheduleAllAlarms(initialRemainingSeconds: Int) {
        // 기존 알람 모두 제거
        stopAllAlarms(false)
        
        val currentTimeMillis = System.currentTimeMillis()
        val elapsedAtStart = (totalSecondsAtStart - initialRemainingSeconds).toLong()
        
        // [v1.7.6-fix] 모든 알람을 미리 예약하지 않고, '다음 가장 가까운 알람' 하나만 예약합니다.
        // 이는 Z플립 등에서 알람이 중복 울리거나 지연되는 현상을 방지합니다.
        
        val focusSeconds = alarmIntervalMinutes * 60
        val restSeconds = restMinutes * 60
        val cycleSeconds = focusSeconds + restSeconds
        
        var nextElapsed = elapsedAtStart
        val offsetInCycle = (elapsedAtStart % cycleSeconds).toInt()
        
        val secondsUntilNextBoundary = if (restSeconds <= 0) {
            focusSeconds - (offsetInCycle % focusSeconds)
        } else if (offsetInCycle < focusSeconds) {
            focusSeconds - offsetInCycle
        } else {
            cycleSeconds - offsetInCycle
        }
        
        nextElapsed += secondsUntilNextBoundary
        
        // 최종 종료인지 구간 전환인지 확인
        val isFinalFinish = nextElapsed >= totalSecondsAtStart
        val actualNextElapsed = if (isFinalFinish) totalSecondsAtStart.toLong() else nextElapsed
        // [v1.7.6-fix] 즉시 다시 울리는 버그 방지를 위해 최소 10초 이상의 여유를 둡니다.
        val timeUntilNext = (actualNextElapsed - elapsedAtStart).coerceAtLeast(10)
        val nextAlarmTime = currentTimeMillis + (timeUntilNext * 1000L)
        
        val transitioningTo = if (isFinalFinish) BlockType.FOCUS 
                             else if (restSeconds <= 0) BlockType.FOCUS
                             else if ((actualNextElapsed % cycleSeconds).toInt() >= focusSeconds) BlockType.REST
                             else BlockType.FOCUS

        val currentSoundId = when {
            isFinalFinish -> finishSoundId
            transitioningTo == BlockType.REST -> restSoundId
            else -> focusSoundId
        }
        val isFullScreenMode = (currentSoundId == "ringtone") || useFullScreenAlarm
        
        val displayTitle = taskTitle.ifEmpty { "집중 세션" }
        val statusTitle = when {
            isFinalFinish -> "$displayTitle: 종료"
            transitioningTo == BlockType.REST -> "$displayTitle: 휴식 시작"
            else -> "$displayTitle: 집중 시작"
        }

        val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            action = "com.focusflow.app.ALARM_ACTION"
            putExtra("taskTitle", statusTitle)
            putExtra("elapsedMinutes", (actualNextElapsed / 60).toInt())
            putExtra("isFinished", isFinalFinish)
            putExtra("vibrationEnabled", vibrationEnabled)
            putExtra("soundEnabled", soundEnabled)
            putExtra("useFullScreen", isFullScreenMode) 
            putExtra("blockType", transitioningTo.name)
            putExtra("focusVibrationPatternId", focusVibrationPatternId)
            putExtra("restVibrationPatternId", restVibrationPatternId)
            putExtra("finishVibrationPatternId", finishVibrationPatternId)
            putExtra("focusSoundId", focusSoundId)
            putExtra("restSoundId", restSoundId)
            putExtra("finishSoundId", finishSoundId)
            putExtra("ringtoneUri", when {
                isFinalFinish -> finishRingtoneUri
                transitioningTo == BlockType.REST -> restRingtoneUri
                else -> focusRingtoneUri
            })
        }

        // FLAG_CANCEL_CURRENT를 사용하여 기존의 모든 유령 예약을 확실히 파기하고 새로 만듭니다.
        val pi = PendingIntent.getBroadcast(
            this, 
            FINISH_ALARM_ID, 
            intent, 
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmInfo = AlarmManager.AlarmClockInfo(nextAlarmTime, pi)
        alarmManager.setAlarmClock(alarmInfo, pi)
        pendingAlarms.add(pi)
    }

    fun pauseTimer() {
        delayedFinishJob?.cancel()
        stopAllAlarms(false)
        timerJob?.cancel()
        _isRunning.value = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        val displayTitle = taskTitle.ifEmpty { "집중 세션" }
        updateForegroundNotification("$displayTitle: 일시정지됨")
    }

    fun skipToNext() {
        delayedFinishJob?.cancel()
        val focusSeconds = alarmIntervalMinutes * 60
        val restSeconds = restMinutes * 60
        val cycleSeconds = focusSeconds + restSeconds
        val currentTotalRemaining = _totalRemainingSeconds.value
        val sessionElapsedSeconds = (totalSecondsAtStart - currentTotalRemaining).coerceAtLeast(0)

        val nextElapsed = if (restSeconds <= 0) {
            ((sessionElapsedSeconds / focusSeconds) + 1) * focusSeconds
        } else {
            val offsetInCycle = sessionElapsedSeconds % cycleSeconds
            if (offsetInCycle < focusSeconds) {
                (sessionElapsedSeconds / cycleSeconds) * cycleSeconds + focusSeconds
            } else {
                ((sessionElapsedSeconds / cycleSeconds) + 1) * cycleSeconds
            }
        }

        val newRemaining = (totalSecondsAtStart - nextElapsed).toInt().coerceAtLeast(0)
        
        if (newRemaining <= 0) {
            handleTimerFinished()
        } else {
            _totalRemainingSeconds.value = newRemaining
            targetEndTimeMillis = SystemClock.elapsedRealtime() + (newRemaining * 1000L)
            _isRunning.value = true
            startDisplayUpdateLoop()
            updateTimeStates(newRemaining, isManualSkip = true)
            scheduleAllAlarms(newRemaining)
        }
    }

    private fun stopAllAlarms(isFinished: Boolean = false) {
        pendingAlarms.forEach { alarmManager.cancel(it) }
        pendingAlarms.clear()
        
        val intent = Intent(this, TimerAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(this, FINISH_ALARM_ID, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
        
        if (isFinished) {
            NotificationHelper.getInstance(this).stopAllAlerts()
        }
    }

    fun stopTimer() {
        isServiceRunning = false
        stopAllAlarms(true)
        timerJob?.cancel()
        delayedFinishJob?.cancel() // [v1.7.5-fix] 수동 중지 시 자동 종료 예약 작업도 즉시 취소
        _isRunning.value = false
        _totalRemainingSeconds.value = 0
        _config.value = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopServiceImmediately() {
        stopTimer()
    }

    private fun createSilentForegroundNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return androidx.core.app.NotificationCompat.Builder(this, NotificationHelper.SILENT_SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Focus Flow")
            .setContentText("타이머가 실행 중입니다.")
            .setContentIntent(pi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = androidx.core.app.NotificationCompat.Builder(this, NotificationHelper.SILENT_SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Focus Flow")
            .setContentText(content)
            .setContentIntent(pi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        nm.notify(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (_isRunning.value) {
            stopAllAlarms(true)
        }
        timerJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
