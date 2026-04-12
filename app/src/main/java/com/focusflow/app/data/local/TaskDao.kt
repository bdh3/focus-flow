package com.focusflow.app.data.local

import androidx.room.*
import com.focusflow.app.model.Task
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE (scheduledDateMillis >= :startOfDay AND scheduledDateMillis <= :endOfDay) OR scheduledDateMillis = 0 ORDER BY createdAt DESC")
    fun getTasksForRange(startOfDay: Long, endOfDay: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE scheduledDateMillis = :dateMillis OR scheduledDateMillis = 0 ORDER BY createdAt DESC")
    fun getTasksForDate(dateMillis: Long): Flow<List<Task>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task): Int

    @Delete
    suspend fun deleteTask(task: Task): Int

    @Query("UPDATE tasks SET isCompleted = :isCompleted WHERE id = :taskId")
    suspend fun updateTaskCompletion(taskId: String, isCompleted: Boolean): Int
}
