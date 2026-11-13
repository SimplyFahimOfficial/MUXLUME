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

    /** A reminder on a channel in a locked group is not returned while locked, so it is never shown. */
    @Query("SELECT * FROM reminders r WHERE r.id = :id AND (:unlocked OR NOT $REMINDER_LOCKED)")
    suspend fun reminder(id: Long, unlocked: Boolean): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId AND stableKey = :stableKey AND startAt = :startAt")
    suspend fun findReminder(sourceId: Long, stableKey: String, startAt: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId AND stableKey = :stableKey AND startAt = :startAt")
    fun reminderFor(sourceId: Long, stableKey: String, startAt: Long): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders r WHERE r.notifyAt > :now AND (:unlocked OR NOT $REMINDER_LOCKED) ORDER BY r.notifyAt")
    fun upcomingReminders(now: Long, unlocked: Boolean): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE notifyAt > :now")
    suspend fun remindersAfter(now: Long): List<ReminderEntity>
}
