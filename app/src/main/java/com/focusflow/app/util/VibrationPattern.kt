package com.focusflow.app.util

// Vibration pattern definitions
const val SHORT_VIBRATION = 150L
const val LONG_VIBRATION = 600L
const val DELAY_BETWEEN_VIBRATIONS = 150L

sealed class VibrationPattern(val id: String, val displayName: String, val pattern: LongArray) {
    object Default : VibrationPattern("default", "기본", longArrayOf(0, 500, 200, 500))
    object FocusDefault : VibrationPattern("focus_default", "집중", longArrayOf(0, 400))
    object RestDefault : VibrationPattern("rest_default", "휴식", longArrayOf(0, SHORT_VIBRATION, DELAY_BETWEEN_VIBRATIONS, LONG_VIBRATION))
    object Simple : VibrationPattern("simple", "심플1", longArrayOf(0, SHORT_VIBRATION))
    object ShortDouble : VibrationPattern("short_double", "심플2", longArrayOf(0, SHORT_VIBRATION, 100, SHORT_VIBRATION))
    object Rhythm : VibrationPattern("rhythm", "리듬1", longArrayOf(0, 400, 150, SHORT_VIBRATION, 100, SHORT_VIBRATION))
    object Warning : VibrationPattern("warning", "경고", longArrayOf(0, 100, 50, 100, 50, 100))
    object FinishTriple : VibrationPattern("finish_triple", "종료", longArrayOf(
        0, LONG_VIBRATION, 200, LONG_VIBRATION, 200, LONG_VIBRATION
    ))

    companion object {
        fun fromId(id: String?): VibrationPattern {
            return when (id) {
                FocusDefault.id -> FocusDefault
                RestDefault.id -> RestDefault
                Simple.id -> Simple
                ShortDouble.id -> ShortDouble
                Rhythm.id -> Rhythm
                Warning.id -> Warning
                FinishTriple.id -> FinishTriple
                "double" -> ShortDouble // 하위 호환성 유지
                else -> Default
            }
        }
        fun getAllPatterns(): List<VibrationPattern> = listOf(
            Default, FocusDefault, RestDefault, FinishTriple, ShortDouble, Simple, Warning, Rhythm
        )
    }
}
