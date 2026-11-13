package com.muxlume.app.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind

/**
 * Playback ids: `<sourceId>|<stableKey>`. The UI only ever sends ids and display metadata to the
 * playback service; the service looks up the stream and builds its URL (with credentials) itself,
 * so URLs never travel through the session and other controllers can't inject arbitrary ones.
 */
object MediaIds {
    const val EXTRA_KIND = "muxlume.kind"
    const val EXTRA_PARENT = "muxlume.parent"
    private const val RECORDING_PREFIX = "rec|"

    fun of(sourceId: Long, stableKey: String) = "$sourceId|$stableKey"

    /** A finished recording: `rec|<id>`. Resolved by the playback service from the recordings table. */
    fun ofRecording(id: Long) = "$RECORDING_PREFIX$id"

    fun parseRecording(mediaId: String): Long? = mediaId.takeIf { it.startsWith(RECORDING_PREFIX) }?.removePrefix(RECORDING_PREFIX)?.toLongOrNull()

    fun parse(mediaId: String): Pair<Long, String>? {
        val sourceId = mediaId.substringBefore('|', "").toLongOrNull() ?: return null
        return sourceId to mediaId.substringAfter('|').ifEmpty { return null }
    }

    /** Series episodes are not stored as items; their key carries what's needed to play them. */
    fun episodeKey(episodeId: String, extension: String) = "ep:$episodeId:$extension"

    fun parseEpisodeKey(key: String): Pair<String, String>? {
        if (!key.startsWith("ep:")) return null
        val parts = key.split(':')
        return if (parts.size == 3) parts[1] to parts[2] else null
    }

    fun request(
        mediaId: String,
        title: String,
        subtitle: String?,
        artwork: String?,
        kind: ItemKind,
        parentKey: String? = null,
        list: ChannelList? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(artwork?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(if (kind == ItemKind.LIVE) MediaMetadata.MEDIA_TYPE_TV_CHANNEL else MediaMetadata.MEDIA_TYPE_VIDEO)
                .setExtras(Bundle().apply { putString(EXTRA_KIND, kind.name); putString(EXTRA_PARENT, parentKey); list?.toBundle(this) })
                .build(),
        )
        .build()

    /** [list]: for live channels, the list it was picked from (channel up/down follows it). */
    fun request(item: ItemEntity, list: ChannelList? = null): MediaItem =
        request(of(item.sourceId, item.stableKey), item.name, item.groupTitle.ifEmpty { null }, item.logo, item.kind, list = list)

    fun request(entry: HistoryEntity): MediaItem =
        request(entry.mediaId, entry.title, entry.subtitle, entry.artwork, entry.kind, entry.parentKey)

    fun kindOf(item: MediaItem): ItemKind =
        item.mediaMetadata.extras?.getString(EXTRA_KIND)?.let { runCatching { ItemKind.valueOf(it) }.getOrNull() } ?: ItemKind.LIVE
}
