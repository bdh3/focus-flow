package com.focusflow.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusflow.app.model.BlockType
import com.focusflow.app.util.NotificationHelper
import com.focusflow.app.util.VibrationPattern

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskTitle = intent.getStringExtra("taskTitle") ?: "작업"
        val elapsedMinutes = intent.getIntExtra("elapsedMinutes", 0)
        val isFinished = intent.getBooleanExtra("isFinished", false)
        val vibrationEnabled = intent.getBooleanExtra("vibrationEnabled", true)
        val soundEnabled = intent.getBooleanExtra("soundEnabled", true)
        
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
            ringtoneUri = ringtoneUri
        )
    }
}
