package com.muxlume.app.ui

import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemEntity
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
 * The channel at [index] plus its neighbours, so next/previous (and the system media controls)
 * switch channels within the list the user picked from.
 * shortcut: capped at [QUEUE_SIZE] channels around the selection; switching beyond that needs a new pick.
 */
suspend fun Context.playFromList(list: List<ItemEntity>, index: Int): Boolean {
    val from = (index - QUEUE_SIZE / 2).coerceAtLeast(0)
    val to = (from + QUEUE_SIZE).coerceAtMost(list.size)
    return play(list.subList(from, to).map(MediaIds::request), index - from)
}

private const val QUEUE_SIZE = 300
