package com.muxlume.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class EpgDao {
    // ---- Staging and swap

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPrograms(programs: List<ProgramEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLinks(links: List<EpgLinkEntity>)

    @Query("DELETE FROM programs WHERE sourceId = :sourceId AND pending = 1")
    protected abstract suspend fun deletePendingPrograms(sourceId: Long)

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId AND pending = 1")
    protected abstract suspend fun deletePendingChannels(sourceId: Long)

    @Query("DELETE FROM programs WHERE sourceId = :sourceId AND pending = 0")
    protected abstract suspend fun deleteLivePrograms(sourceId: Long)

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId AND pending = 0")
    protected abstract suspend fun deleteLiveChannels(sourceId: Long)

    @Query("UPDATE programs SET pending = 0 WHERE sourceId = :sourceId AND pending = 1")
    protected abstract suspend fun publishPrograms(sourceId: Long)

    @Query("UPDATE epg_channels SET pending = 0 WHERE sourceId = :sourceId AND pending = 1")
    protected abstract suspend fun publishChannels(sourceId: Long)

    @Query("DELETE FROM epg_links WHERE sourceId = :sourceId AND manual = 0")
    protected abstract suspend fun deleteAutoLinks(sourceId: Long)

    @Query("DELETE FROM epg_links WHERE sourceId = :sourceId")
    protected abstract suspend fun deleteAllLinks(sourceId: Long)

    /** Drops rows of an import that didn't finish. */
    @Transaction
    open suspend fun discardPending(sourceId: Long) {
        deletePendingPrograms(sourceId)
        deletePendingChannels(sourceId)
    }

    /** Replaces the source's guide with the staged one and its automatic links, in one short transaction. */
    @Transaction
    open suspend fun commit(sourceId: Long, links: List<EpgLinkEntity>) {
        deleteLivePrograms(sourceId)
        deleteLiveChannels(sourceId)
        publishPrograms(sourceId)
        publishChannels(sourceId)
        replaceAutoLinks(sourceId, links)
    }

    @Transaction
    open suspend fun replaceAutoLinks(sourceId: Long, links: List<EpgLinkEntity>) {
        deleteAutoLinks(sourceId)
        links.chunked(LINK_BATCH).forEach { insertLinks(it) } // IGNORE keeps manual links in place
    }

    /** Removes everything guide-related of a source (the user turned the guide off). */
    @Transaction
    open suspend fun clear(sourceId: Long) {
        discardPending(sourceId)
        deleteLivePrograms(sourceId)
        deleteLiveChannels(sourceId)
        deleteAllLinks(sourceId)
    }

    // ---- Matching

    @Query("SELECT * FROM epg_channels WHERE sourceId = :sourceId AND pending = 0")
    abstract suspend fun channels(sourceId: Long): List<EpgChannelEntity>

    @Query("SELECT id, stableKey, name, tvgId FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0 AND id > :afterId ORDER BY id LIMIT :limit")
    abstract suspend fun liveBatch(sourceId: Long, afterId: Long, limit: Int): List<MatchRow>

    @Query("INSERT OR REPLACE INTO epg_links (sourceId, stableKey, channelId, manual) VALUES (:sourceId, :stableKey, :channelId, 1)")
    abstract suspend fun setManualLink(sourceId: Long, stableKey: String, channelId: String)

    @Query("DELETE FROM epg_links WHERE sourceId = :sourceId AND stableKey = :stableKey")
    abstract suspend fun removeLink(sourceId: Long, stableKey: String)

    @Query("SELECT * FROM epg_links WHERE sourceId = :sourceId AND stableKey = :stableKey")
    abstract suspend fun link(sourceId: Long, stableKey: String): EpgLinkEntity?

    /** Guide channels whose name contains [escapedQuery] (see [likeEscape]), one row per channel. */
    @Query(
        """SELECT sourceId, channelId, MIN(name) AS name, 0 AS pending FROM epg_channels
           WHERE sourceId = :sourceId AND pending = 0 AND name LIKE '%' || :escapedQuery || '%' ESCAPE '\'
           GROUP BY channelId ORDER BY name COLLATE NOCASE LIMIT :limit""",
    )
    abstract suspend fun findChannels(sourceId: Long, escapedQuery: String, limit: Int): List<EpgChannelEntity>

    // ---- Reading the guide

    /** The programmes of a live channel overlapping [from, to), through its guide link. */
    @Query(
        """SELECT p.* FROM programs p JOIN epg_links l ON l.sourceId = p.sourceId AND l.channelId = p.channelId
           WHERE l.sourceId = :sourceId AND l.stableKey = :stableKey AND p.pending = 0 AND p.stop > :from AND p.start < :to
           ORDER BY p.start""",
    )
    abstract suspend fun programsFor(sourceId: Long, stableKey: String, from: Long, to: Long): List<ProgramEntity>

    /** The programme on now (if any) and the next ones, at most [limit]. */
    @Query(
        """SELECT p.* FROM programs p JOIN epg_links l ON l.sourceId = p.sourceId AND l.channelId = p.channelId
           WHERE l.sourceId = :sourceId AND l.stableKey = :stableKey AND p.pending = 0 AND p.stop > :now
           ORDER BY p.start LIMIT :limit""",
    )
    abstract suspend fun upcoming(sourceId: Long, stableKey: String, now: Long, limit: Int): List<ProgramEntity>

    /** Live channels that have guide data, in provider order. */
    @Query(
        """SELECT i.* FROM items i JOIN epg_links l ON l.sourceId = i.sourceId AND l.stableKey = i.stableKey
           WHERE i.sourceId = :sourceId AND i.kind = 'LIVE' AND i.pending = 0 AND (:group IS NULL OR i.groupTitle = :group)
           ORDER BY i.position""",
    )
    abstract fun linkedPage(sourceId: Long, group: String?): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0
           AND NOT EXISTS (SELECT 1 FROM epg_links l WHERE l.sourceId = items.sourceId AND l.stableKey = items.stableKey)
           ORDER BY position""",
    )
    abstract fun unmatchedPage(sourceId: Long): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT i.* FROM items i JOIN epg_links l ON l.sourceId = i.sourceId AND l.stableKey = i.stableKey
           WHERE l.sourceId = :sourceId AND l.manual = 1 AND i.kind = 'LIVE' AND i.pending = 0 ORDER BY i.position""",
    )
    abstract fun manualPage(sourceId: Long): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0
           AND NOT EXISTS (SELECT 1 FROM epg_links l WHERE l.sourceId = items.sourceId AND l.stableKey = items.stableKey)""",
    )
    abstract fun unmatchedCount(sourceId: Long): Flow<Int>

    @Query("SELECT channelId FROM epg_links WHERE sourceId = :sourceId AND stableKey = :stableKey AND manual = 1")
    abstract fun manualChannel(sourceId: Long, stableKey: String): Flow<String?>

    // ---- Reminders

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertReminder(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    abstract suspend fun deleteReminder(id: Long)

    @Query("SELECT * FROM reminders WHERE id = :id")
    abstract suspend fun reminder(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId AND stableKey = :stableKey AND startAt = :startAt")
    abstract suspend fun findReminder(sourceId: Long, stableKey: String, startAt: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId AND stableKey = :stableKey AND startAt = :startAt")
    abstract fun reminderFor(sourceId: Long, stableKey: String, startAt: Long): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE notifyAt > :now ORDER BY notifyAt")
    abstract fun upcomingReminders(now: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE notifyAt > :now")
    abstract suspend fun remindersAfter(now: Long): List<ReminderEntity>

    companion object {
        private const val LINK_BATCH = 500

        /** Escapes LIKE wildcards in user input for queries written with `ESCAPE '\'`. */
        fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
