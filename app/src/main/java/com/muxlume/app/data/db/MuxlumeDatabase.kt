package com.muxlume.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("UPDATE sources SET guideEnabled = :enabled WHERE id = :id")
    suspend fun setGuideEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET epgRefreshedAt = :at, epgError = NULL, epgPrograms = :programs WHERE id = :id")
    suspend fun setGuideDone(id: Long, at: Long, programs: Int)

    @Query("UPDATE sources SET epgError = :error WHERE id = :id")
    suspend fun setGuideError(id: Long, error: String?)

    @Query("UPDATE sources SET epgRefreshedAt = NULL, epgError = NULL, epgPrograms = 0 WHERE id = :id")
    suspend fun resetGuide(id: Long)
}

@Dao
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

    @Query("SELECT groupTitle FROM items WHERE sourceId = :sourceId AND kind = :kind AND pending = 0 GROUP BY groupTitle ORDER BY MIN(position)")
    abstract fun groups(sourceId: Long, kind: ItemKind): Flow<List<String>>

    /** One page at a time, so groups with hundreds of thousands of channels stay light. */
    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = :kind AND pending = 0 AND (:group IS NULL OR groupTitle = :group)
           ORDER BY CASE WHEN :alphabetical THEN name END COLLATE NOCASE, position""",
    )
    abstract fun page(sourceId: Long, kind: ItemKind, group: String?, alphabetical: Boolean): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT items.* FROM items JOIN favorites f ON f.sourceId = items.sourceId AND f.stableKey = items.stableKey
           WHERE items.kind = :kind AND items.pending = 0 AND (:sourceId IS NULL OR items.sourceId = :sourceId)
           ORDER BY f.addedAt DESC, items.id""",
    )
    abstract fun favoritesPage(kind: ItemKind, sourceId: Long?): PagingSource<Int, ItemEntity>

    @Query(
        """SELECT items.* FROM items JOIN favorites f ON f.sourceId = items.sourceId AND f.stableKey = items.stableKey
           WHERE items.kind = :kind AND items.pending = 0 AND (:sourceId IS NULL OR items.sourceId = :sourceId)
           ORDER BY f.addedAt DESC, items.id""",
    )
    abstract fun favorites(kind: ItemKind, sourceId: Long?): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE kind = :kind AND pending = 0 AND addedAt IS NOT NULL ORDER BY addedAt DESC LIMIT :limit")
    abstract fun recentlyAdded(kind: ItemKind, limit: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE sourceId = :sourceId AND stableKey = :key AND pending = 0")
    abstract suspend fun byKey(sourceId: Long, key: String): ItemEntity?

    @Query("SELECT * FROM items WHERE sourceId = :sourceId AND stableKey = :key AND pending = 0")
    abstract fun observe(sourceId: Long, key: String): Flow<ItemEntity?>

    /** [match] is an FTS4 MATCH expression; build it with [ftsQuery]. */
    @Query(
        """SELECT items.* FROM items JOIN items_fts ON items.id = items_fts.rowid
           WHERE items_fts MATCH :match AND items.pending = 0 ORDER BY items.kind, items.name COLLATE NOCASE LIMIT :limit""",
    )
    abstract fun search(match: String, limit: Int): Flow<List<ItemEntity>>

    // Neighbours of a channel in the order the list showed it, for channel up/down.

    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0 AND (NOT :hasGroup OR groupTitle = :group)
           AND position > :position ORDER BY position LIMIT 1""",
    )
    abstract suspend fun nextByPosition(sourceId: Long, hasGroup: Boolean, group: String, position: Int): ItemEntity?

    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0 AND (NOT :hasGroup OR groupTitle = :group)
           AND position < :position ORDER BY position DESC LIMIT 1""",
    )
    abstract suspend fun previousByPosition(sourceId: Long, hasGroup: Boolean, group: String, position: Int): ItemEntity?

    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0 AND (NOT :hasGroup OR groupTitle = :group)
           AND (name > :name COLLATE NOCASE OR (name = :name COLLATE NOCASE AND position > :position))
           ORDER BY name COLLATE NOCASE, position LIMIT 1""",
    )
    abstract suspend fun nextByName(sourceId: Long, hasGroup: Boolean, group: String, name: String, position: Int): ItemEntity?

    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = 'LIVE' AND pending = 0 AND (NOT :hasGroup OR groupTitle = :group)
           AND (name < :name COLLATE NOCASE OR (name = :name COLLATE NOCASE AND position < :position))
           ORDER BY name COLLATE NOCASE DESC, position DESC LIMIT 1""",
    )
    abstract suspend fun previousByName(sourceId: Long, hasGroup: Boolean, group: String, name: String, position: Int): ItemEntity?

    @Query(
        """SELECT items.* FROM items JOIN favorites f ON f.sourceId = items.sourceId AND f.stableKey = items.stableKey
           WHERE items.kind = 'LIVE' AND items.pending = 0 AND (:sourceId IS NULL OR items.sourceId = :sourceId)
           AND (f.addedAt < :addedAt OR (f.addedAt = :addedAt AND items.id > :id)) ORDER BY f.addedAt DESC, items.id LIMIT 1""",
    )
    abstract suspend fun nextFavorite(sourceId: Long?, addedAt: Long, id: Long): ItemEntity?

    @Query(
        """SELECT items.* FROM items JOIN favorites f ON f.sourceId = items.sourceId AND f.stableKey = items.stableKey
           WHERE items.kind = 'LIVE' AND items.pending = 0 AND (:sourceId IS NULL OR items.sourceId = :sourceId)
           AND (f.addedAt > :addedAt OR (f.addedAt = :addedAt AND items.id < :id)) ORDER BY f.addedAt, items.id DESC LIMIT 1""",
    )
    abstract suspend fun previousFavorite(sourceId: Long?, addedAt: Long, id: Long): ItemEntity?

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
interface FavoriteDao {
    @Query("SELECT stableKey FROM favorites WHERE sourceId = :sourceId")
    fun keys(sourceId: Long): Flow<List<String>>

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

    @Query("SELECT * FROM history WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE mediaId IN (:mediaIds)")
    fun observe(mediaIds: List<String>): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE kind = 'LIVE' ORDER BY updatedAt DESC LIMIT :limit")
    fun recentChannels(limit: Int): Flow<List<HistoryEntity>>

    /** Started but not finished (5% left counts as finished). */
    @Query("SELECT * FROM history WHERE kind != 'LIVE' AND positionMs > 30000 AND (durationMs <= 0 OR positionMs < durationMs * 0.95) ORDER BY updatedAt DESC LIMIT :limit")
    fun continueWatching(limit: Int): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(
    entities = [
        SourceEntity::class, ItemEntity::class, ItemFtsEntity::class, FavoriteEntity::class, HistoryEntity::class,
        EpgChannelEntity::class, ProgramEntity::class, EpgLinkEntity::class, ReminderEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = MuxlumeDatabase.Migration1To2::class),
        AutoMigration(from = 2, to = 3, spec = MuxlumeDatabase.Migration2To3::class),
    ],
)
abstract class MuxlumeDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun items(): ItemDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun epg(): EpgDao

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
