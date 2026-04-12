package com.focusflow.app.service

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
import com.focusflow.app.util.BlockType
import com.focusflow.app.util.NotificationHelper
import com.focusflow.app.util.VibrationPattern
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
        val useFullScreenAlarm: Boolean = true,
        val focusVibrationPatternId: String = "focus_default",
        val restVibrationPatternId: String = "rest_default",
        val finishVibrationPatternId: String = "finish_triple",
        val focusSoundId: String = "focus_default",
        val restSoundId: String = "rest_default",
        val finishSoundId: String = "finish_triple",
        val focusRingtoneUri: String? = null,
        val restRingtoneUri: String? = null,
        val finishRingtoneUri: String? = null
    )

    private var alarmIntervalMinutes = 15
    private var restMinutes = 0
    private var totalSecondsAtStart = 0
    private var taskTitle = ""
    private var vibrationEnabled = true
    private var soundEnabled = true
    private var useFullScreenAlarm = true
    private var focusVibrationPatternId = "focus_default"
    private var restVibrationPatternId = "rest_default"
    private var finishVibrationPatternId = "finish_triple"
    private var focusSoundId = "focus_default"
    private var restSoundId = "rest_default"
    private var finishSoundId = "finish_triple"
    private var focusRingtoneUri: String? = null
    private var restRingtoneUri: String? = null
    private var finishRingtoneUri: String? = null
    private var onTransition: (String, Int, Boolean, BlockType, Boolean) -> Unit = { _, _, _, _, _ -> }
    private var onFinished: () -> Unit = {}

    private var targetEndTimeMillis: Long = 0
    private val pendingAlarms = mutableListOf<PendingIntent>()
    private var delayedFinishJob: Job? = null // [v1.7.3] 지연 종료 작업 추적용

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    companion object {
        @Volatile
        var isServiceRunning = false
        private const val FINISH_ALARM_ID = 99999
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        _isRunning.value = false
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusFlow::TimerWakeLock")
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("stop_alarm", false) == true) {
            NotificationHelper.getInstance(this).stopAllAlerts()
            // [v1.7.3] 사용자가 직접 알람을 껐고, 이미 타이머가 끝난 상태라면 즉시 정리
            if (!_isRunning.value && delayedFinishJob != null) {
                stopServiceImmediately()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopServiceImmediately() {
        delayedFinishJob?.cancel()
        delayedFinishJob = null
        onFinished()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createSilentForegroundNotification(statusText: String? = null): Notification {
        val displayTitle = taskTitle.ifEmpty { "독립 세션" }
        val displayStatus = statusText ?: "$displayTitle: 몰입 중"
        return NotificationCompat.Builder(this, NotificationHelper.SILENT_SERVICE_CHANNEL_ID)
            .setContentTitle(displayStatus)
            .setContentText("타이머가 실행 중입니다.")
            .setSmallIcon(com.focusflow.app.R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(statusText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        // [v1.7.3] 알람이 울리고 있을 때는 서비스 알림 업데이트를 잠시 멈춤 (ID 1000 통합 충돌 방지)
        if (!NotificationHelper.getInstance(this).isAlarmRunning()) {
            notificationManager.notify(NotificationHelper.SERVICE_NOTIFICATION_ID, createSilentForegroundNotification(statusText))
        }
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
        stopAllAlarms(false)
        timerJob?.cancel()
        _isRunning.value = true
        
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(initialTotalRemaining * 1000L + 60000L)
        }

        targetEndTimeMillis = SystemClock.elapsedRealtime() + (initialTotalRemaining * 1000L)
        _totalRemainingSeconds.value = initialTotalRemaining
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID, 
                createSilentForegroundNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, createSilentForegroundNotification())
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
                // [v1.7.3 리소스 최적화] 화면이 꺼져 있다면 루프를 잠시 멈추고 CPU 휴식
                if (!powerManager.isInteractive) {
                    delay(2000) // 화면 꺼짐 시 체크 주기를 늘림
                    continue
                }

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
            val displayTitle = if (isDefault) "독립 세션" else taskTitle
            
            val statusTitle = when {
                transitioningTo == BlockType.REST -> "$displayTitle: 휴식 시작"
                newBlockIndex >= 2 && restSeconds <= 0 -> "$displayTitle: 몰입"
                else -> "$displayTitle: 몰입 시작"
            }
            
            updateForegroundNotification(if(transitioningTo == BlockType.REST) "$displayTitle: 휴식 중" else "$displayTitle: 몰입 중")
            onTransition(statusTitle, (sessionElapsedSeconds / 60), false, transitioningTo, isManualSkip)
        }
        _remainingSeconds.value = currentBlockRemaining
    }

    private fun handleTimerFinished() {
        isServiceRunning = false
        _totalRemainingSeconds.value = 0
        _remainingSeconds.value = 0
        _isRunning.value = false
        
        // [v1.7.3] 종료 시점에 알람을 미리 끄지 않음 (알람이 울려야 하므로)
        // stopAllAlarms(true) 제거
        
        val displayTitle = taskTitle.ifEmpty { "독립 세션" }
        // 종료 알람 트리거
        onTransition("$displayTitle: 종료", totalSecondsAtStart / 60, true, BlockType.FOCUS, false)
        
        delayedFinishJob = serviceScope.launch {
            // [v1.7.3] 알람이 20초 동안 울릴 수 있도록 서비스 종료를 충분히 지연 (1초 -> 25초)
            // 유저가 알람을 끄면 stopServiceImmediately()에 의해 즉시 종료됨
            delay(25000)
            stopServiceImmediately()
        }
    }

    private fun scheduleAllAlarms(initialRemainingSeconds: Int) {
        stopAllAlarms(false)
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
            val transitioningTo = if (restSeconds <= 0) BlockType.FOCUS
                                  else if ((elapsedAtNextAlarm % cycleSeconds).toInt() >= focusSeconds) BlockType.REST
                                  else BlockType.FOCUS
            
            // [v1.7.3] 벨소리 모드이거나, 유저가 설정에서 '전체화면'을 명시적으로 선택했다면 전체화면 모드 활성화
            val currentSoundId = if (transitioningTo == BlockType.REST) restSoundId else focusSoundId
            val isFullScreenModeForBlock = (currentSoundId == "ringtone") || useFullScreenAlarm
            
            val isDefault = taskTitle.isEmpty()
            val displayTitle = if (isDefault) "독립 세션" else taskTitle
            
            val alarmTitle = when {
                transitioningTo == BlockType.REST -> "$displayTitle: 휴식 시작"
                restSeconds <= 0 -> "$displayTitle: 몰입"
                else -> "$displayTitle: 몰입 시작"
            }

            val intent = Intent(this, TimerAlarmReceiver::class.java).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                // [중요] 액션을 고유하게 만들어 인텐트 시스템이 이전의 팝업 모드 인텐트를 재사용하지 못하게 함
                action = "com.focusflow.app.ALARM_RECV_${elapsedAtNextAlarm}_${System.currentTimeMillis()}"
                putExtra("taskTitle", alarmTitle)
                putExtra("elapsedMinutes", (elapsedAtNextAlarm / 60).toInt())
                putExtra("isFinished", false)
                putExtra("vibrationEnabled", vibrationEnabled)
                putExtra("soundEnabled", soundEnabled)
                putExtra("useFullScreen", isFullScreenModeForBlock) 
                putExtra("blockType", transitioningTo.name)
                putExtra("focusVibrationPatternId", focusVibrationPatternId)
                putExtra("restVibrationPatternId", restVibrationPatternId)
                putExtra("finishVibrationPatternId", finishVibrationPatternId)
                putExtra("focusSoundId", focusSoundId)
                putExtra("restSoundId", restSoundId)
                putExtra("finishSoundId", finishSoundId)
                putExtra("ringtoneUri", if (transitioningTo == BlockType.REST) restRingtoneUri else focusRingtoneUri)
            }
            // RequestCode를 elapsedAtNextAlarm.toInt()로 고유하게 부여
            val pi = PendingIntent.getBroadcast(this, elapsedAtNextAlarm.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextAlarmTime, pi)
            pendingAlarms.add(pi)
        }

        if (initialRemainingSeconds > 0) {
            val isDefault = taskTitle.isEmpty()
            val displayTitle = if (isDefault) "독립 세션" else taskTitle
            
            // [v1.7.3] 규칙 1 & 3: 종료 알람도 벨소리라면 무조건 전체화면, 아니면 유저 설정(useFullScreenAlarm)을 따름
            val isFullScreenForFinish = (finishSoundId == "ringtone") || useFullScreenAlarm
            
            val finishIntent = Intent(this, TimerAlarmReceiver::class.java).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("taskTitle", "$displayTitle: 종료")
                putExtra("elapsedMinutes", totalSecondsAtStart / 60)
                putExtra("isFinished", true)
                putExtra("vibrationEnabled", vibrationEnabled)
                putExtra("soundEnabled", soundEnabled)
                putExtra("useFullScreen", isFullScreenForFinish)
                putExtra("blockType", BlockType.FOCUS.name)
                putExtra("focusVibrationPatternId", focusVibrationPatternId)
                putExtra("restVibrationPatternId", restVibrationPatternId)
                putExtra("finishVibrationPatternId", finishVibrationPatternId)
                putExtra("focusSoundId", focusSoundId)
                putExtra("restSoundId", restSoundId)
                putExtra("finishSoundId", finishSoundId)
                putExtra("ringtoneUri", finishRingtoneUri)
            }
            val finishPI = PendingIntent.getBroadcast(this, FINISH_ALARM_ID, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, currentTime + (initialRemainingSeconds * 1000L), finishPI)
            pendingAlarms.add(finishPI)
        }
    }

    fun pauseTimer() {
        stopAllAlarms(false)
        timerJob?.cancel()
        _isRunning.value = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        val displayTitle = taskTitle.ifEmpty { "독립 세션" }
        updateForegroundNotification("$displayTitle: 일시정지됨")
    }

    fun skipToNext() {
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
            
            // [v1.7.3] 넘기기 시 루프를 먼저 확보하여 상태 전이 중 멈춤 방지
            _isRunning.value = true
            startDisplayUpdateLoop()
            
            updateTimeStates(newRemaining, isManualSkip = true)
            scheduleAllAlarms(newRemaining)
        }
    }

    private fun stopAllAlarms(isFinished: Boolean = false) {
        // 1. 메모리 리스트에 있는 것 취소
        pendingAlarms.forEach { alarmManager.cancel(it) }
        pendingAlarms.clear()
        
        // 2. [확인사살] FINISH_ALARM_ID에 대한 명시적 취소 (유령 알람 방지 핵심)
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
        _isRunning.value = false
        _totalRemainingSeconds.value = 0
        _config.value = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        // [v1.7.3] 자연 종료 시에는 알람을 끄지 않음 (알람이 계속 울려야 하므로)
        // 단, 타이머가 아직 실행 중인데 서비스가 죽는 경우(강제 종료 등)에는 알람을 정리함
        if (_isRunning.value) {
            stopAllAlarms(true)
        }
        timerJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
