package com.muxlume.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import kotlinx.coroutines.flow.Flow

/** Which live channels have a guide (for the guide and the matching screen), and each source's guide state. */
@Dao
@RewriteQueriesToDropUnusedColumns
interface GuideDao {
    /** Live channels that have guide data, pinned first, then in provider order. */
    @Query(
        """SELECT i.* FROM visible_items i JOIN epg_links l ON l.sourceId = i.sourceId AND l.stableKey = i.stableKey
           WHERE i.sourceId = :sourceId AND i.kind = 'LIVE' AND (:group IS NULL OR i.groupTitle = :group) AND (:unlocked OR i.isLocked = 0)
           ORDER BY i.pinned DESC, i.position""",
    )
    fun linkedPage(sourceId: Long, group: String?, unlocked: Boolean): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT * FROM visible_items WHERE sourceId = :sourceId AND kind = 'LIVE' AND (:unlocked OR isLocked = 0)
           AND NOT EXISTS (SELECT 1 FROM epg_links l WHERE l.sourceId = visible_items.sourceId AND l.stableKey = visible_items.stableKey)
           ORDER BY position""",
    )
    fun unmatchedPage(sourceId: Long, unlocked: Boolean): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT i.* FROM visible_items i JOIN epg_links l ON l.sourceId = i.sourceId AND l.stableKey = i.stableKey
           WHERE l.sourceId = :sourceId AND l.manual = 1 AND i.kind = 'LIVE' AND (:unlocked OR i.isLocked = 0) ORDER BY i.position""",
    )
    fun manualPage(sourceId: Long, unlocked: Boolean): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT COUNT(*) FROM visible_items WHERE sourceId = :sourceId AND kind = 'LIVE' AND (:unlocked OR isLocked = 0)
           AND NOT EXISTS (SELECT 1 FROM epg_links l WHERE l.sourceId = visible_items.sourceId AND l.stableKey = visible_items.stableKey)""",
    )
    fun unmatchedCount(sourceId: Long, unlocked: Boolean): Flow<Int>

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
