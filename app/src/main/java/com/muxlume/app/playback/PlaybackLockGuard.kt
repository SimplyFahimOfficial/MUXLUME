package com.muxlume.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.muxlume.app.data.db.MuxlumeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Stops playback that is no longer allowed: what plays is in a locked group and the parental lock closed (timeout or "Lock now"), or its
 * playlist was removed (Delete all data). Starting such content is refused in PlaybackService.resolve; this is the other half, and it
 * runs when the lock changes, when a playlist is removed and when another item starts, because a server change resolved before the lock
 * closed or the playlist went can still be installed after.
 *
 * What counts: the item that plays, a finished recording through the recording rule, an episode through its series (the same rule as
 * everywhere else), and, while another server plays a channel (failover), also the channel the user chose, which is what the player
 * and the notification show. The check reads the database and so can be overtaken by a channel change: the verdict is applied only to
 * the item it was made for, and an item that took its place is judged in turn.
 */
internal class PlaybackLockGuard(
    private val db: MuxlumeDatabase,
    private val unlockedNow: suspend () -> Boolean,
    /** Called with the player just before it is stopped, to save the position. Must not suspend: nothing may change between the verdict and the stop. */
    private val beforeStop: (Player) -> Unit = {},
    /** Whether an item (a series' key for an episode) is in a locked group. */
    private val locked: suspend (sourceId: Long, key: String) -> Boolean = { sourceId, key -> db.items().isLockedKey(sourceId, key) },
) {
    /** Judges what plays at every trigger; a newer trigger replaces a check still running. */
    fun watch(scope: CoroutineScope, unlocked: Flow<Boolean>, player: Player): Job = scope.launch {
        val playlists = db.sources().all().map { list -> list.map { it.id } }.distinctUntilChanged()
        merge(unlocked.map { }, playlists.map { }, itemChanges(player)).collectLatest { stopIfRestricted(player) }
    }

    private fun itemChanges(player: Player): Flow<Unit> = callbackFlow {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) trySend(Unit)
            }
        }
        player.addListener(listener)
        awaitClose { player.removeListener(listener) }
    }

    suspend fun stopIfRestricted(player: Player) {
        while (true) {
            val item = player.currentMediaItem ?: return
            val restricted = isRestricted(item)
            val now = player.currentMediaItem
            if (now == null || now.mediaId != item.mediaId || now.primaryId != item.primaryId) continue // changed while checking: judge the new one
            if (!restricted) return
            beforeStop(player)
            player.stop()
            player.clearMediaItems() // also takes the item out of the media session and its notification
            return
        }
    }

    suspend fun isRestricted(item: MediaItem): Boolean {
        val unlocked = unlockedNow()
        if (notAllowed(item.mediaId, item.mediaMetadata.extras?.getString(MediaIds.EXTRA_PARENT), unlocked)) return true
        return item.primaryId != item.mediaId && notAllowed(item.primaryId, null, unlocked)
    }

    private suspend fun notAllowed(id: String, parent: String?, unlocked: Boolean): Boolean {
        // Playable only if not in a locked group (while locked) and still there: the rule resolve() applies when a recording is opened.
        MediaIds.parseRecording(id)?.let { return db.recordings().playable(it, unlocked) == null }
        val (sourceId, key) = MediaIds.parse(id) ?: return false
        if (db.sources().get(sourceId) == null) return true
        return !unlocked && locked(sourceId, parent ?: key)
    }
}
