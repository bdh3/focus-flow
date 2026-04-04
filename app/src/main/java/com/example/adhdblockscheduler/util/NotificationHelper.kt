package com.example.adhdblockscheduler.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.adhdblockscheduler.R
import com.example.adhdblockscheduler.ui.MainActivity

class NotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ALARM_CHANNEL_ID = "block_scheduler_alarm_channel"
        const val SILENT_CHANNEL_ID = "block_scheduler_silent_channel"
        const val NOTIFICATION_ID = 1001
        const val FINISHED_NOTIFICATION_ID = 1002
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. 알람 채널 (팝업, 소리, 진동 있음)
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "몰입 알람 (팝업/소리)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "주기적인 경과 알림과 완료 알림을 제공합니다."
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 2. 무음 채널 (서비스 유지용, 사용자 방해 금지)
            val silentChannel = NotificationChannel(
                SILENT_CHANNEL_ID,
                "앱 실행 유지 (무음)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "타이머 작동 유지를 위한 필수 알림입니다."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            notificationManager.createNotificationChannel(alarmChannel)
            notificationManager.createNotificationChannel(silentChannel)
        }
    }

    fun showBlockTransitionNotification(
        taskTitle: String,
        elapsedMinutes: Int,
        isFinished: Boolean,
        vibrationEnabled: Boolean
    ) {
        val title = if (isFinished) "몰입 완료! 🎉" else "몰입 중"
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

        // 중요도가 높은 ALARM_CHANNEL 사용
        val builder = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true) // 잠금화면에서도 팝업되도록
            .setAutoCancel(true)

        // 진동 설정 강제 적용
        if (vibrationEnabled) {
            builder.setDefaults(Notification.DEFAULT_ALL)
        } else {
            builder.setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_LIGHTS)
            builder.setVibrate(longArrayOf(0L)) // 시스템 진동 명시적 제거
        }

        val id = if (isFinished) FINISHED_NOTIFICATION_ID else (NOTIFICATION_ID + 1)
        notificationManager.notify(id, builder.build())
        
        // 커스텀 진동 로직도 설정을 따름
        if (vibrationEnabled) {
            if (isFinished) vibrateAlarm() else vibrateDeviceShort()
        }
    }

    fun cancelForegroundNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun vibrateDeviceShort() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }
    }

    private fun vibrateAlarm() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
