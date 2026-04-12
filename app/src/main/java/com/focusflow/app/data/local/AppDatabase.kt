package com.focusflow.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.focusflow.app.model.DailyStats
import com.focusflow.app.model.ScheduleBlock
import com.focusflow.app.model.Task

@Database(entities = [Task::class, DailyStats::class, ScheduleBlock::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun statsDao(): StatsDao
    abstract fun scheduleDao(): ScheduleDao
}
