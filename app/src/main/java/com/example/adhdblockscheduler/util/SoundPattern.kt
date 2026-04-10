package com.example.adhdblockscheduler.util

import android.media.RingtoneManager
import android.net.Uri

data class SoundPattern(
    val id: String,
    val displayName: String,
    val uri: Uri? = null // null means system default
) {
    companion object {
        val Default = SoundPattern("default", "기본")
        val FocusStart = SoundPattern("focus_start", "집중")
        val RestStart = SoundPattern("rest_start", "휴식")
        val SimpleBeep = SoundPattern("simple_beep", "심플")
        val Gentle = SoundPattern("gentle", "종료")
        val Alert = SoundPattern("alert", "경고")

        fun getAllPatterns(): List<SoundPattern> {
            return listOf(Default, FocusStart, RestStart, Gentle, SimpleBeep, Alert)
        }
    }
}
