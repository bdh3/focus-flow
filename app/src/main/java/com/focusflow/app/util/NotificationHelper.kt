package com.focusflow.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.ToneGenerator
import androidx.core.app.NotificationCompat
import com.focusflow.app.R
import com.focusflow.app.ui.AlarmActivity
import com.focusflow.app.ui.MainActivity
import kotlinx.coroutines.*

import com.focusflow.app.service.TimerService

enum class BlockType { FOCUS, REST }

class NotificationHelper private constructor(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val audioManager = 
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var ringtonePlayer: android.media.Ringtone? = null
    private var notificationToneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    private var alarmToneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
    
    private var soundJob: Job? = null
    private var isLoopingActive = false
    private var vibrationJob: Job? = null
    private var timeoutJob: Job? = null
    private var alertJob: Job? = null // [v1.7.6-patch] 소리/진동 실행 대기열 관리용

    fun isAlarmRunning(): Boolean = isLoopingActive

    companion object {
        // [v1.7.6-recovery] 채널 중요도 강제 초기화를 위해 ID 변경
        const val ALARM_HIGH_CHANNEL_ID = "focus_flow_alarm_v15_emergency"
        const val SILENT_SERVICE_CHANNEL_ID = "focus_flow_service_v13"
        const val SERVICE_NOTIFICATION_ID = 1000
        const val ALARM_NOTIFICATION_ID = 2000 
        
        const val ALARM_TIMEOUT_MS = 20000L 

        @Volatile
        private var INSTANCE: NotificationHelper? = null

        fun getInstance(context: Context): NotificationHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmChannel = NotificationChannel(
                ALARM_HIGH_CHANNEL_ID,
                "집중 알람 (전체화면 및 팝업)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "구간 전환 및 종료 시 알람을 표시합니다."
                enableLights(true)
                lightColor = android.graphics.Color.RED
                // [v1.7.6-patch] 채널 레벨에서 진동을 활성화해야 FSI 확률이 높아짐
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1) // 시스템이 '진동 있음'으로 인식하게 유도
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setShowBadge(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
            }

            val serviceChannel = NotificationChannel(
                SILENT_SERVICE_CHANNEL_ID,
                "타이머 상태 유지",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "타이머가 백그라운드에서 정확하게 동작하도록 유지합니다."
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(alarmChannel)
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    fun showBlockTransitionNotification(
        taskTitle: String,
        elapsedMinutes: Int,
        isFinished: Boolean,
        currentBlockType: BlockType,
        focusVibrationPattern: LongArray,
        restVibrationPattern: LongArray,
        finishVibrationPattern: LongArray,
        focusSoundId: String,
        restSoundId: String,
        finishSoundId: String,
        vibrationEnabled: Boolean,
        soundEnabled: Boolean = true,
        ringtoneUri: String? = null,
        useFullScreen: Boolean = false,
        isManualSkip: Boolean = false
    ) {
        if (elapsedMinutes == 0 && !isFinished) return

        val elapsedText = when {
            elapsedMinutes <= 0 -> ""
            elapsedMinutes >= 60 -> " (${elapsedMinutes / 60}시간 ${elapsedMinutes % 60}분 경과)"
            else -> " (${elapsedMinutes}분 경과)"
        }
        val displayTitle = taskTitle + elapsedText
        
        val message = when {
            isFinished -> "모든 세션을 완료했습니다. 수고하셨습니다!"
            currentBlockType == BlockType.REST -> "잠시 숨을 고르며 에너지를 충전하세요."
            else -> "흐름을 타고 집중을 시작할 시간입니다."
        }

        val sId = when {
            isFinished -> finishSoundId
            currentBlockType == BlockType.FOCUS -> focusSoundId
            else -> restSoundId
        }
        
        val isRingtone = soundEnabled && sId == "ringtone"
        val isManualFullScreen = useFullScreen && (soundEnabled || vibrationEnabled)
        val forceFullScreen = isRingtone || isManualFullScreen
        
        stopAllAlerts()
        isLoopingActive = forceFullScreen

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("stop_alarm", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmActivityIntent = Intent(context, AlarmActivity::class.java).apply {
            action = "com.focusflow.app.ALARM_ACTION_${System.currentTimeMillis()}" 
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("taskTitle", displayTitle)
            putExtra("message", message)
            putExtra("isFinished", isFinished)
        }

        // [v1.7.6-final] 알림에서 직접 중단할 수 있는 액션 추가 (잠금 화면 대응)
        val stopIntent = Intent(context, TimerService::class.java).apply {
            putExtra("stop_alarm", true)
            putExtra("isFinished", isFinished)
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 2002, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 
            2001,
            alarmActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALARM_HIGH_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayTitle)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "알람 중단", stopPendingIntent) // 액션 버튼 추가
            .setAutoCancel(true)
            .setOngoing(forceFullScreen)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setWhen(System.currentTimeMillis()) 
            .setShowWhen(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(longArrayOf(0))

        // [v1.8.2-fix] 팝업 알림 모드에서도 화면이 꺼져있을 때 안정적으로 알람을 인지할 수 있도록 WakeLock 통합 관리
        // 특히 BroadcastReceiver 종료 후 코루틴 지연 시간(500ms) 동안 CPU가 잠드는 것을 방지
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "FocusFlow:AlarmWakeLock"
            )
            // 소리/진동 코루틴이 시작되고 안정적으로 실행될 수 있도록 10초간 유지
            wakeLock.acquire(10000L) 
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isScreenOn = pm.isInteractive

        if (forceFullScreen) {
            // [v1.8.2-hotfix] 전체 화면 알람(FSI)은 반드시 HIGH 이상의 채널과 MAX 우선순위가 필요함
            // 화면 켜짐 여부와 상관없이 FSI 권한을 확보하기 위해 채널과 우선순위를 ALARM_HIGH/MAX로 고정
            builder.setChannelId(ALARM_HIGH_CHANNEL_ID)
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
            
            if (isScreenOn) {
                // 화면이 이미 켜져 있는 경우, 배너(HUN)와 액티비티가 겹치는 혼란을 줄이기 위해
                // 시스템에 '한 번만 알림'을 요청하고 직접 액티비티를 선제적으로 호출 시도
                builder.setOnlyAlertOnce(true)
                try {
                    context.startActivity(alarmActivityIntent)
                } catch (e: Exception) {
                    // startActivity 실패 시에도 FSI(fullScreenIntent)가 백업으로 작동함
                    e.printStackTrace() 
                }
            }
            
            // 알림 등록 (상태바 유지 및 중단 액션용)
            notificationManager.notify(ALARM_NOTIFICATION_ID, builder.build())
            startTimeoutCounter()
        } else {
            // [v1.8.2-fix] 팝업 모드: 확실한 배너(HUN) 노출을 위해 MAX/ALARM 및 미세 진동 유지
            builder.setFullScreenIntent(null, false)
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
            builder.setDefaults(0)
            builder.setSound(null)
            builder.setVibrate(longArrayOf(0, 50)) 
            
            // 화면이 꺼져 있다면 WakeLock으로 깨워서 배너가 보이게 함
            if (!isScreenOn) {
                try {
                    val wakeLock = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "FocusFlow:PopupWakeLock"
                    )
                    wakeLock.acquire(5000L)
                } catch (e: Exception) { e.printStackTrace() }
            }

            notificationManager.notify(ALARM_NOTIFICATION_ID, builder.build())
        }
        
        alertJob?.cancel()
        alertJob = serviceScope.launch {
            // [v1.8.2-fix] 사운드 이질감 및 즉각성 균형 조정
            // forceFullScreen(화면 꺼짐)일 때 800ms는 너무 길어 답답하므로 400ms로 단축
            // 화면이 이미 켜져 있다면(isScreenOn) 더 빠르게 200ms만 대기
            val startDelay = when {
                !forceFullScreen -> 500L // 팝업 배너용
                isScreenOn -> 200L // 화면 켜짐 + 전체화면 (즉시)
                else -> 400L // 화면 꺼짐 + 전체화면 (전환 대기)
            }
            delay(startDelay)
            
            if (!isLoopingActive && forceFullScreen) return@launch

            if (vibrationEnabled) {
                val pattern = when {
                    isFinished -> finishVibrationPattern
                    currentBlockType == BlockType.FOCUS -> focusVibrationPattern
                    else -> restVibrationPattern
                }
                startVibration(pattern, forceFullScreen)
            }

            if (soundEnabled) {
                val finalSoundId = if (!forceFullScreen && isRingtone) "simple1" else sId
                playSound(finalSoundId, ringtoneUri, isLooping = forceFullScreen, isAlarmType = forceFullScreen)
            }
        }
    }

    private fun startTimeoutCounter() {
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay(ALARM_TIMEOUT_MS)
            if (isLoopingActive) {
                stopAllAlerts()
            }
        }
    }

    private fun startVibration(pattern: LongArray, loop: Boolean) {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        vibrationJob?.cancel()
        val vibrator = getVibrator()
        
        if (loop) {
            vibrationJob = serviceScope.launch {
                repeat(2) {
                    if (!isLoopingActive) return@repeat
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(pattern, -1)
                    }
                    val totalDuration = pattern.sum().coerceAtLeast(500L)
                    delay(totalDuration + 1000)
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    fun playSound(soundId: String, ringtoneUri: String? = null, isLooping: Boolean = false, isAlarmType: Boolean = false) {
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return

        soundJob?.cancel()
        soundJob = serviceScope.launch {
            try {
                val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                        .build()
                } else null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                    audioManager.requestAudioFocus(focusRequest)
                }

                withContext(Dispatchers.IO) {
                    ringtonePlayer?.stop()
                    ringtonePlayer = null
                }

                if (soundId == "ringtone") {
                    val uri = if (!ringtoneUri.isNullOrEmpty()) Uri.parse(ringtoneUri) 
                              else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    
                    ringtonePlayer = RingtoneManager.getRingtone(context, uri).apply {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            this.isLooping = isLooping
                        }
                        play()
                    }
                    return@launch
                }

                val generator = if (isAlarmType) alarmToneGenerator else notificationToneGenerator
                do {
                    playToneEffect(generator, soundId)
                    if (isLooping && isLoopingActive) delay(2000) 
                } while (isLooping && isLoopingActive)
                
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private suspend fun playToneEffect(generator: ToneGenerator, soundId: String) {
        when (soundId) {
            "focus_start", "focus_default" -> {
                generator.startTone(ToneGenerator.TONE_SUP_PIP, 100)
                delay(150)
                generator.startTone(ToneGenerator.TONE_SUP_PIP, 100)
            }
            "rest_start", "rest_default" -> {
                generator.startTone(ToneGenerator.TONE_CDMA_LOW_L, 400)
            }
            "finish_triple" -> {
                repeat(3) {
                    generator.startTone(ToneGenerator.TONE_SUP_PIP, 150)
                    delay(300)
                }
            }
            "warning" -> generator.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500)
            "simple1" -> {
                generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
            }
            "simple2" -> {
                generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
                delay(150)
                generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
            }
            else -> generator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        }
    }

    fun stopAllAlerts() {
        isLoopingActive = false
        vibrationJob?.cancel()
        timeoutJob?.cancel()
        getVibrator().cancel()
        stopSoundOnly()
    }

    private fun stopSoundOnly() {
        try {
            ringtonePlayer?.stop()
            ringtonePlayer = null
            notificationToneGenerator.stopTone()
            alarmToneGenerator.stopTone()
            notificationToneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            alarmToneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        } catch (e: Exception) {}
    }

    fun stopSound() = stopAllAlerts()

    fun vibratePreview(patternId: String) {
        val pattern = VibrationPattern.fromId(patternId).pattern
        startVibration(pattern, false)
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
