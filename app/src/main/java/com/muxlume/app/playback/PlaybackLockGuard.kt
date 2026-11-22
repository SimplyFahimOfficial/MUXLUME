package com.muxlume.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.muxlume.app.data.db.MuxlumeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Stops playback when what plays is, or has become, restricted: the parental lock closed (timeout or "Lock now") while content of a
 * locked group plays. Starting such content while locked is refused in PlaybackService.resolve; this is the other half.
 *
 * What counts: the item that plays, a finished recording of a channel in a locked group, an episode through its series (the same rule as
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
    /** Judges what plays each time the lock closes. A newer change of the lock replaces a check still running. */
    fun watch(scope: CoroutineScope, unlocked: Flow<Boolean>, player: Player): Job =
        scope.launch { unlocked.collectLatest { if (!it) stopIfRestricted(player) } }

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
        if (unlockedNow()) return false
        if (idRestricted(item.mediaId, item.mediaMetadata.extras?.getString(MediaIds.EXTRA_PARENT))) return true
        return item.primaryId != item.mediaId && idRestricted(item.primaryId, null)
    }

    private suspend fun idRestricted(id: String, parent: String?): Boolean {
        // Playable only if not in a locked group (and still there): the rule resolve() applies when a recording is opened.
        MediaIds.parseRecording(id)?.let { return db.recordings().playable(it, unlocked = false) == null }
        val (sourceId, key) = MediaIds.parse(id) ?: return false
        return locked(sourceId, parent ?: key)
    }
}
