package com.muxlume.app.playback

import com.muxlume.app.data.db.ItemDao
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.playback.ChannelNames.Tier

/**
 * Finds the same channel elsewhere in the saved playlists, by name, on the device only. Candidates come from the text index
 * (channels with words starting like the name's words) and are judged by [ChannelNames].
 *
 * shortcut: a very different spelling ("Skysports" for "Sky Sports") is not found because the index is by words;
 * a normalised-name column or a trigram index is the upgrade if recall disappoints.
 */
class AlternativeFinder(private val db: MuxlumeDatabase) {
    class Alternative(val item: ItemEntity, val tier: Tier, val sourceName: String)

    /** Other visible live channels like [channel]: SAME before SIMILAR, other playlists before its own, then the closer name, then provider order. */
    suspend fun find(channel: ItemEntity, unlocked: Boolean, limit: Int = MAX_RESULTS): List<Alternative> {
        val wanted = ChannelNames.normalise(channel.name) ?: return emptyList()
        val words = wanted.tokens.filter { t -> t != "plus" && t.none(Char::isDigit) }
        if (words.isEmpty()) return emptyList()
        // All the words, and the first two (to reach shorter and longer names); the second query is a superset of matches.
        val queries = listOf(words, words.take(FIRST_WORDS)).distinct().mapNotNull { ItemDao.ftsQuery(it.joinToString(" ")) }
        val candidates = LinkedHashMap<Long, ItemEntity>()
        for (q in queries) db.channelServers().liveByWords(q, MAX_CANDIDATES, unlocked).forEach { candidates.putIfAbsent(it.id, it) }
        val names = db.sources().allNow().associate { it.id to it.name }
        return candidates.values.asSequence()
            .filterNot { it.sourceId == channel.sourceId && it.stableKey == channel.stableKey }
            .mapNotNull { c ->
                val n = ChannelNames.normalise(c.name) ?: return@mapNotNull null
                val tier = ChannelNames.tier(wanted, n) ?: return@mapNotNull null
                Triple(Alternative(c, tier, names[c.sourceId].orEmpty()), ChannelNames.similarity(wanted, n), c.sourceId == channel.sourceId)
            }
            .sortedWith(compareBy<Triple<Alternative, Double, Boolean>> { it.first.tier }.thenBy { it.third }.thenByDescending { it.second }.thenBy { it.first.item.position })
            .map { it.first }
            .take(limit)
            .toList()
    }

    companion object {
        const val MAX_RESULTS = 30
        private const val MAX_CANDIDATES = 500
        private const val FIRST_WORDS = 2
    }
}

/** Picks the servers to try, one after the other, when a channel does not play. Never the same one twice, never more than [MAX_ATTEMPTS]. */
object FailoverPlan {
    const val MAX_ATTEMPTS = 3

    /** The first of [candidates] (media ids, in the order to try) not in [tried], or null once [attempts] servers were tried or none is left. */
    fun next(candidates: List<String>, tried: Set<String>, attempts: Int): String? = if (attempts >= MAX_ATTEMPTS) null else candidates.firstOrNull { it !in tried }
}
