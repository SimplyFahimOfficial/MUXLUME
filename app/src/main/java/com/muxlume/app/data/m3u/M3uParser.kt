package com.muxlume.app.data.m3u

import com.muxlume.app.core.net.Urls
import java.io.Reader

data class M3uEntry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val number: Int?,
    /** Only User-Agent and Referer, validated (see [M3uParser.safeHeader]). */
    val headers: Map<String, String>,
    val isVod: Boolean,
)

/**
 * Streaming parser for (extended) M3U playlists from untrusted sources.
 *
 * It never holds the whole playlist in memory: entries are produced one at a time as the reader is
 * consumed. Lines longer than [maxLineLength] are skipped instead of buffered, at most [maxEntries]
 * entries are produced, and stream URLs with non-network schemes (file:, content:, javascript:, ...)
 * are dropped so a playlist cannot point the player at local data.
 */
class M3uParser(
    reader: Reader,
    private val maxLineLength: Int = 64 * 1024,
    private val maxEntries: Int = 500_000,
) : Iterator<M3uEntry> {

    private val lines = BoundedLineReader(reader, maxLineLength)

    /** EPG (XMLTV) URL from the #EXTM3U header, if any. */
    var epgUrl: String? = null
        private set

    var skipped = 0
        private set
    var produced = 0
        private set

    private var next: M3uEntry? = null
    private var done = false

    // State of the entry being assembled from #EXTINF and the directives after it.
    private var info: ExtInf? = null
    private var extGroup: String? = null
    private val pendingHeaders = LinkedHashMap<String, String>()

    override fun hasNext(): Boolean {
        if (next == null && !done) next = advance()
        return next != null
    }

    override fun next(): M3uEntry {
        if (!hasNext()) throw NoSuchElementException()
        return next!!.also { next = null }
    }

    private fun advance(): M3uEntry? {
        while (produced < maxEntries) {
            val raw = lines.readLine() ?: break
            if (raw === BoundedLineReader.TOO_LONG) {
                skipped++
                continue
            }
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substring(7))
                    epgUrl = epgUrl ?: (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.split(',')?.firstOrNull { Urls.parseHttp(it) != null }?.trim()
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (info != null) skipped++ // previous #EXTINF had no URL
                    info = parseExtInf(line)
                    extGroup = null
                    pendingHeaders.clear()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim().take(MAX_TEXT)
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11)
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "")
                    when (key) {
                        "http-user-agent" -> safeHeader(value)?.let { pendingHeaders["User-Agent"] = it }
                        "http-referrer", "http-referer" -> safeHeader(value)?.let { pendingHeaders["Referer"] = it }
                    }
                }
                line.startsWith("#") -> Unit // other directives and comments
                else -> entryFor(line)?.let { return it }
            }
        }
        done = true
        return null
    }

    private fun entryFor(urlLine: String): M3uEntry? {
        val inf = info
        val grp = extGroup
        info = null
        extGroup = null
        // Kodi style: url|User-Agent=x&Referer=y
        val url = urlLine.substringBefore('|').trim()
        val headers = LinkedHashMap(pendingHeaders)
        pendingHeaders.clear()
        if ('|' in urlLine) {
            urlLine.substringAfter('|').split('&').forEach { kv ->
                val k = kv.substringBefore('=').trim()
                val v = safeHeader(kv.substringAfter('=', "")) ?: return@forEach
                when {
                    k.equals("User-Agent", true) -> headers["User-Agent"] = v
                    k.equals("Referer", true) || k.equals("Referrer", true) -> headers["Referer"] = v
                }
            }
        }
        if (!Urls.isPlayable(url)) {
            skipped++
            return null
        }
        val name = inf?.title?.takeIf { it.isNotBlank() } ?: inf?.attrs?.get("tvg-name") ?: fallbackName(url)
        val group = inf?.attrs?.get("group-title")?.takeIf { it.isNotBlank() } ?: grp ?: ""
        produced++
        return M3uEntry(
            name = name.take(MAX_TEXT),
            url = url,
            group = group.take(MAX_TEXT),
            logo = inf?.attrs?.get("tvg-logo")?.takeIf { Urls.parseHttp(it) != null },
            tvgId = inf?.attrs?.get("tvg-id")?.takeIf { it.isNotBlank() }?.take(MAX_TEXT),
            number = inf?.attrs?.get("tvg-chno")?.toIntOrNull(),
            headers = headers,
            isVod = isVodUrl(url),
        )
    }

    private class ExtInf(val attrs: Map<String, String>, val title: String)

    /** `#EXTINF:-1 tvg-id="x" group-title="News, World",Channel Name` — the title follows the first comma outside quotes. */
    private fun parseExtInf(line: String): ExtInf {
        val body = line.substringAfter(':', "")
        var quoted = false
        var comma = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') quoted = !quoted else if (c == ',' && !quoted) { comma = i; break }
        }
        val head = if (comma >= 0) body.substring(0, comma) else body
        val title = if (comma >= 0) body.substring(comma + 1).trim() else ""
        return ExtInf(parseAttributes(head), title)
    }

    companion object {
        private const val MAX_TEXT = 512
        private const val MAX_HEADER = 512
        private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v")

        /** key="value" or key=value pairs; keys lower-cased. Malformed parts are ignored. */
        internal fun parseAttributes(s: String): Map<String, String> {
            val out = HashMap<String, String>()
            var i = 0
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                val keyStart = i
                while (i < s.length && s[i] != '=' && !s[i].isWhitespace()) i++
                val key = s.substring(keyStart, i).lowercase()
                if (i >= s.length || s[i] != '=') { i++; continue }
                i++ // '='
                val value: String
                if (i < s.length && s[i] == '"') {
                    val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    value = s.substring(i + 1, end)
                    i = end + 1
                } else {
                    val start = i
                    while (i < s.length && !s[i].isWhitespace()) i++
                    value = s.substring(start, i)
                }
                if (key.isNotEmpty()) out[key] = value.trim()
            }
            return out
        }

        /** Header values from playlists: printable, no CR/LF (header injection), bounded length. */
        internal fun safeHeader(value: String): String? {
            val v = value.trim()
            if (v.isEmpty() || v.length > MAX_HEADER) return null
            return v.takeIf { s -> s.all { it in ' '..'~' || it.code >= 0xA0 } }
        }

        internal fun isVodUrl(url: String): Boolean {
            val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').lowercase()
            return "/movie/" in "/$path" || "/series/" in "/$path" || path.substringAfterLast('.', "") in VOD_EXTENSIONS
        }

        private fun fallbackName(url: String): String =
            url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url.substringAfter("://").substringBefore('/') }
    }
}

/** Reads lines without ever buffering more than [maxLength] characters of one line. */
internal class BoundedLineReader(private val reader: Reader, private val maxLength: Int) {
    private val buf = CharArray(8192)
    private var pos = 0
    private var len = 0
    private val line = StringBuilder()

    /** The next line, [TOO_LONG] for a line over the limit (its content is discarded), or null at the end. */
    fun readLine(): String? {
        line.setLength(0)
        var tooLong = false
        var sawAny = false
        while (true) {
            if (pos >= len) {
                len = reader.read(buf)
                pos = 0
                if (len <= 0) return if (sawAny) finish(tooLong) else null
            }
            val c = buf[pos++]
            sawAny = true
            if (c == '\n') return finish(tooLong)
            if (c == '\r') continue
            if (!tooLong) {
                if (line.length >= maxLength) { tooLong = true; line.setLength(0) } else line.append(c)
            }
        }
    }

    private fun finish(tooLong: Boolean): String = if (tooLong) TOO_LONG else line.toString()

    companion object {
        /** Identity-compared marker. */
        val TOO_LONG = String(charArrayOf('\u0000'))
    }
}
