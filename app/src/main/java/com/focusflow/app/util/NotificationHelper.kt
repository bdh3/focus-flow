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

    fun isAlarmRunning(): Boolean = isLoopingActive

    companion object {
        const val ALARM_HIGH_CHANNEL_ID = "focus_flow_alarm_high_v9" // [v1.7.3] 시스템 소리/진동 원천 차단을 위한 채널 갱신
        const val SILENT_SERVICE_CHANNEL_ID = "focus_flow_service_v9"
        const val SERVICE_NOTIFICATION_ID = 1000
        
        const val ALARM_TIMEOUT_MS = 20000L // [v1.7.3] 20초 제한

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
                // [v1.7.3] 시스템 중복 소리/진동을 막기 위해 채널 자체를 무음/무진동으로 설정
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
            )

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
        isManualSkip: Boolean = false // [정책 9] 넘기기 여부 추가
    ) {
        // [정책 1] 타이머 시작 시(0분)에는 알람을 울리지 않음
        if (elapsedMinutes == 0 && !isFinished) return

        val elapsedText = when {
            elapsedMinutes <= 0 -> ""
            elapsedMinutes >= 60 -> " (${elapsedMinutes / 60}시간 ${elapsedMinutes % 60}분 경과)"
            else -> " (${elapsedMinutes}분 경과)"
        }
        // [v1.7.3] 타이틀 단순화 (이미 "독립 세션"으로 용어 통일 완료)
        val displayTitle = taskTitle + elapsedText
        
        val sId = when {
            isFinished -> finishSoundId
            currentBlockType == BlockType.FOCUS -> focusSoundId
            else -> restSoundId
        }
        
        // [v1.7.3] 알람 노출 모드 결정 트리 (README_ALARM.md 규칙 1, 2 준수)
        val isRingtone = sId == "ringtone"
        
        // 최종 모드 결정: 벨소리이거나 유저가 '전체 화면'을 선택했다면 무조건 전체 화면.
        // (기존의 넘기기 예외 로직을 제거하여 유저 설정을 최우선으로 존중함)
        val forceFullScreen = isRingtone || useFullScreen 
        
        stopAllAlerts()
        // [v1.7.3] 단일 알림 정책(1000번 통합)에 따라 1001번 취소 로직 제거
        // 기존 1000번(서비스 알림)을 업데이트하는 방식으로 변경됨

        isLoopingActive = forceFullScreen

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("stop_alarm", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALARM_HIGH_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayTitle)
            // [v1.7.3] 전체 화면 모드일 때 상단 팝업(Heads-up)이 뜨는 것을 방지하기 위해 
            // 시스템에 '알림창에만 조용히 표시'하도록 PRIORITY_LOW와 CATEGORY_SERVICE를 조합
            .setPriority(if (forceFullScreen) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setCategory(if (forceFullScreen) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(forceFullScreen && !isFinished)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setWhen(System.currentTimeMillis()) 
            .setShowWhen(true)
            .setGroup("focus_flow_timer_group")
            .setGroupSummary(false)
            // [v1.7.3] 알림 내용이 바뀌어도 소리/진동/팝업이 다시 발생하지 않도록 설정
            .setOnlyAlertOnce(forceFullScreen)

        if (forceFullScreen) {
            val message = when {
                isFinished -> "모든 세션을 완료했습니다. 수고하셨습니다!"
                currentBlockType == BlockType.REST -> "잠시 숨을 고르며 에너지를 충전하세요."
                else -> "흐름을 타고 집중을 시작할 시간입니다."
            }
            builder.setContentText(message)
            // [v1.7.3] 전체화면 시 시스템 자체 소리/진동을 막고 앱에서 직접 제어 (중복 알림음 방지)
            builder.setDefaults(0) 
            builder.setSound(null)
            builder.setVibrate(longArrayOf(0))
            // setOngoing은 위에서 이미 설정됨 (isFinished인 경우 해제 보장)

            val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
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
                context, 2001, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // [v1.7.3] 중복 팝업 방지를 위해 highPriority 플래그를 false로 설정 (manual startActivity가 있으므로 보조 역할)
            builder.setFullScreenIntent(fullScreenPendingIntent, false)
            
            startTimeoutCounter()

            // 직접 실행 시도
            try {
                context.startActivity(fullScreenIntent)
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            // [v1.7.3] 상단 팝업 모드: 시스템 진동/소리를 완전히 차단하고 앱에서 직접 제어
            builder.setSound(null)
            builder.setVibrate(longArrayOf(0)) 
            builder.setDefaults(0)
            builder.setOnlyAlertOnce(false) // [중요] 다음 블록에서도 팝업이 다시 뜨도록 false 처리
            builder.setContentText(null)
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
            builder.setFullScreenIntent(null, false)
        }

        // [v1.7.3] 알림 ID 통일: 서비스 알림(1000)을 업데이트하여 중복 팝업 방지
        notificationManager.notify(SERVICE_NOTIFICATION_ID, builder.build())
        
        serviceScope.launch {
            // [v1.7.3] 액티비티 전환 및 오디오 포커스 안정화를 위해 지연 시간을 0.5초로 조정
            delay(500)
            
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
        // [v1.7.3] 매너 모드 준수
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return
        }

        vibrationJob?.cancel()
        val vibrator = getVibrator()
        
        if (loop) {
            vibrationJob = serviceScope.launch {
                // [v1.7.3 요청] 전체화면 알람이라도 진동은 최대 2회만 수행
                repeat(2) {
                    if (!isLoopingActive) return@repeat
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(pattern, -1)
                    }
                    val totalDuration = pattern.sum().coerceAtLeast(500L)
                    delay(totalDuration + 1000) // 패턴 간 1초 휴식
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
        if (ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        soundJob?.cancel()
        soundJob = serviceScope.launch {
            try {
                // [v1.7.3] 오디오 포커스 강제 요청하여 다른 앱이나 시스템 알림에 의한 끊김 방지
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
                            // [v1.7.3] USAGE_ALARM으로 복구하여 시스템 알람 볼륨을 따르도록 함
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
                // [v1.7.3] 집중: 경쾌하고 높은 톤의 알림
                generator.startTone(ToneGenerator.TONE_SUP_PIP, 100)
                delay(150)
                generator.startTone(ToneGenerator.TONE_SUP_PIP, 100)
            }
            "rest_start", "rest_default" -> {
                // [v1.7.3] 휴식: 차분하고 낮은 톤의 알림
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
                // [v1.7.3] 심플1: 아주 짧은 터치음 스타일
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
