package com.muxlume.app.data.epg

import com.muxlume.app.data.db.EpgChannelEntity
import com.muxlume.app.data.db.EpgLinkEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import java.util.Locale

/**
 * Finds the guide channel of a live channel: first by `tvg-id` (exact, ignoring case), then by name
 * with quality tags and punctuation ignored ("BBC One HD" = "bbc one"). When several guide channels
 * share a name the first one wins; the user can correct any link by hand.
 */
class EpgMatcher(channels: List<EpgChannelEntity>) {
    private val byId = HashMap<String, String>()
    private val byName = HashMap<String, String>()

    init {
        for (c in channels) {
            byId.putIfAbsent(c.channelId.lowercase(Locale.ROOT), c.channelId)
            normalize(c.name).takeIf { it.isNotEmpty() }?.let { byName.putIfAbsent(it, c.channelId) }
        }
    }

    fun match(tvgId: String?, name: String): String? =
        tvgId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { byId[it] }
            ?: normalize(name).takeIf { it.isNotEmpty() }?.let { byName[it] }

    companion object {
        private val NOISE = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h264", "h265", "hq", "lq")
        private val BRACKETS = Regex("""\[[^\]]*]|\([^)]*\)""")
        private val SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

        fun normalize(name: String): String =
            name.lowercase(Locale.ROOT).replace(BRACKETS, " ").split(SEPARATORS).filter { it.isNotEmpty() && it !in NOISE }.joinToString("")
    }
}

/** Computes and stores the automatic guide links of a source's live channels. */
class EpgLinker(private val db: MuxlumeDatabase) {

    /** Links every live channel of [sourceId] that [channels] has a match for. */
    suspend fun links(sourceId: Long, channels: List<EpgChannelEntity>): List<EpgLinkEntity> {
        if (channels.isEmpty()) return emptyList()
        val matcher = EpgMatcher(channels)
        val out = ArrayList<EpgLinkEntity>()
        var after = 0L
        while (true) {
            val rows = db.epg().liveBatch(sourceId, after, BATCH)
            if (rows.isEmpty()) return out
            for (r in rows) matcher.match(r.tvgId, r.name)?.let { out += EpgLinkEntity(sourceId, r.stableKey, it) }
            after = rows.last().id
        }
    }

    /** Re-links after the playlist changed (new channels), using the guide channels already stored. */
    suspend fun relink(sourceId: Long) {
        val channels = db.epg().channels(sourceId)
        if (channels.isEmpty()) return
        db.epg().replaceAutoLinks(sourceId, links(sourceId, channels))
    }

    private companion object {
        const val BATCH = 2000
    }
}
