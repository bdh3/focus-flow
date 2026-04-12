package com.focusflow.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.ToneGenerator
import androidx.core.app.NotificationCompat
import com.focusflow.app.model.BlockType
import com.focusflow.app.ui.MainActivity
import kotlinx.coroutines.*

class NotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val audioManager = 
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var ringtonePlayer: android.media.Ringtone? = null
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    companion object {
        const val ALARM_VIBRATE_CHANNEL_ID = "block_scheduler_vibrate_channel_v3"
        const val ALARM_SILENT_CHANNEL_ID = "block_scheduler_silent_alarm_channel_v3"
        const val SILENT_SERVICE_CHANNEL_ID = "block_scheduler_service_channel_v3"
        const val NOTIFICATION_ID = 1001
        const val FINISHED_NOTIFICATION_ID = 1002
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibrateChannel = NotificationChannel(
                ALARM_VIBRATE_CHANNEL_ID,
                "몰입 알림 (커스텀 패턴)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }

            val silentAlarmChannel = NotificationChannel(
                ALARM_SILENT_CHANNEL_ID,
                "몰입 알림 (무음/무진동)",
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
        soundEnabled: Boolean = true
    ) {
        val title = if (isFinished) "몰입 완료! 🎉" else "몰입 중"
        val message = when {
            isFinished -> "[$taskTitle] 모든 세션을 마쳤습니다."
            elapsedMinutes == 0 -> "[$taskTitle] 몰입을 시작합니다. 화이팅!"
            currentBlockType == BlockType.REST -> "[$taskTitle] ${elapsedMinutes}분 경과되었습니다. 휴식 시간입니다."
            else -> "[$taskTitle] ${elapsedMinutes}분 경과되었습니다."
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "timer")
            putExtra("stop_alarm", true) // 알림 터치 시 소리 중지 플래그
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALARM_SILENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(null)
            .setVibrate(null)

        notificationManager.notify(if (isFinished) FINISHED_NOTIFICATION_ID else NOTIFICATION_ID, builder.build())
        
        // 수동 진동/소리 실행
        if (vibrationEnabled && audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            val pattern = when {
                isFinished -> finishVibrationPattern
                currentBlockType == BlockType.FOCUS -> focusVibrationPattern
                else -> restVibrationPattern
            }
            vibrate(pattern)
        }

        if (soundEnabled && audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val sId = when {
                isFinished -> finishSoundId
                currentBlockType == BlockType.FOCUS -> focusSoundId
                else -> restSoundId
            }
            playSound(sId)
        }
    }

    fun playSound(soundId: String) {
        serviceScope.launch {
            try {
                stopSound() 
                when (soundId) {
                    "focus_default", "focus_start" -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                    }
                    "rest_default", "rest_start" -> {
                        toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 300)
                    }
                    "finish_default", "finish_start", "finish_triple" -> {
                        repeat(3) {
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                            delay(250)
                        }
                    }
                    "warning" -> {
                        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 500)
                    }
                    "simple", "simple1" -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 100)
                    }
                    "short_double", "simple2" -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
                        delay(150)
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
                    }
                    "ringtone" -> {
                        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        ringtonePlayer = RingtoneManager.getRingtone(context, uri).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                            play()
                        }
                    }
                    else -> {
                        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ringtonePlayer = RingtoneManager.getRingtone(context, uri)
                        ringtonePlayer?.play()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun stopSound() {
        try {
            ringtonePlayer?.stop()
            ringtonePlayer = null
            toneGenerator.stopTone()
        } catch (e: Exception) {}
    }

    fun vibrate(pattern: LongArray) {
        val vibrator = getVibrator()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
