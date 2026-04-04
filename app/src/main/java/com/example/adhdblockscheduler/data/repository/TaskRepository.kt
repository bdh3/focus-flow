package com.example.adhdblockscheduler.data.repository

import com.example.adhdblockscheduler.data.local.TaskDao
import com.example.adhdblockscheduler.model.Task
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {
    val allTasks: Flow<List<Task>> = taskDao.getAllTasks()

    fun getTasksForDate(dateMillis: Long): Flow<List<Task>> {
        return taskDao.getTasksForDate(dateMillis)
    }

    suspend fun insertTask(task: Task) {
        taskDao.insertTask(task)
    }

    suspend fun updateTask(task: Task) {
        taskDao.updateTask(task)
    }

    suspend fun deleteTask(task: Task) {
        taskDao.deleteTask(task)
    }

    suspend fun updateTaskCompletion(taskId: String, isCompleted: Boolean) {
        taskDao.updateTaskCompletion(taskId, isCompleted)
    }
}
