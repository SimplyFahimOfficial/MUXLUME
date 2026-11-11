package com.muxlume.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.muxlume.app.MainActivity
import com.muxlume.app.MuxlumeApp
import com.muxlume.app.core.net.Urls
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the player. The UI talks to it through a MediaController, which also gives system media
 * controls, the media notification, Bluetooth buttons and background audio for free.
 */
@OptIn(UnstableApi::class) // OkHttpDataSource, DataSourceBitmapLoader and media source wiring
class PlaybackService : MediaSessionService() {

    private val container get() = (application as MuxlumeApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var http: OkHttpDataSource.Factory
    private var session: MediaSession? = null
    private val headersByMediaId = HashMap<String, Map<String, String>>()
    private var retries = 0
    private var retryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        http = OkHttpDataSource.Factory(container.http)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(Recovery(player))
        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            // Artwork for the notification goes through the same gated HTTP client as everything else.
            .setBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(http).build())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        scope.launch { savePositionsWhilePlaying(player) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        scope.launch {
            if (player == null || !player.playWhenReady || !container.settings.current().backgroundPlayback) {
                player?.let { saveHistory(it) }
                pauseAllPlayersAndStopSelf()
            }
        }
    }

    override fun onDestroy() {
        session?.run {
            saveHistoryLater(player)
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { mediaItems.mapNotNull { resolve(it) }.toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val wanted = mediaItems.getOrNull(startIndex)?.mediaId
            val resolved = mediaItems.mapNotNull { resolve(it) }
            val index = resolved.indexOfFirst { it.mediaId == wanted }.coerceAtLeast(0)
            var position = startPositionMs
            val start = resolved.getOrNull(index)
            if (start != null && position == C.TIME_UNSET && MediaIds.kindOf(start) != ItemKind.LIVE) {
                container.db.history().get(start.mediaId)?.let { h ->
                    if (h.positionMs > 30_000 && (h.durationMs <= 0 || h.positionMs < h.durationMs * 0.95)) position = h.positionMs
                }
            }
            start?.let { applyHeaders(it.mediaId) }
            MediaSession.MediaItemsWithStartPosition(resolved, index, position)
        }
    }

    /** Builds the playable item for an id from the UI, or null if the source or item is gone. */
    private suspend fun resolve(request: MediaItem): MediaItem? {
        val (sourceId, key) = MediaIds.parse(request.mediaId) ?: return null
        val source = container.db.sources().get(sourceId) ?: return null
        val url: String
        val headers: Map<String, String>
        val episode = MediaIds.parseEpisodeKey(key)
        if (episode != null) {
            if (source.type != SourceType.XTREAM) return null
            url = container.sources.xtreamApi(source).episodeUrl(episode.first, episode.second)
            headers = emptyMap()
        } else {
            val item = container.db.items().byKey(sourceId, key) ?: return null
            url = when (source.type) {
                SourceType.XTREAM -> {
                    val api = container.sources.xtreamApi(source)
                    val id = item.remoteId ?: return null
                    if (item.kind == ItemKind.LIVE) api.liveUrl(id, source.liveExtension ?: "ts") else api.movieUrl(id, item.extension ?: "mp4")
                }
                else -> item.url ?: return null
            }
            headers = item.headers?.lines()?.mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()
        }
        // RTSP bypasses the HTTP client, so treat it like plain HTTP: only for sources the user allowed.
        if (url.startsWith("rtsp", ignoreCase = true) && !source.allowHttp) return null
        if (!Urls.isPlayable(url)) return null
        headersByMediaId[request.mediaId] = headers
        val path = url.substringBefore('?').lowercase()
        return request.buildUpon()
            .setUri(url.toUri())
            .setMimeType(if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else if (path.endsWith(".mpd")) MimeTypes.APPLICATION_MPD else null)
            .build()
    }

    /** Playlist headers (User-Agent, Referer) apply to every request of the current item, including segments. */
    private fun applyHeaders(mediaId: String) {
        http.setDefaultRequestProperties(headersByMediaId[mediaId].orEmpty())
    }

    /** Reconnects after network failures (2, 4, 8 s) and rejoins live streams that fell behind. */
    private inner class Recovery(private val player: Player) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { applyHeaders(it.mediaId) }
            retries = 0
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retries = 0 else scope.launch { saveHistory(player) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            val network = error.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
            if (network && retries < MAX_RETRIES) {
                retries++
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(2_000L shl (retries - 1))
                    if (player.playerError != null) player.prepare()
                }
            }
        }
    }

    private suspend fun savePositionsWhilePlaying(player: Player) {
        while (scope.isActive) {
            delay(10_000)
            if (player.isPlaying) saveHistory(player)
        }
    }

    private suspend fun saveHistory(player: Player) {
        // The source may have been deleted meanwhile (foreign key); losing that one position is fine.
        entryFor(player)?.let { runCatching { container.db.history().upsert(it) } }
    }

    private fun saveHistoryLater(player: Player) {
        val entry = entryFor(player) ?: return
        container.appScope.launch(Dispatchers.IO) { runCatching { container.db.history().upsert(entry) } }
    }

    private fun entryFor(player: Player): HistoryEntity? {
        val item = player.currentMediaItem ?: return null
        val (sourceId, _) = MediaIds.parse(item.mediaId) ?: return null
        val kind = MediaIds.kindOf(item)
        val meta = item.mediaMetadata
        return HistoryEntity(
            mediaId = item.mediaId,
            sourceId = sourceId,
            kind = kind,
            title = meta.title?.toString().orEmpty(),
            subtitle = meta.artist?.toString(),
            artwork = meta.artworkUri?.toString(),
            positionMs = if (kind == ItemKind.LIVE) 0 else player.currentPosition,
            durationMs = if (kind == ItemKind.LIVE) 0 else player.duration.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(),
            parentKey = meta.extras?.getString(MediaIds.EXTRA_PARENT),
        )
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
