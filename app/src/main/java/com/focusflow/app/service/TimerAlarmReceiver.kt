package com.focusflow.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusflow.app.util.BlockType
import com.focusflow.app.util.NotificationHelper
import com.focusflow.app.util.VibrationPattern

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // [v1.7.6-fix] 구버전에서 예약된 '좀비 알람' 차단
        // 현재는 고정된 액션 "com.focusflow.app.ALARM_ACTION"만 사용합니다.
        val action = intent.action
        if (action != null && action.startsWith("com.focusflow.app.ALARM_RECV_")) {
            return // 옛날 방식의 알람은 무시
        }
        // [v1.7.3] 서비스가 종료된 상태라면 예약된 알람을 무시 (중지 후 잔여 알람 방지)
        if (!TimerService.isServiceRunning) return

        val taskTitle = intent.getStringExtra("taskTitle") ?: "작업"
        val elapsedMinutes = intent.getIntExtra("elapsedMinutes", 0)
        val isFinished = intent.getBooleanExtra("isFinished", false)
        val vibrationEnabled = intent.getBooleanExtra("vibrationEnabled", true)
        val soundEnabled = intent.getBooleanExtra("soundEnabled", true)
        val useFullScreen = intent.getBooleanExtra("useFullScreen", false)
        
        val blockTypeName = intent.getStringExtra("blockType") ?: BlockType.FOCUS.name
        val currentBlockType = try { BlockType.valueOf(blockTypeName) } catch (e: Exception) { BlockType.FOCUS }
        
        val focusPatternId = intent.getStringExtra("focusVibrationPatternId")
        val restPatternId = intent.getStringExtra("restVibrationPatternId")
        val finishPatternId = intent.getStringExtra("finishVibrationPatternId")
        val focusSoundId = intent.getStringExtra("focusSoundId") ?: "default"
        val restSoundId = intent.getStringExtra("restSoundId") ?: "default"
        val finishSoundId = intent.getStringExtra("finishSoundId") ?: "default"
        val ringtoneUri = intent.getStringExtra("ringtoneUri")
        
        val focusPattern = VibrationPattern.fromId(focusPatternId).pattern
        val restPattern = VibrationPattern.fromId(restPatternId).pattern
        val finishPattern = VibrationPattern.fromId(finishPatternId).pattern

        val notificationHelper = NotificationHelper.getInstance(context)
        notificationHelper.showBlockTransitionNotification(
            taskTitle = taskTitle,
            elapsedMinutes = elapsedMinutes,
            isFinished = isFinished,
            currentBlockType = currentBlockType,
            focusVibrationPattern = focusPattern,
            restVibrationPattern = restPattern,
            finishVibrationPattern = finishPattern,
            focusSoundId = focusSoundId,
            restSoundId = restSoundId,
            finishSoundId = finishSoundId,
            vibrationEnabled = vibrationEnabled,
            soundEnabled = soundEnabled,
            ringtoneUri = ringtoneUri,
            useFullScreen = useFullScreen,
            isManualSkip = false // 알람 리시버를 통한 알람은 수동 넘기기가 아님
        )
    }
}
