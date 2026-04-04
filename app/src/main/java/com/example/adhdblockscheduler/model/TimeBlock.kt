package com.example.adhdblockscheduler.model

import java.util.UUID

enum class BlockType {
    FOCUS
}

data class TimeBlock(
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long,
    val durationMinutes: Int = 15,
    val type: BlockType,
    val assignedTaskId: String? = null,
    val isCompleted: Boolean = false
)
