package com.focusflow.app.util

data class SoundPattern(
    val id: String,
    val displayName: String
) {
    companion object {
        val Default = SoundPattern("default", "기본")
        val FocusStart = SoundPattern("focus_start", "집중")
        val RestStart = SoundPattern("rest_start", "휴식")
        val Finish = SoundPattern("finish_triple", "종료")
        val Simple1 = SoundPattern("simple1", "심플1")
        val Simple2 = SoundPattern("simple2", "심플2")
        val Warning = SoundPattern("warning", "경고")
        val Ringtone = SoundPattern("ringtone", "벨소리")

        fun getAllPatterns(): List<SoundPattern> {
            return listOf(
                Default, FocusStart, RestStart, Finish, Simple1, Simple2, Warning, Ringtone
            )
        }

        fun fromId(id: String?): SoundPattern {
            return getAllPatterns().find { it.id == id } ?: Default
        }
    }
}
