package com.muxlume.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Which live channels have a guide (for the guide and the matching screen), and each source's guide state. */
@Dao
interface GuideDao {
    /** Live channels that have guide data, in provider order. */
    @Query(
        """SELECT i.* FROM items i JOIN epg_links l ON l.sourceId = i.sourceId AND l.stableKey = i.stableKey
           WHERE i.sourceId = :sourceId AND i.kind = 'LIVE' AND i.pending = 0 AND (:group IS NULL OR i.groupTitle = :group)
           ORDER BY i.position""",
    )
    fun linkedPage(sourceId: Long, group: String?): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0
           AND NOT EXISTS (SELECT 1 FROM epg_links l WHERE l.sourceId = items.sourceId AND l.stableKey = items.stableKey)
           ORDER BY position""",
    )
    fun unmatchedPage(sourceId: Long): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT i.* FROM items i JOIN epg_links l ON l.sourceId = i.sourceId AND l.stableKey = i.stableKey
           WHERE l.sourceId = :sourceId AND l.manual = 1 AND i.kind = 'LIVE' AND i.pending = 0 ORDER BY i.position""",
    )
    fun manualPage(sourceId: Long): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0
           AND NOT EXISTS (SELECT 1 FROM epg_links l WHERE l.sourceId = items.sourceId AND l.stableKey = items.stableKey)""",
    )
    fun unmatchedCount(sourceId: Long): Flow<Int>

    @Query("SELECT channelId FROM epg_links WHERE sourceId = :sourceId AND stableKey = :stableKey AND manual = 1")
    fun manualChannel(sourceId: Long, stableKey: String): Flow<String?>

    // ---- Per-source guide state (columns of `sources`)

    @Query("UPDATE sources SET guideEnabled = :enabled WHERE id = :id")
    suspend fun setGuideEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET epgRefreshedAt = :at, epgError = NULL, epgPrograms = :programs WHERE id = :id")
    suspend fun setGuideDone(id: Long, at: Long, programs: Int)

    @Query("UPDATE sources SET epgError = :error WHERE id = :id")
    suspend fun setGuideError(id: Long, error: String?)

    @Query("UPDATE sources SET epgRefreshedAt = NULL, epgError = NULL, epgPrograms = 0 WHERE id = :id")
    suspend fun resetGuide(id: Long)
}
