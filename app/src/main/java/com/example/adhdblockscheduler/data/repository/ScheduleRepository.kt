package com.example.adhdblockscheduler.data.repository

import com.example.adhdblockscheduler.data.local.ScheduleDao
import com.example.adhdblockscheduler.model.ScheduleBlock
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class ScheduleRepository(private val scheduleDao: ScheduleDao) {
    fun getSchedulesForDay(dateInMillis: Long): Flow<List<ScheduleBlock>> {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = dateInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val endOfDay = calendar.timeInMillis
        
        return scheduleDao.getSchedulesForDay(startOfDay, endOfDay)
    }

    suspend fun getScheduleById(id: String): ScheduleBlock? {
        return scheduleDao.getScheduleById(id)
    }

    suspend fun insertSchedule(schedule: ScheduleBlock) {
        scheduleDao.insertSchedule(schedule)
    }

    suspend fun updateSchedule(schedule: ScheduleBlock) {
        scheduleDao.updateSchedule(schedule)
    }

    suspend fun deleteSchedule(schedule: ScheduleBlock) {
        scheduleDao.deleteSchedule(schedule)
    }

    fun getAllSchedules(): Flow<List<ScheduleBlock>> {
        return scheduleDao.getAllSchedules()
    }
}
