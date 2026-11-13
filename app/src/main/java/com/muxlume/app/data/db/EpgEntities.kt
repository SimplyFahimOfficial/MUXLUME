package com.muxlume.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Guide (XMLTV) data, per source. Like items, a refresh writes its rows with [pending] = true and
 * swaps them in at the end (EpgDao.commit), so the guide stays usable while it downloads.
 */

/** One display name of a guide channel (a channel can have several). */
@Entity(
    tableName = "epg_channels",
    primaryKeys = ["sourceId", "channelId", "name", "pending"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class EpgChannelEntity(
    val sourceId: Long,
    val channelId: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val pending: Boolean = false,
)

/** A programme. Times are epoch seconds (absolute instants; shown in the device's time zone). */
@Entity(
    tableName = "programs",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    // Also serves the per-channel time-range queries; the unique key drops duplicate programmes.
    indices = [Index(value = ["sourceId", "channelId", "start", "pending"], unique = true)],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    @ColumnInfo(defaultValue = "0") val pending: Boolean = false,
)

/**
 * Which guide channel a live channel uses. Automatic links (tvg-id, then name) are recomputed after
 * every guide or playlist refresh; links the user chose ([manual]) are kept.
 */
@Entity(
    tableName = "epg_links",
    primaryKeys = ["sourceId", "stableKey"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class EpgLinkEntity(
    val sourceId: Long,
    val stableKey: String,
    val channelId: String,
    @ColumnInfo(defaultValue = "0") val manual: Boolean = false,
)

/** A programme reminder. [notifyAt] and [startAt] are epoch seconds. */
@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sourceId", "stableKey", "startAt"], unique = true)],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val stableKey: String,
    val channelName: String,
    val title: String,
    val startAt: Long,
    val notifyAt: Long,
)

/** A programme found by title, with the playlist channel it is shown on. */
data class ProgramHit(@Embedded val program: ProgramEntity, val stableKey: String, val channelName: String)

/** Just what channel matching needs from an item. */
data class MatchRow(val id: Long, val stableKey: String, val name: String, val tvgId: String?)
