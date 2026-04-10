package com.example.adhdblockscheduler.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.adhdblockscheduler.model.BlockType
import com.example.adhdblockscheduler.ui.MainActivity
import com.example.adhdblockscheduler.util.VibrationPattern
import com.example.adhdblockscheduler.util.SoundPattern

class NotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val audioManager = 
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
            // 알람 채널들을 시스템 레벨에서 무음/무진동으로 설정합니다.
            // 소리와 진동은 앱에서 설정된 패턴에 따라 수동으로 제어합니다.
            
            val vibrateChannel = NotificationChannel(
                ALARM_VIBRATE_CHANNEL_ID,
                "몰입 알림 (커스텀 패턴)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "앱에서 설정한 커스텀 소리와 진동으로 알림을 보냅니다."
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val silentAlarmChannel = NotificationChannel(
                ALARM_SILENT_CHANNEL_ID,
                "몰입 알림 (무음/무진동)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "화면 알림만 표시합니다."
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 서비스 유지용 채널 (최소 중요도)
            val serviceChannel = NotificationChannel(
                SILENT_SERVICE_CHANNEL_ID,
                "앱 실행 유지",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }

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
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 알림 채널은 소리가 없는 채널을 사용하고, 소리는 수동으로 재생하여 패턴 선택을 반영합니다.
        val channelId = ALARM_SILENT_CHANNEL_ID

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null)      // 시스템 기본 소리 제거
            .setVibrate(null)    // 시스템 기본 진동 제거
            .setDefaults(0)      // 모든 기본 설정 초기화

        val id = if (isFinished) FINISHED_NOTIFICATION_ID else (NOTIFICATION_ID + 1)
        notificationManager.notify(id, builder.build())
        
        // 수동 진동
        if (vibrationEnabled && audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            val patternToVibrate = when {
                isFinished -> finishVibrationPattern
                currentBlockType == BlockType.FOCUS -> focusVibrationPattern
                currentBlockType == BlockType.REST -> restVibrationPattern
                else -> VibrationPattern.Default.pattern
            }
            vibrate(patternToVibrate)
        }

        // 수동 소리 재생
        if (soundEnabled && audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val soundIdToPlay = when {
                isFinished -> finishSoundId
                currentBlockType == BlockType.FOCUS -> focusSoundId
                currentBlockType == BlockType.REST -> restSoundId
                else -> "default"
            }
            playSound(soundIdToPlay)
        }
    }

    private var currentRingtone: android.media.Ringtone? = null
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)

    fun playSound(soundId: String) {
        try {
            // 이전 소리가 재생 중이면 중지
            currentRingtone?.stop()

            if (soundId == "simple_beep") {
                // '심플'은 시스템 비프음으로 아주 짧게 재생
                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200)
                return
            }

            val uri: Uri = when (soundId) {
                "focus_start" -> getSystemNotificationUri(0) // 첫 번째 알림음
                "rest_start" -> getSystemNotificationUri(1)  // 두 번째 알림음
                "gentle" -> getSystemNotificationUri(2)      // 세 번째 알림음
                "alert" -> getSystemNotificationUri(3)       // 네 번째 알림음
                else -> {
                    if (soundId.startsWith("content://")) Uri.parse(soundId)
                    else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
            }
            
            val ringtone = RingtoneManager.getRingtone(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = false
            }
            currentRingtone = ringtone
            ringtone.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSystemNotificationUri(index: Int): Uri {
        val manager = RingtoneManager(context)
        manager.setType(RingtoneManager.TYPE_NOTIFICATION)
        val cursor = manager.cursor
        return if (cursor != null && cursor.moveToPosition(index)) {
            manager.getRingtoneUri(index)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    fun stopSound() {
        currentRingtone?.stop()
        currentRingtone = null
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

    fun vibratePreview(pattern: LongArray) {
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
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
