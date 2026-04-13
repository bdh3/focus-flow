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
        // [v1.7.3] 최종 채널 ID 고정 (안정화 완료)
        const val ALARM_HIGH_CHANNEL_ID = "focus_flow_alarm_v13"
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
                "몰입 알람 (전체화면 및 팝업)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "구간 전환 및 종료 시 알람을 표시합니다."
                enableLights(true)
                lightColor = android.graphics.Color.RED
                // 중복 소리 방지를 위해 시스템 소리는 다시 null로 설정
                setSound(null, null)
                enableVibration(false)
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
        
        val isRingtone = sId == "ringtone"
        val forceFullScreen = isRingtone || useFullScreen 
        
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
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("taskTitle", displayTitle)
            putExtra("message", message)
            putExtra("isFinished", isFinished)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 2001, alarmActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALARM_HIGH_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayTitle)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // [v1.7.5-fix] 종료 알림(isFinished)인 경우 ongoing을 해제하여 사용자가 밀어서 지울 수 있게 함
            .setOngoing(forceFullScreen && !isFinished)
            // 잠금 화면에서도 내용을 "항상 표시"하도록 강제
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setWhen(System.currentTimeMillis()) 
            .setShowWhen(true)
            // [v1.7.6-patch] 최신 안드로이드(Z플립5 등) 대응: 긴급도 최대 설정 및 알람 카테고리 명시
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // [v1.7.6-patch] 최신 안드로이드(Z플립5 등) 대응: 긴급도 최대 설정 및 알람 카테고리 명시
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // [v1.7.6-patch] 최신 안드로이드(Z플립5 등) 대응: 긴급도 최대 설정 및 알람 카테고리 명시
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        if (forceFullScreen) {
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            startTimeoutCounter()
            
            // [v1.7.6-patch] Z플립5 등 삼성 기기 대응: PowerManager를 사용하여 화면을 강제로 깨웁니다.
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val wakeLock = pm.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "FocusFlow:AlarmWakeLock"
                )
                // 화면을 15초간 강제로 켭니다.
                wakeLock.acquire(15000L) 

                // [핵심] PendingIntent에만 의존하지 않고 액티비티를 직접 즉시 실행
                context.startActivity(alarmActivityIntent)
            } catch (e: Exception) { 
                e.printStackTrace() 
            }
        } else {
            builder.setDefaults(0)
            builder.setSound(null)
            builder.setVibrate(longArrayOf(0))
        }

        notificationManager.notify(ALARM_NOTIFICATION_ID, builder.build())
        
        alertJob?.cancel() // 기존에 대기 중인 작업이 있다면 취소
        alertJob = serviceScope.launch {
            delay(500)
            
            // [v1.7.6-patch] 작업을 시작하기 전에 세션이 이미 중단되었는지 재확인
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
