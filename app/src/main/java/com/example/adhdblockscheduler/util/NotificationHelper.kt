package com.example.adhdblockscheduler.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.adhdblockscheduler.ui.MainActivity

class NotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val audioManager = 
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        const val ALARM_VIBRATE_CHANNEL_ID = "block_scheduler_vibrate_channel"
        const val ALARM_SILENT_CHANNEL_ID = "block_scheduler_silent_alarm_channel"
        const val SILENT_SERVICE_CHANNEL_ID = "block_scheduler_service_channel"
        const val NOTIFICATION_ID = 1001
        const val FINISHED_NOTIFICATION_ID = 1002
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. 진동 허용 채널: 시스템 설정에 따라 소리가 나며, 진동도 허용됨
            val vibrateChannel = NotificationChannel(
                ALARM_VIBRATE_CHANNEL_ID,
                "몰입 알람 (진동 허용)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // 소리는 기본값(시스템 설정 따름) 유지
            }

            // 2. 진동 차단 채널: 시스템 설정에 따라 소리는 나지만, 진동은 절대 하지 않음
            val silentAlarmChannel = NotificationChannel(
                ALARM_SILENT_CHANNEL_ID,
                "몰입 알람 (진동 차단)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // 소리는 기본값(시스템 설정 따름) 유지
            }

            // 3. 서비스 유지용 채널
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
        vibrationEnabled: Boolean
    ) {
        val title = if (isFinished) "몰입 완료! 🎉" else "몰입 중"
        
        // 집중/휴식 상태 판별 (elapsedMinutes가 interval의 배수인지 등으로 판단 가능하나, 
        // Service에서 넘어오는 메시지가 더 정확하므로 taskTitle에 상태 정보가 포함되어 있는지 확인)
        val message = if (isFinished) 
            "[$taskTitle] 모든 세션을 마쳤습니다." 
        else 
            "[$taskTitle] ${elapsedMinutes}분 경과되었습니다."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "timer")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 앱 내 진동 설정에 따라 채널 선택
        // Vibrate 채널은 폰이 진동/벨소리 모드일 때만 진동함 (시스템이 자동 판단)
        // Silent 채널은 폰이 어떤 모드여도 진동하지 않음
        val channelId = if (vibrationEnabled) ALARM_VIBRATE_CHANNEL_ID else ALARM_SILENT_CHANNEL_ID

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        val id = if (isFinished) FINISHED_NOTIFICATION_ID else (NOTIFICATION_ID + 1)
        notificationManager.notify(id, builder.build())
        
        // 수동 진동 (화면이 켜져 있거나 즉각적인 피드백이 필요할 때)
        // 앱 설정이 ON이고, 휴대폰이 완전 무음 모드가 아닐 때만 실행
        if (vibrationEnabled && audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            if (isFinished) vibrateAlarm() else vibrateDeviceShort()
        }
    }

    private fun vibrateDeviceShort() {
        val vibrator = getVibrator()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }
    }

    private fun vibrateAlarm() {
        val vibrator = getVibrator()
        val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
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
