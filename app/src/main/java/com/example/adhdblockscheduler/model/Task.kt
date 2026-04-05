package com.example.adhdblockscheduler.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val scheduledDateMillis: Long = 0L, // 0 means not specifically scheduled for a date
    val startTimeMillis: Long = 0L // 스케줄 작업의 경우 정렬을 위한 시간 필드 (요구사항 3번)
)
