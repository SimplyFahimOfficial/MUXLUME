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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.muxlume.app.MainActivity
import com.muxlume.app.MuxlumeApp
import com.muxlume.app.core.net.HttpPolicy
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
import kotlinx.coroutines.runBlocking
import okhttp3.Call

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
    private val policyByMediaId = HashMap<String, HttpPolicy>()
    private val hlsRetried = HashSet<String>()
    private var retries = 0
    private var retryJob: Job? = null
    private var switchJob: Job? = null

    /**
     * HTTP setting of the source that is playing. Stream and segment requests run on loader threads
     * and are tagged with it, so one source's HTTP opt-in never applies to another source's streams.
     * A queue only ever holds one source's items (a series' episodes), so "the current item" is exact.
     */
    @Volatile private var currentPolicy = HttpPolicy.HTTPS_ONLY

    override fun onCreate() {
        super.onCreate()
        http = OkHttpDataSource.Factory(
            Call.Factory { req -> container.http.newCall(req.newBuilder().tag(HttpPolicy::class.java, currentPolicy).build()) },
        )
        // One small read at start-up; the load control cannot change once the player exists.
        val buffer = runBlocking { container.settings.current().buffer }
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(buffer.minMs, buffer.maxMs, buffer.startMs, buffer.resumeMs).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(Recovery(exo))
        val player = ChannelSwitcher(exo)
        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            // Notification artwork goes through the gated client untagged, i.e. with the artwork rule.
            .setBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(OkHttpDataSource.Factory(container.http)).build())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        scope.launch { savePositionsWhilePlaying(player) }
        scope.launch { container.lock.unlocked.collect { if (!it) stopIfLocked(player) } }
    }

    /** The lock closed (timeout or "Lock now") while something from a locked group is playing: stop it and clear the notification. */
    private suspend fun stopIfLocked(player: Player) {
        val item = player.currentMediaItem ?: return
        val (sourceId, key) = MediaIds.parse(item.mediaId) ?: return
        val series = item.mediaMetadata.extras?.getString(MediaIds.EXTRA_PARENT)
        if (container.db.items().isLockedKey(sourceId, series ?: key)) {
            saveHistory(player)
            player.stop()
            player.clearMediaItems()
        }
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
            start?.let { applyFor(it.mediaId) }
            MediaSession.MediaItemsWithStartPosition(resolved, index, position)
        }
    }

    /**
     * Builds the playable item for an id from the UI, or null if the source or item is gone, or it is in a
     * locked group while locked. That also covers other controllers (Bluetooth, system UI): they get nothing to play.
     */
    private suspend fun resolve(request: MediaItem): MediaItem? {
        val (sourceId, key) = MediaIds.parse(request.mediaId) ?: return null
        val source = container.db.sources().get(sourceId) ?: return null
        val unlocked = container.lock.unlockedNow()
        val url: String
        val headers: Map<String, String>
        val episode = MediaIds.parseEpisodeKey(key)
        if (episode != null) {
            if (source.type != SourceType.XTREAM) return null
            // An episode is not an item of its own: it is as locked as the series it belongs to.
            val series = request.mediaMetadata.extras?.getString(MediaIds.EXTRA_PARENT) ?: return null
            container.db.items().byKey(sourceId, series, unlocked) ?: return null
            url = container.sources.xtreamApi(source).episodeUrl(episode.first, episode.second)
            headers = emptyMap()
        } else {
            val item = container.db.items().byKey(sourceId, key, unlocked) ?: return null
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
        policyByMediaId[request.mediaId] = HttpPolicy.of(source.allowHttp)
        val path = url.substringBefore('?').lowercase()
        return request.buildUpon()
            .setUri(url.toUri())
            .setMimeType(if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else if (path.endsWith(".mpd")) MimeTypes.APPLICATION_MPD else null)
            .build()
    }

    /** Playlist headers (User-Agent, Referer) and the source's HTTP setting apply to every request of the current item. */
    private fun applyFor(mediaId: String) {
        http.setDefaultRequestProperties(headersByMediaId[mediaId].orEmpty())
        currentPolicy = policyByMediaId[mediaId] ?: HttpPolicy.HTTPS_ONLY
    }

    /**
     * Channel up/down for live TV (app buttons, remote keys, media notification, Bluetooth): the
     * neighbouring channel of the list it was picked from is looked up when asked for. Other items
     * (a season's episodes) use the normal queue.
     */
    private inner class ChannelSwitcher(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().apply { SEEK_COMMANDS.forEach(::add) }.build()
        override fun isCommandAvailable(command: Int): Boolean = command in SEEK_COMMANDS || super.isCommandAvailable(command)
        override fun seekToNext() { if (!switchChannel(forward = true)) super.seekToNext() }
        override fun seekToNextMediaItem() { if (!switchChannel(forward = true)) super.seekToNextMediaItem() }
        override fun seekToPrevious() { if (!switchChannel(forward = false)) super.seekToPrevious() }
        override fun seekToPreviousMediaItem() { if (!switchChannel(forward = false)) super.seekToPreviousMediaItem() }

        /** True when the current item is a live channel (the switch then happens asynchronously). */
        private fun switchChannel(forward: Boolean): Boolean {
            val current = exo.currentMediaItem ?: return false
            if (MediaIds.kindOf(current) != ItemKind.LIVE) return false
            val (sourceId, key) = MediaIds.parse(current.mediaId) ?: return false
            switchJob?.cancel()
            switchJob = scope.launch {
                val unlocked = container.lock.unlockedNow()
                val item = container.db.items().byKey(sourceId, key, unlocked) ?: return@launch
                val list = ChannelList.from(current.mediaMetadata.extras, item)
                val next = container.db.neighbour(item, list, forward, unlocked) ?: return@launch // end of the list
                val resolved = resolve(MediaIds.request(next, list)) ?: return@launch
                saveHistory(exo)
                applyFor(resolved.mediaId)
                exo.setMediaItem(resolved)
                exo.prepare()
                exo.play()
            }
            return true
        }
    }

    /** Reconnects after network failures (2, 4, 8 s) and rejoins live streams that fell behind. */
    private inner class Recovery(val player: Player) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { applyFor(it.mediaId) }
            retries = 0
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retries = 0 else scope.launch { saveHistory(player) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retryAsHls(error)) return
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

    /**
     * Many providers serve HLS from links without ".m3u8", which then fail as an unknown container.
     * Retry such an item once as HLS before showing the error.
     */
    private fun Recovery.retryAsHls(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false
        val item = player.currentMediaItem ?: return false
        if (item.localConfiguration?.mimeType != null || !hlsRetried.add(item.mediaId)) return false
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
        player.prepare()
        return true
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
        val SEEK_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
