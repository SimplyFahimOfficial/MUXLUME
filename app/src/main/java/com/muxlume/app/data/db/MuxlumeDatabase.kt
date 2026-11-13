package com.muxlume.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY createdAt")
    fun all(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM sources WHERE allowHttp = 1)")
    fun anyAllowsHttp(): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM sources WHERE allowHttp = 1)")
    suspend fun anyAllowsHttpNow(): Boolean

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastError = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String?)

    @Query("SELECT * FROM sources")
    suspend fun allNow(): List<SourceEntity>
}

/** SQL: the history row `h` is of an item (or a series' episode) in a locked group. Used as `NOT (...)` unless `:unlocked`. */
internal const val HISTORY_LOCKED = """EXISTS (SELECT 1 FROM items li JOIN locked_groups lg ON lg.sourceId = li.sourceId AND lg.groupTitle = li.groupTitle
    WHERE li.sourceId = h.sourceId AND li.pending = 0
    AND (li.stableKey = substr(h.mediaId, instr(h.mediaId, '|') + 1) OR li.stableKey = h.parentKey))"""

/** SQL: the reminder or recording `r` is on a channel in a locked group. */
internal const val CHANNEL_LOCKED = """EXISTS (SELECT 1 FROM items li JOIN locked_groups lg ON lg.sourceId = li.sourceId AND lg.groupTitle = li.groupTitle
    WHERE li.sourceId = r.sourceId AND li.stableKey = r.stableKey AND li.pending = 0)"""

/**
 * Lists read from `visible_items` (renames applied, hidden and staged rows removed); [ItemEntity] has no `pinned`,
 * so Room drops that column from `SELECT *`.
 */
@Dao
@RewriteQueriesToDropUnusedColumns
abstract class ItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE sourceId = :sourceId AND pending = 1")
    abstract suspend fun deletePending(sourceId: Long)

    @Query("DELETE FROM items WHERE sourceId = :sourceId AND pending = 0")
    protected abstract suspend fun deleteLive(sourceId: Long)

    @Query("UPDATE items SET pending = 0 WHERE sourceId = :sourceId AND pending = 1")
    protected abstract suspend fun publishPending(sourceId: Long)

    /** Replaces the source's visible items with the staged (pending) ones, in one short transaction. */
    @Transaction
    open suspend fun commitPending(sourceId: Long) {
        deleteLive(sourceId)
        publishPending(sourceId)
    }

    @Query("SELECT groupTitle FROM visible_items WHERE sourceId = :sourceId AND kind = :kind AND (:unlocked OR isLocked = 0) GROUP BY groupTitle ORDER BY MIN(position)")
    abstract fun groups(sourceId: Long, kind: ItemKind, unlocked: Boolean): Flow<List<String>>

    /** Whether the item [key] (a series' key, for an episode) is in a locked group, whether or not it is shown. */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM items i JOIN locked_groups lg ON lg.sourceId = i.sourceId AND lg.groupTitle = i.groupTitle
           WHERE i.sourceId = :sourceId AND i.stableKey = :key AND i.pending = 0)""",
    )
    abstract suspend fun isLockedKey(sourceId: Long, key: String): Boolean

    /** Every group of a source, locked or not, for choosing which ones the PIN covers. */
    @Query("SELECT groupTitle FROM items WHERE sourceId = :sourceId AND pending = 0 GROUP BY groupTitle ORDER BY MIN(position)")
    abstract fun allGroups(sourceId: Long): Flow<List<String>>

    /** One page at a time, so groups with hundreds of thousands of channels stay light. Pinned channels first. */
    @Query(
        """SELECT * FROM visible_items WHERE sourceId = :sourceId AND kind = :kind AND (:group IS NULL OR groupTitle = :group) AND (:unlocked OR isLocked = 0)
           ORDER BY pinned DESC, CASE WHEN :alphabetical THEN name END COLLATE NOCASE, position""",
    )
    abstract fun page(sourceId: Long, kind: ItemKind, group: String?, alphabetical: Boolean, unlocked: Boolean): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT v.* FROM visible_items v JOIN favorites f ON f.sourceId = v.sourceId AND f.stableKey = v.stableKey
           WHERE v.kind = :kind AND (:sourceId IS NULL OR v.sourceId = :sourceId) AND (:unlocked OR v.isLocked = 0)
           ORDER BY f.addedAt DESC, v.id""",
    )
    abstract fun favoritesPage(kind: ItemKind, sourceId: Long?, unlocked: Boolean): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT v.* FROM visible_items v JOIN favorites f ON f.sourceId = v.sourceId AND f.stableKey = v.stableKey
           WHERE v.kind = :kind AND (:sourceId IS NULL OR v.sourceId = :sourceId) AND (:unlocked OR v.isLocked = 0)
           ORDER BY f.addedAt DESC, v.id""",
    )
    abstract fun favorites(kind: ItemKind, sourceId: Long?, unlocked: Boolean): Flow<List<ItemEntity>>

    @Query("SELECT * FROM visible_items WHERE kind = :kind AND addedAt IS NOT NULL AND (:unlocked OR isLocked = 0) ORDER BY addedAt DESC LIMIT :limit")
    abstract fun recentlyAdded(kind: ItemKind, limit: Int, unlocked: Boolean): Flow<List<ItemEntity>>

    @Query("SELECT * FROM visible_items WHERE sourceId = :sourceId AND stableKey = :key AND (:unlocked OR isLocked = 0)")
    abstract suspend fun byKey(sourceId: Long, key: String, unlocked: Boolean): ItemEntity?

    @Query("SELECT * FROM visible_items WHERE sourceId = :sourceId AND stableKey = :key AND (:unlocked OR isLocked = 0)")
    abstract fun observe(sourceId: Long, key: String, unlocked: Boolean): Flow<ItemEntity?>

    /**
     * [match] is an FTS4 MATCH expression (see [ftsQuery]) over the provider's names. [like] is a LIKE pattern
     * (escaped with `\`) over names the user chose, which the index doesn't know: renamed channels are found by both.
     */
    @Query(
        """SELECT * FROM visible_items
           WHERE (id IN (SELECT rowid FROM items_fts WHERE items_fts MATCH :match)
               OR id IN (SELECT i.id FROM items i JOIN channel_prefs p ON p.sourceId = i.sourceId AND p.stableKey = i.stableKey
                         WHERE p.customName LIKE :like ESCAPE '\'))
           AND (:unlocked OR isLocked = 0)
           ORDER BY kind, name COLLATE NOCASE LIMIT :limit""",
    )
    abstract fun search(match: String, like: String, limit: Int, unlocked: Boolean): Flow<List<ItemEntity>>

    // Neighbours of a channel in the order the list showed it, for channel up/down: pinned first, then
    // provider order or name. [pinned] is the current channel's own flag (see ChannelPrefDao.isPinned).

    @Query(
        """SELECT * FROM visible_items WHERE sourceId = :sourceId AND kind = 'LIVE' AND (:group IS NULL OR groupTitle = :group) AND (:unlocked OR isLocked = 0)
           AND (pinned < :pinned OR (pinned = :pinned AND position > :position)) ORDER BY pinned DESC, position LIMIT 1""",
    )
    abstract suspend fun nextByPosition(sourceId: Long, group: String?, pinned: Boolean, position: Int, unlocked: Boolean): ItemEntity?

    @Query(
        """SELECT * FROM visible_items WHERE sourceId = :sourceId AND kind = 'LIVE' AND (:group IS NULL OR groupTitle = :group) AND (:unlocked OR isLocked = 0)
           AND (pinned > :pinned OR (pinned = :pinned AND position < :position)) ORDER BY pinned, position DESC LIMIT 1""",
    )
    abstract suspend fun previousByPosition(sourceId: Long, group: String?, pinned: Boolean, position: Int, unlocked: Boolean): ItemEntity?

    @Query(
        """SELECT * FROM visible_items WHERE sourceId = :sourceId AND kind = 'LIVE' AND (:group IS NULL OR groupTitle = :group) AND (:unlocked OR isLocked = 0)
           AND (pinned < :pinned OR (pinned = :pinned AND (name > :name COLLATE NOCASE OR (name = :name COLLATE NOCASE AND position > :position))))
           ORDER BY pinned DESC, name COLLATE NOCASE, position LIMIT 1""",
    )
    @Suppress("LongParameterList") // the current channel's sort key, and the lock
    abstract suspend fun nextByName(sourceId: Long, group: String?, pinned: Boolean, name: String, position: Int, unlocked: Boolean): ItemEntity?

    @Query(
        """SELECT * FROM visible_items WHERE sourceId = :sourceId AND kind = 'LIVE' AND (:group IS NULL OR groupTitle = :group) AND (:unlocked OR isLocked = 0)
           AND (pinned > :pinned OR (pinned = :pinned AND (name < :name COLLATE NOCASE OR (name = :name COLLATE NOCASE AND position < :position))))
           ORDER BY pinned, name COLLATE NOCASE DESC, position DESC LIMIT 1""",
    )
    @Suppress("LongParameterList") // the current channel's sort key, and the lock
    abstract suspend fun previousByName(sourceId: Long, group: String?, pinned: Boolean, name: String, position: Int, unlocked: Boolean): ItemEntity?

    @Query(
        """SELECT v.* FROM visible_items v JOIN favorites f ON f.sourceId = v.sourceId AND f.stableKey = v.stableKey
           WHERE v.kind = 'LIVE' AND (:sourceId IS NULL OR v.sourceId = :sourceId) AND (:unlocked OR v.isLocked = 0)
           AND (f.addedAt < :addedAt OR (f.addedAt = :addedAt AND v.id > :id)) ORDER BY f.addedAt DESC, v.id LIMIT 1""",
    )
    abstract suspend fun nextFavorite(sourceId: Long?, addedAt: Long, id: Long, unlocked: Boolean): ItemEntity?

    @Query(
        """SELECT v.* FROM visible_items v JOIN favorites f ON f.sourceId = v.sourceId AND f.stableKey = v.stableKey
           WHERE v.kind = 'LIVE' AND (:sourceId IS NULL OR v.sourceId = :sourceId) AND (:unlocked OR v.isLocked = 0)
           AND (f.addedAt > :addedAt OR (f.addedAt = :addedAt AND v.id < :id)) ORDER BY f.addedAt, v.id DESC LIMIT 1""",
    )
    abstract suspend fun previousFavorite(sourceId: Long?, addedAt: Long, id: Long, unlocked: Boolean): ItemEntity?

    companion object {
        private const val MAX_SEARCH_WORDS = 8

        /**
         * FTS4 MATCH expression for user input: each word must start a word in the name
         * ("bbc on" finds "BBC One"). Quotes keep FTS operators in the input literal.
         */
        fun ftsQuery(input: String): String? {
            val words = input.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(MAX_SEARCH_WORDS)
            return if (words.isEmpty()) null else words.joinToString(" ") { "\"$it*\"" }
        }
    }
}

@Dao
abstract class ChannelPrefDao {
    @Query("SELECT * FROM channel_prefs")
    abstract suspend fun all(): List<ChannelPrefEntity>

    @Query("SELECT * FROM channel_prefs WHERE sourceId = :sourceId AND stableKey = :key")
    abstract fun observe(sourceId: Long, key: String): Flow<ChannelPrefEntity?>

    @Query("SELECT COALESCE((SELECT pinned FROM channel_prefs WHERE sourceId = :sourceId AND stableKey = :key), 0)")
    abstract suspend fun isPinned(sourceId: Long, key: String): Boolean

    @Upsert
    abstract suspend fun upsert(pref: ChannelPrefEntity)

    @Query("SELECT * FROM channel_prefs WHERE sourceId = :sourceId AND stableKey = :key")
    protected abstract suspend fun get(sourceId: Long, key: String): ChannelPrefEntity?

    @Query("DELETE FROM channel_prefs WHERE sourceId = :sourceId AND stableKey = :key")
    protected abstract suspend fun delete(sourceId: Long, key: String)

    /** Changes one channel's preferences; a row left with nothing set is removed. */
    @Transaction
    open suspend fun edit(sourceId: Long, key: String, change: (ChannelPrefEntity) -> ChannelPrefEntity) {
        val pref = change(get(sourceId, key) ?: ChannelPrefEntity(sourceId, key))
        if (pref.isDefault()) delete(sourceId, key) else upsert(pref)
    }

    @Query("UPDATE channel_prefs SET hidden = 0")
    protected abstract suspend fun unhideAll()

    @Query("DELETE FROM channel_prefs WHERE hidden = 0 AND pinned = 0 AND customName IS NULL")
    protected abstract suspend fun deleteDefaults()

    /** Shows every hidden channel again; renames and pins stay. */
    @Transaction
    open suspend fun restoreAll() {
        unhideAll()
        deleteDefaults()
    }

    /** Hidden channels that still exist in their playlist, by name. */
    @Query(
        """SELECT i.* FROM items i JOIN channel_prefs p ON p.sourceId = i.sourceId AND p.stableKey = i.stableKey
           WHERE p.hidden = 1 AND i.pending = 0
           AND (:unlocked OR NOT EXISTS (SELECT 1 FROM locked_groups lg WHERE lg.sourceId = i.sourceId AND lg.groupTitle = i.groupTitle))
           ORDER BY i.name COLLATE NOCASE, i.id""",
    )
    abstract fun hiddenPage(unlocked: Boolean): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT COUNT(*) FROM channel_prefs p JOIN items i ON p.sourceId = i.sourceId AND p.stableKey = i.stableKey
           WHERE p.hidden = 1 AND i.pending = 0
           AND (:unlocked OR NOT EXISTS (SELECT 1 FROM locked_groups lg WHERE lg.sourceId = i.sourceId AND lg.groupTitle = i.groupTitle))""",
    )
    abstract fun hiddenCount(unlocked: Boolean): Flow<Int>
}

@Dao
abstract class RecordingDao {
    /** -1 when the programme is already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(recording: RecordingEntity): Long

    @Query("SELECT * FROM recordings WHERE id = :id")
    abstract suspend fun get(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE sourceId = :sourceId AND stableKey = :stableKey AND startAt = :startAt")
    abstract suspend fun find(sourceId: Long, stableKey: String, startAt: Long): RecordingEntity?

    @Query("SELECT * FROM recordings r WHERE (:unlocked OR NOT $CHANNEL_LOCKED) ORDER BY r.startAt DESC")
    abstract fun all(unlocked: Boolean): Flow<List<RecordingEntity>>

    /** A recording with a file, for playback ("rec|<id>"). */
    @Query("SELECT * FROM recordings r WHERE r.id = :id AND r.fileUri IS NOT NULL AND r.bytes > 0 AND (:unlocked OR NOT $CHANNEL_LOCKED)")
    abstract suspend fun playable(id: Long, unlocked: Boolean): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE state = 'SCHEDULED' AND startAt > :now")
    abstract suspend fun scheduledAfter(now: Long): List<RecordingEntity>

    @Query("UPDATE recordings SET state = 'FAILED', error = 'MISSED' WHERE state = 'SCHEDULED' AND endAt <= :now")
    abstract suspend fun markMissed(now: Long)

    /** Rows left "recording" by a process that died. */
    @Query("UPDATE recordings SET state = 'FAILED', error = 'INTERRUPTED' WHERE state = 'RECORDING'")
    abstract suspend fun failRunning()

    @Query("UPDATE recordings SET state = 'RECORDING', fileUri = :fileUri, error = NULL WHERE id = :id")
    abstract suspend fun start(id: Long, fileUri: String)

    @Query("UPDATE recordings SET bytes = :bytes WHERE id = :id")
    abstract suspend fun setBytes(id: Long, bytes: Long)

    @Query("UPDATE recordings SET state = :state, bytes = :bytes, error = :error WHERE id = :id")
    abstract suspend fun finish(id: Long, state: RecordingState, bytes: Long, error: String?)

    @Query("DELETE FROM recordings WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM recordings")
    abstract fun totalBytes(): Flow<Long>
}

@Dao
interface LockedGroupDao {
    @Query("SELECT groupTitle FROM locked_groups WHERE sourceId = :sourceId")
    fun titles(sourceId: Long): Flow<List<String>>

    @Query("SELECT * FROM locked_groups")
    suspend fun all(): List<LockedGroupEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(group: LockedGroupEntity)

    @Query("DELETE FROM locked_groups WHERE sourceId = :sourceId AND groupTitle = :groupTitle")
    suspend fun remove(sourceId: Long, groupTitle: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT stableKey FROM favorites WHERE sourceId = :sourceId")
    fun keys(sourceId: Long): Flow<List<String>>

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE sourceId = :sourceId AND stableKey = :key")
    suspend fun get(sourceId: Long, key: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE sourceId = :sourceId AND stableKey = :key)")
    fun isFavorite(sourceId: Long, key: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId AND stableKey = :key")
    suspend fun remove(sourceId: Long, key: String)
}

@Dao
interface HistoryDao {
    @Upsert
    suspend fun upsert(entry: HistoryEntity)

    @Query("SELECT * FROM history")
    suspend fun all(): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE mediaId IN (:mediaIds)")
    fun observe(mediaIds: List<String>): Flow<List<HistoryEntity>>

    /** Hidden channels are left out; the history row itself stays, so unhiding brings it back. */
    @Query(
        """SELECT * FROM history h WHERE h.kind = 'LIVE'
           AND NOT EXISTS (SELECT 1 FROM channel_prefs p WHERE p.hidden = 1 AND p.sourceId = h.sourceId AND h.mediaId = p.sourceId || '|' || p.stableKey)
           AND (:unlocked OR NOT $HISTORY_LOCKED)
           ORDER BY h.updatedAt DESC LIMIT :limit""",
    )
    fun recentChannels(limit: Int, unlocked: Boolean): Flow<List<HistoryEntity>>

    /** Started but not finished (5% left counts as finished). */
    @Query(
        """SELECT * FROM history h WHERE h.kind != 'LIVE' AND h.positionMs > 30000 AND (h.durationMs <= 0 OR h.positionMs < h.durationMs * 0.95)
           AND (:unlocked OR NOT $HISTORY_LOCKED)
           ORDER BY h.updatedAt DESC LIMIT :limit""",
    )
    fun continueWatching(limit: Int, unlocked: Boolean): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(
    entities = [
        SourceEntity::class, ItemEntity::class, ItemFtsEntity::class, FavoriteEntity::class, HistoryEntity::class,
        EpgChannelEntity::class, ProgramEntity::class, EpgLinkEntity::class, ReminderEntity::class, ChannelPrefEntity::class,
        LockedGroupEntity::class, RecordingEntity::class,
    ],
    views = [VisibleItem::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = MuxlumeDatabase.Migration1To2::class),
        AutoMigration(from = 2, to = 3, spec = MuxlumeDatabase.Migration2To3::class),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class MuxlumeDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun items(): ItemDao
    abstract fun favorites(): FavoriteDao
    abstract fun channelPrefs(): ChannelPrefDao
    abstract fun lockedGroups(): LockedGroupDao
    abstract fun recordings(): RecordingDao
    abstract fun history(): HistoryDao
    abstract fun epg(): EpgDao

    abstract fun guide(): GuideDao

    abstract fun reminders(): ReminderDao

    /** v2: staged imports (`items.pending`) and the full-text index, filled from existing rows. */
    class Migration1To2 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
        }
    }

    /** v3: guide tables. Guide links used to be stored in plain text in `sources.epgUrl`; they can carry credentials, so drop them. */
    class Migration2To3 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE sources SET epgUrl = NULL")
        }
    }

    companion object {
        fun build(context: Context): MuxlumeDatabase =
            Room.databaseBuilder(context, MuxlumeDatabase::class.java, "muxlume.db").build()
    }
}
