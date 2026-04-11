package com.focusflow.app.data.local

import androidx.room.*
import com.focusflow.app.model.DailyStats
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Query("SELECT * FROM daily_stats WHERE date = :date")
    suspend fun getStatsForDate(date: String): DailyStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateStats(stats: DailyStats): Long

    @Query("SELECT * FROM daily_stats ORDER BY date DESC LIMIT 7")
    fun getRecentStats(): Flow<List<DailyStats>>
}
