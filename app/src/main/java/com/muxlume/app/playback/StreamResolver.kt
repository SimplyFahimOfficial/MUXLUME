package com.muxlume.app.playback

import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.core.net.Urls
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType

/** What it takes to open a stream: the URL (which may carry credentials), the playlist's headers and its HTTP setting. */
class ResolvedStream(val url: String, val headers: Map<String, String>, val policy: HttpPolicy) {
    override fun toString() = "ResolvedStream" // never prints the URL
}

/**
 * Turns an item id into a stream. Xtream URLs include the account's login, so this runs only inside the
 * playback and recording services; nothing that leaves them (UI, logs, notifications) ever holds a [ResolvedStream].
 */
class StreamResolver(private val db: MuxlumeDatabase, private val sources: SourceRepository) {
    /**
     * Null if the source or item is gone, hidden or in a locked group while locked, or the URL is not playable.
     * An episode (`ep:` key) is as locked as its series, [parentKey].
     */
    suspend fun resolve(sourceId: Long, key: String, parentKey: String?, unlocked: Boolean): ResolvedStream? {
        val source = db.sources().get(sourceId) ?: return null
        val episode = MediaIds.parseEpisodeKey(key)
        val (url, headers) = (if (episode != null) episodeStream(source, episode, parentKey, unlocked) else itemStream(source, key, unlocked)) ?: return null
        // RTSP bypasses the HTTP client, so treat it like plain HTTP: only for sources the user allowed.
        if ((url.startsWith("rtsp", ignoreCase = true) && !source.allowHttp) || !Urls.isPlayable(url)) return null
        return ResolvedStream(url, headers, HttpPolicy.of(source.allowHttp))
    }

    private suspend fun episodeStream(source: SourceEntity, episode: Pair<String, String>, parentKey: String?, unlocked: Boolean): Pair<String, Map<String, String>>? {
        if (source.type != SourceType.XTREAM) return null
        db.items().byKey(source.id, parentKey ?: return null, unlocked) ?: return null
        return sources.xtreamApi(source).episodeUrl(episode.first, episode.second) to emptyMap()
    }

    private suspend fun itemStream(source: SourceEntity, key: String, unlocked: Boolean): Pair<String, Map<String, String>>? {
        val item = db.items().byKey(source.id, key, unlocked) ?: return null
        val url = when (source.type) {
            SourceType.XTREAM -> {
                val api = sources.xtreamApi(source)
                val id = item.remoteId ?: return null
                if (item.kind == ItemKind.LIVE) api.liveUrl(id, source.liveExtension ?: "ts") else api.movieUrl(id, item.extension ?: "mp4")
            }
            else -> item.url ?: return null
        }
        val headers = item.headers?.lines()?.mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()
        return url to headers
    }
}
