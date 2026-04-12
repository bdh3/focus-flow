package com.focusflow.app.model

import com.focusflow.app.util.BlockType
import java.util.UUID

data class TimeBlock(
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long,
    val durationMinutes: Int = 15,
    val type: BlockType,
    val assignedTaskId: String? = null,
    val isCompleted: Boolean = false
)
