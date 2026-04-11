package com.focusflow.app.data.local

import androidx.room.*
import com.focusflow.app.model.ScheduleBlock
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_blocks WHERE startTimeMillis >= :startOfDay AND startTimeMillis < :endOfDay ORDER BY startTimeMillis ASC")
    fun getSchedulesForDay(startOfDay: Long, endOfDay: Long): Flow<List<ScheduleBlock>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleBlock): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleBlock): Int

    @Delete
    suspend fun deleteSchedule(schedule: ScheduleBlock): Int

    @Query("SELECT * FROM schedule_blocks WHERE id = :id")
    suspend fun getScheduleById(id: String): ScheduleBlock?

    @Query("SELECT * FROM schedule_blocks ORDER BY startTimeMillis DESC")
    fun getAllSchedules(): Flow<List<ScheduleBlock>>
}
