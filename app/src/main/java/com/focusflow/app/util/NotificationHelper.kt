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
import com.focusflow.app.model.BlockType
import com.focusflow.app.ui.MainActivity
import kotlinx.coroutines.*

class NotificationHelper private constructor(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val audioManager = 
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var ringtonePlayer: android.media.Ringtone? = null
    private var notificationToneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    private var alarmToneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)

    companion object {
        const val ALARM_VIBRATE_CHANNEL_ID = "block_scheduler_vibrate_channel_v3"
        const val ALARM_SILENT_CHANNEL_ID = "block_scheduler_silent_alarm_channel_v3"
        const val SILENT_SERVICE_CHANNEL_ID = "block_scheduler_service_channel_v3"
        const val NOTIFICATION_ID = 1001
        const val FINISHED_NOTIFICATION_ID = 1002

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
            val vibrateChannel = NotificationChannel(
                ALARM_VIBRATE_CHANNEL_ID,
                "몰입 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }

            val silentAlarmChannel = NotificationChannel(
                ALARM_SILENT_CHANNEL_ID,
                "몰입 알림 (무음)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }

            val serviceChannel = NotificationChannel(
                SILENT_SERVICE_CHANNEL_ID,
                "앱 실행 유지",
                NotificationManager.IMPORTANCE_MIN
            )

            notificationManager.createNotificationChannel(vibrateChannel)
            notificationManager.createNotificationChannel(silentAlarmChannel)
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
        ringtoneUri: String? = null
    ) {
        val title = if (isFinished) "몰입 완료! 🎉" else "몰입 중"
        val message = when {
            isFinished -> "[$taskTitle] 모든 세션을 마쳤습니다."
            elapsedMinutes == 0 -> "[$taskTitle] 몰입을 시작합니다."
            currentBlockType == BlockType.REST -> "[$taskTitle] 휴식 시간입니다."
            else -> "[$taskTitle] ${elapsedMinutes}분 경과되었습니다."
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "timer")
            putExtra("stop_alarm", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALARM_SILENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        notificationManager.notify(if (isFinished) FINISHED_NOTIFICATION_ID else NOTIFICATION_ID, builder.build())
        
        if (vibrationEnabled && audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            val pattern = when {
                isFinished -> finishVibrationPattern
                currentBlockType == BlockType.FOCUS -> focusVibrationPattern
                else -> restVibrationPattern
            }
            vibrate(pattern)
        }

        if (soundEnabled && audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            val sId = when {
                isFinished -> finishSoundId
                currentBlockType == BlockType.FOCUS -> focusSoundId
                else -> restSoundId
            }
            
            // 유저 경험 개선: 타이머 '최초 시작(elapsedMinutes == 0)' 시에는 
            // 설정이 벨소리(ringtone)더라도 소리를 내지 않음 (시작 버튼 클릭음으로 충분)
            if (elapsedMinutes == 0 && sId == "ringtone") {
                return
            }

            // 전체 종료이거나 벨소리 모드인 경우 알람 스트림 사용 권장
            val isAlarmType = isFinished || sId == "ringtone"
            playSound(sId, ringtoneUri, isLooping = isFinished, isAlarmType = isAlarmType)
        }
    }

    fun playSound(soundId: String, ringtoneUri: String? = null, isLooping: Boolean = true, isAlarmType: Boolean = false) {
        serviceScope.launch {
            try {
                stopSound() // 기존 소리 무조건 정지

                if (soundId == "ringtone") {
                    val uri = if (!ringtoneUri.isNullOrEmpty()) {
                        Uri.parse(ringtoneUri)
                    } else {
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    }
                    
                    ringtonePlayer = RingtoneManager.getRingtone(context, uri).apply {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(if (isAlarmType) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            this.isLooping = isLooping
                        }
                        play()
                    }
                    return@launch
                }

                val usage = if (isAlarmType) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
                val generator = if (isAlarmType) alarmToneGenerator else notificationToneGenerator

                when (soundId) {
                    "focus_start", "focus_default" -> {
                        // 기기마다 다른 시스템 소리 대신 일관된 비프음 사용
                        generator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                    }
                    "rest_start", "rest_default" -> {
                        generator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    }
                    "finish_triple" -> {
                        repeat(3) {
                            generator.startTone(ToneGenerator.TONE_SUP_PIP, 150)
                            delay(300)
                        }
                    }
                    "warning" -> {
                        generator.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500)
                    }
                    "simple1" -> {
                        generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 100)
                    }
                    "simple2" -> {
                        generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
                        delay(150)
                        generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
                    }
                    else -> {
                        generator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun playSystemSound(type: Int, index: Int, usage: Int, generator: ToneGenerator) {
        try {
            val manager = RingtoneManager(context)
            manager.setType(type)
            val cursor = manager.cursor
            if (cursor != null && cursor.moveToPosition(index)) {
                val uri = manager.getRingtoneUri(index)
                ringtonePlayer = RingtoneManager.getRingtone(context, uri).apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
            } else {
                generator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            }
        } catch (e: Exception) {
            generator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        }
    }

    fun stopSound() {
        try {
            ringtonePlayer?.stop()
            ringtonePlayer = null
            notificationToneGenerator.stopTone()
            alarmToneGenerator.stopTone()
            // ToneGenerator 재생성 (상태 초기화)
            notificationToneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            alarmToneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        } catch (e: Exception) {}
    }

    fun vibratePreview(patternId: String) {
        val pattern = VibrationPattern.fromId(patternId).pattern
        vibrate(pattern)
    }

    fun vibrate(pattern: LongArray) {
        val vibrator = getVibrator()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
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
