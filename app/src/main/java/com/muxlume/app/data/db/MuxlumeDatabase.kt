package com.muxlume.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
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
}

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("SELECT groupTitle FROM items WHERE sourceId = :sourceId AND kind = :kind GROUP BY groupTitle ORDER BY MIN(position)")
    fun groups(sourceId: Long, kind: ItemKind): Flow<List<String>>

    // shortcut: loads a whole group into memory (fine to ~100k rows); move to Paging if libraries grow beyond that.
    @Query(
        """SELECT * FROM items WHERE sourceId = :sourceId AND kind = :kind AND (:group IS NULL OR groupTitle = :group)
           ORDER BY CASE WHEN :alphabetical THEN name END COLLATE NOCASE, position""",
    )
    fun list(sourceId: Long, kind: ItemKind, group: String?, alphabetical: Boolean): Flow<List<ItemEntity>>

    @Query(
        """SELECT items.* FROM items JOIN favorites f ON f.sourceId = items.sourceId AND f.stableKey = items.stableKey
           WHERE items.kind = :kind AND (:sourceId IS NULL OR items.sourceId = :sourceId) ORDER BY f.addedAt DESC""",
    )
    fun favorites(kind: ItemKind, sourceId: Long?): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE kind = :kind AND addedAt IS NOT NULL ORDER BY addedAt DESC LIMIT :limit")
    fun recentlyAdded(kind: ItemKind, limit: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE sourceId = :sourceId AND stableKey = :key")
    suspend fun byKey(sourceId: Long, key: String): ItemEntity?

    @Query("SELECT * FROM items WHERE sourceId = :sourceId AND stableKey = :key")
    fun observe(sourceId: Long, key: String): Flow<ItemEntity?>

    // shortcut: LIKE scan, no full-text index; fine for typical libraries, add FTS4 if search gets slow.
    @Query("SELECT * FROM items WHERE name LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY kind, name COLLATE NOCASE LIMIT :limit")
    fun search(query: String, limit: Int): Flow<List<ItemEntity>>
}

@Dao
interface FavoriteDao {
    @Query("SELECT stableKey FROM favorites WHERE sourceId = :sourceId")
    fun keys(sourceId: Long): Flow<List<String>>

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
    entities = [SourceEntity::class, ItemEntity::class, FavoriteEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MuxlumeDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun items(): ItemDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao

    companion object {
        fun build(context: Context): MuxlumeDatabase =
            Room.databaseBuilder(context, MuxlumeDatabase::class.java, "muxlume.db").build()
    }
}
