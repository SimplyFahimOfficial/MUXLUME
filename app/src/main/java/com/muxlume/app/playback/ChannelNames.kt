package com.muxlume.app.playback

import com.muxlume.app.data.m3u.UnnamedChannel
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Decides whether two channel names are the same channel on another server. Pure and local: nothing here touches the
 * network. The rules are constants so that a change shows up as a visible diff (and in ChannelNamesTest).
 */
object ChannelNames {
    enum class Tier { SAME, SIMILAR }

    /** [tokens] are the words left after normalising, [key] is them joined by one space. */
    class Normalised(val tokens: List<String>, val key: String) {
        /** Words that name a particular channel and must match exactly: any word with a digit ("1", "f1", "24") and "plus" ("ITV +1"). */
        val identity: List<String> = tokens.filter(::isIdentity).sorted()
        val words: Set<String> = tokens.filterNot(::isIdentity).toSet()
    }

    /** Lower than this and two different names are not offered as similar. */
    const val SIMILAR_THRESHOLD = 0.75
    private const val MIN_KEY_LENGTH = 3
    private const val TYPO_LETTERS = 5

    private val QUALITY = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h264", "h265", "1080p", "1080i", "720p", "576p", "480p", "50fps", "60fps", "hq", "lq", "raw",
    )
    private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*]|\{[^}]*}""")
    private val SEPARATORS = Regex("[|:\\-_./]")
    private val SYMBOLS = Regex("[^\\p{L}\\p{N} ]")
    private val COMBINING = Regex("\\p{M}+")
    private val PREFIX = Regex("^\\s*\\|?\\s*([a-z]{2,3})(?:\\s*[|:]|\\s+-\\s+)\\s*")

    /** ISO 3166 alpha-2 and alpha-3 codes, lower case, plus "uk". shortcut: a channel literally named like a country code is read as a prefix, only when a separator follows. */
    private val COUNTRY_CODES: Set<String> by lazy {
        val codes = HashSet<String>().apply { add("uk") }
        for (a2 in Locale.getISOCountries()) {
            codes += a2.lowercase(Locale.ROOT)
            runCatching { Locale.Builder().setRegion(a2).build().isO3Country }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { codes += it.lowercase(Locale.ROOT) }
        }
        codes
    }

    private fun isIdentity(token: String) = token == "plus" || token.any(Char::isDigit)

    /** Null if nothing usable is left (empty, or under 3 characters): such a channel has no alternatives. */
    fun normalise(name: String): Normalised? {
        if (UnnamedChannel.isGenerated(name)) return null // made up by the importer: it says nothing about which channel this is
        var s = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(COMBINING, "").lowercase(Locale.ROOT)
        s = s.replace(BRACKETS, " ")
        PREFIX.find(s)?.takeIf { it.groupValues[1] in COUNTRY_CODES }?.let { s = s.substring(it.range.last + 1) }
        s = s.replace("+", " plus ").replace(SEPARATORS, " ").replace(SYMBOLS, "")
        val tokens = s.split(' ').filter { it.isNotEmpty() && it !in QUALITY }
        val key = tokens.joinToString(" ")
        return if (key.length < MIN_KEY_LENGTH) null else Normalised(tokens, key)
    }

    /** SAME for equal names after normalising, SIMILAR for close ones that agree on every number, null otherwise. */
    fun tier(a: Normalised, b: Normalised): Tier? = when {
        a.key == b.key -> Tier.SAME
        similarity(a, b) >= SIMILAR_THRESHOLD && a.identity == b.identity && a.words.intersect(b.words).isNotEmpty() &&
            (jaccard(a, b) >= SIMILAR_THRESHOLD || differingWordsAreTypos(a, b)) -> Tier.SIMILAR
        else -> null
    }

    /** 0 to 1: the better of the share of common words and the share of common characters. */
    fun similarity(a: Normalised, b: Normalised): Double =
        max(jaccard(a, b), 1.0 - levenshtein(a.key, b.key).toDouble() / max(a.key.length, b.key.length))

    private fun jaccard(a: Normalised, b: Normalised): Double {
        val sa = a.tokens.toSet()
        val sb = b.tokens.toSet()
        return sa.intersect(sb).size.toDouble() / sa.union(sb).size
    }

    /**
     * When names are close only by their letters, the words that differ must each be a typo or plural of a word on the other side
     * (one edit, one more per 5 letters): "Sky Sport News" and "Sky Sports News" are, "Sony SAB" and "Sony MAX" are two different words.
     */
    private fun differingWordsAreTypos(a: Normalised, b: Normalised): Boolean {
        val onlyA = a.tokens.toSet() - b.tokens.toSet()
        val onlyB = b.tokens.toSet() - a.tokens.toSet()
        fun typo(x: String, y: String) = levenshtein(x, y) <= max(1, max(x.length, y.length) / TYPO_LETTERS)
        return onlyA.all { x -> onlyB.any { y -> typo(x, y) } } && onlyB.all { y -> onlyA.any { x -> typo(x, y) } }
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
