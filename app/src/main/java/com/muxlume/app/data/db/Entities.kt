package com.muxlume.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceType { M3U_URL, M3U_FILE, XTREAM }

enum class ItemKind { LIVE, MOVIE, SERIES }

/**
 * A playlist or provider account. Everything sensitive (playlist URL, file URI, username,
 * password) is in [secret], sealed with the Keystore key; [label] is only for display.
 */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    val secret: String,
    /** Scheme and host (or file name), never a full URL. */
    val label: String,
    /** The user acknowledged that this source uses unencrypted HTTP. */
    val allowHttp: Boolean,
    /** File extension for Xtream live streams (m3u8 or ts). */
    val liveExtension: String? = null,
    /** Unused since v3: guide links can carry credentials, so they live in the sealed [secret]. */
    val epgUrl: String? = null,
    val createdAt: Long,
    val refreshedAt: Long? = null,
    val lastError: String? = null,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    /** Xtream account expiry, epoch seconds, if the provider reports one. */
    val expiresAt: Long? = null,
    /** The user turned the TV guide on: it is downloaded now and refreshed in the background. */
    @ColumnInfo(defaultValue = "0") val guideEnabled: Boolean = false,
    val epgRefreshedAt: Long? = null,
    val epgError: String? = null,
    @ColumnInfo(defaultValue = "0") val epgPrograms: Int = 0,
)

/**
 * A live channel, movie or series of a source. Rows are replaced on every refresh; anything the
 * user creates (favorites, history) refers to [stableKey] instead of [id] so it survives refreshes.
 *
 * A refresh writes the new rows with [pending] = true in short batches, then swaps them in (see
 * ItemDao.commitPending). Every read filters `pending = 0`, so a running import is invisible.
 */
@Entity(
    tableName = "items",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["sourceId", "stableKey", "pending"], unique = true),
        Index(value = ["sourceId", "kind", "groupTitle"]),
        Index(value = ["kind", "addedAt"]),
    ],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val kind: ItemKind,
    val stableKey: String,
    val name: String,
    val groupTitle: String,
    val logo: String?,
    /** M3U only. Xtream URLs are built at play time from [remoteId] so credentials are not duplicated here. */
    val url: String? = null,
    val remoteId: String? = null,
    val extension: String? = null,
    val tvgId: String? = null,
    val number: Int? = null,
    /** "User-Agent\tvalue\nReferer\tvalue" from the playlist. */
    val headers: String? = null,
    val plot: String? = null,
    val year: String? = null,
    val rating: String? = null,
    /** Provider order. */
    val position: Int,
    /** When the provider added it (epoch seconds), for "Recently added". */
    val addedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val pending: Boolean = false,
)

/** Full-text index over item names (kept in sync with `items` by Room's triggers). */
@Fts4(contentEntity = ItemEntity::class)
@Entity(tableName = "items_fts")
data class ItemFtsEntity(val name: String)

@Entity(
    tableName = "favorites",
    primaryKeys = ["sourceId", "stableKey"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class FavoriteEntity(val sourceId: Long, val stableKey: String, val addedAt: Long)

/** Watch history and resume positions. [mediaId] is the playback id (see playback/MediaIds.kt). */
@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index("updatedAt")],
)
data class HistoryEntity(
    @PrimaryKey val mediaId: String,
    val sourceId: Long,
    val kind: ItemKind,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /** For episodes: the series' stable key, so "Continue watching" can open the show. */
    val parentKey: String? = null,
)
