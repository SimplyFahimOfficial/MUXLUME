package com.muxlume.app.playback

import android.os.Bundle
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.MuxlumeDatabase

/**
 * The list a live channel was picked from, so channel up/down follows that same list. The next
 * channel is looked up in the database when asked for; nothing is queued in advance, so this works
 * in lists of any size.
 */
data class ChannelList(val order: Order, val group: String?, val favoritesSourceId: Long? = null) {
    enum class Order { PROVIDER, NAME, FAVORITES }

    fun toBundle(into: Bundle) = into.apply {
        putString(KEY_ORDER, order.name)
        putString(KEY_GROUP, group)
        putBoolean(KEY_HAS_GROUP, group != null)
        favoritesSourceId?.let { putLong(KEY_FAV_SOURCE, it) }
    }

    companion object {
        private const val KEY_ORDER = "muxlume.list.order"
        private const val KEY_GROUP = "muxlume.list.group"
        private const val KEY_HAS_GROUP = "muxlume.list.hasGroup"
        private const val KEY_FAV_SOURCE = "muxlume.list.favSource"

        /** The list a channel was played from; without one (search, history), its own group in provider order. */
        fun from(extras: Bundle?, item: ItemEntity): ChannelList {
            val order = extras?.getString(KEY_ORDER)?.let { runCatching { Order.valueOf(it) }.getOrNull() }
                ?: return ChannelList(Order.PROVIDER, item.groupTitle)
            val group = if (extras.getBoolean(KEY_HAS_GROUP)) extras.getString(KEY_GROUP) else null
            val favSource = if (extras.containsKey(KEY_FAV_SOURCE)) extras.getLong(KEY_FAV_SOURCE) else null
            return ChannelList(order, group, favSource)
        }
    }
}

/** The channel after (or before) [item] in [list], or null at the end of the list. */
/** [unlocked]: whether channels in locked groups count (see ParentalLock). */
suspend fun MuxlumeDatabase.neighbour(item: ItemEntity, list: ChannelList, forward: Boolean, unlocked: Boolean): ItemEntity? {
    val items = items()
    val group = list.group
    val pinned = if (list.order == ChannelList.Order.FAVORITES) false else channelPrefs().isPinned(item.sourceId, item.stableKey)
    return when (list.order) {
        ChannelList.Order.PROVIDER ->
            if (forward) items.nextByPosition(item.sourceId, group, pinned, item.position, unlocked)
            else items.previousByPosition(item.sourceId, group, pinned, item.position, unlocked)
        ChannelList.Order.NAME ->
            if (forward) items.nextByName(item.sourceId, group, pinned, item.name, item.position, unlocked)
            else items.previousByName(item.sourceId, group, pinned, item.name, item.position, unlocked)
        ChannelList.Order.FAVORITES -> {
            val fav = favorites().get(item.sourceId, item.stableKey) ?: return null
            if (forward) items.nextFavorite(list.favoritesSourceId, fav.addedAt, item.id, unlocked)
            else items.previousFavorite(list.favoritesSourceId, fav.addedAt, item.id, unlocked)
        }
    }
}
