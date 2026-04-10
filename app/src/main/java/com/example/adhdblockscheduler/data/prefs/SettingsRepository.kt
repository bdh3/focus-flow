package com.example.adhdblockscheduler.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val CALENDAR_SYNC_ENABLED = booleanPreferencesKey("calendar_sync_enabled")
        val BLOCKS_PER_HOUR = intPreferencesKey("blocks_per_hour")
        val REST_MINUTES = intPreferencesKey("rest_minutes")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val ALARM_INTERVAL_MINUTES = intPreferencesKey("alarm_interval_minutes")
        val DEFAULT_TOTAL_MINUTES = intPreferencesKey("default_total_minutes")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val FOCUS_VIBRATION_PATTERN_ID = stringPreferencesKey("focus_vibration_pattern_id")
        val REST_VIBRATION_PATTERN_ID = stringPreferencesKey("rest_vibration_pattern_id")
        val FINISH_VIBRATION_PATTERN_ID = stringPreferencesKey("finish_vibration_pattern_id")
        val FOCUS_SOUND_ID = stringPreferencesKey("focus_sound_id")
        val REST_SOUND_ID = stringPreferencesKey("rest_sound_id")
        val FINISH_SOUND_ID = stringPreferencesKey("finish_sound_id")
    }

    val calendarSyncEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[CALENDAR_SYNC_ENABLED] ?: false
        }

    val vibrationEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[VIBRATION_ENABLED] ?: true
        }

    val blocksPerHour: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[BLOCKS_PER_HOUR] ?: 4 // Default: 4 blocks (15 min each)
        }

    val restMinutes: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[REST_MINUTES] ?: 0 // Default: 0 min rest (Continuous Focus)
        }

    val alarmIntervalMinutes: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[ALARM_INTERVAL_MINUTES] ?: 15 // Default: 15 min interval
        }

    val defaultTotalMinutes: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[DEFAULT_TOTAL_MINUTES] ?: 60 // Default: 60 min
        }

    val soundEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SOUND_ENABLED] ?: true
        }

    val focusVibrationPatternId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[FOCUS_VIBRATION_PATTERN_ID] ?: "focus_default"
        }

    val restVibrationPatternId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[REST_VIBRATION_PATTERN_ID] ?: "rest_default"
        }

    val finishVibrationPatternId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[FINISH_VIBRATION_PATTERN_ID] ?: "double"
        }

    val focusSoundId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[FOCUS_SOUND_ID] ?: "focus_start"
        }

    val restSoundId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[REST_SOUND_ID] ?: "rest_start"
        }

    val finishSoundId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[FINISH_SOUND_ID] ?: "gentle"
        }

    suspend fun setCalendarSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CALENDAR_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setBlocksPerHour(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[BLOCKS_PER_HOUR] = count
        }
    }

    suspend fun setRestMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[REST_MINUTES] = minutes
        }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VIBRATION_ENABLED] = enabled
        }
    }

    suspend fun setAlarmIntervalMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[ALARM_INTERVAL_MINUTES] = minutes
        }
    }

    suspend fun setDefaultTotalMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_TOTAL_MINUTES] = minutes
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SOUND_ENABLED] = enabled
        }
    }

    suspend fun setFocusVibrationPatternId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[FOCUS_VIBRATION_PATTERN_ID] = id
        }
    }

    suspend fun setRestVibrationPatternId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[REST_VIBRATION_PATTERN_ID] = id
        }
    }

    suspend fun setFinishVibrationPatternId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[FINISH_VIBRATION_PATTERN_ID] = id
        }
    }

    suspend fun setFocusSoundId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[FOCUS_SOUND_ID] = id
        }
    }

    suspend fun setRestSoundId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[REST_SOUND_ID] = id
        }
    }

    suspend fun setFinishSoundId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[FINISH_SOUND_ID] = id
        }
    }

}
