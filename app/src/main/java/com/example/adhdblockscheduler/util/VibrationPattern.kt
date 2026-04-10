package com.example.adhdblockscheduler.util

// Vibration pattern definitions
const val SHORT_VIBRATION = 150L
const val LONG_VIBRATION = 400L
const val DELAY_BETWEEN_VIBRATIONS = 100L

sealed class VibrationPattern(val id: String, val displayName: String, val pattern: LongArray) {
    object Default : VibrationPattern("default", "기본", longArrayOf(0, 500, 200, 500))
    object FocusDefault : VibrationPattern("focus_default", "집중", longArrayOf(0, LONG_VIBRATION))
    object RestDefault : VibrationPattern("rest_default", "휴식", longArrayOf(0, SHORT_VIBRATION, DELAY_BETWEEN_VIBRATIONS, SHORT_VIBRATION, DELAY_BETWEEN_VIBRATIONS, LONG_VIBRATION))
    object Simple : VibrationPattern("simple", "심플", longArrayOf(0, SHORT_VIBRATION))
    object Warning : VibrationPattern("warning", "경고", longArrayOf(0, 100, 50, 100, 50, 100))
    object Rhythm : VibrationPattern("rhythm", "리듬", longArrayOf(0, LONG_VIBRATION, 150, SHORT_VIBRATION, 100, SHORT_VIBRATION))
    object Double : VibrationPattern("double", "종료", longArrayOf(0, SHORT_VIBRATION, 150, SHORT_VIBRATION))

    companion object {
        fun fromId(id: String?): VibrationPattern {
            return when (id) {
                FocusDefault.id -> FocusDefault
                RestDefault.id -> RestDefault
                Simple.id -> Simple
                Warning.id -> Warning
                Rhythm.id -> Rhythm
                Double.id -> Double
                else -> Default
            }
        }
        fun getAllPatterns(): List<VibrationPattern> = listOf(
            Default, FocusDefault, RestDefault, Double, Simple, Warning, Rhythm
        )
    }
}
