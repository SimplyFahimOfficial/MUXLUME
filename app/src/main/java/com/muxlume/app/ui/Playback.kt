package com.muxlume.app.ui

import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.playback.MediaIds

/** Starts playback in the service and returns true once the controller accepted it. */
suspend fun Context.play(items: List<MediaItem>, startIndex: Int = 0, positionMs: Long = C.TIME_UNSET): Boolean {
    if (items.isEmpty()) return false
    return try {
        val player = container.player()
        player.setMediaItems(items, startIndex, positionMs)
        player.prepare()
        player.play()
        true
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Toast.makeText(this, "Playback couldn't start.", Toast.LENGTH_SHORT).show()
        false
    }
}

/**
 * Plays a live channel. [list] is the list it was picked from: channel up/down (app, remote, media
 * notification) moves through that list, looked up on demand by the playback service.
 */
suspend fun Context.playChannel(item: ItemEntity, list: ChannelList? = null): Boolean = play(listOf(MediaIds.request(item, list)))
