package com.muxlume.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId AND stableKey = :stableKey AND startAt = :startAt")
    suspend fun findReminder(sourceId: Long, stableKey: String, startAt: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId AND stableKey = :stableKey AND startAt = :startAt")
    fun reminderFor(sourceId: Long, stableKey: String, startAt: Long): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE notifyAt > :now ORDER BY notifyAt")
    fun upcomingReminders(now: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE notifyAt > :now")
    suspend fun remindersAfter(now: Long): List<ReminderEntity>
}
