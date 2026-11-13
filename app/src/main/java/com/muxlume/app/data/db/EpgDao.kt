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

    /**
     * Programmes (on now or later) whose title contains [escapedQuery] (see [likeEscape]), on live channels the user can
     * see. A guide channel shared by several playlist channels yields one row, with the first of them.
     */
    @Query(
        """SELECT p.*, MIN(l.stableKey) AS stableKey, v.name AS channelName FROM programs p
           JOIN epg_links l ON l.sourceId = p.sourceId AND l.channelId = p.channelId
           JOIN visible_items v ON v.sourceId = l.sourceId AND v.stableKey = l.stableKey
           WHERE p.sourceId = :sourceId AND p.pending = 0 AND p.stop > :now AND p.title LIKE '%' || :escapedQuery || '%' ESCAPE '\'
           GROUP BY p.id ORDER BY p.start, p.id LIMIT :limit""",
    )
    abstract suspend fun searchPrograms(sourceId: Long, escapedQuery: String, now: Long, limit: Int): List<ProgramHit>

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

    companion object {
        private const val LINK_BATCH = 500

        /** Escapes LIKE wildcards in user input for queries written with `ESCAPE '\'`. */
        fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
