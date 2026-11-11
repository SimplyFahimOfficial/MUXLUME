package com.muxlume.app.data.epg

import android.util.Xml
import com.muxlume.app.core.net.LimitedInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/** The file isn't XMLTV, is malformed, or breaks one of the safety limits. */
class EpgFormatException(message: String) : IOException(message)

sealed interface XmltvItem
data class XmltvChannel(val id: String, val names: List<String>) : XmltvItem
data class XmltvProgramme(
    val channel: String,
    /** Epoch seconds. */
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
) : XmltvItem

/**
 * Streaming XMLTV reader for guide files from untrusted sources.
 *
 * - Android's pull parser never loads a DTD or fetches external entities; on top of that a DOCTYPE
 *   with an internal subset (where entity declarations live) is rejected, and a reference to an
 *   undefined entity such as `&xxe;` is dropped from the text, never resolved. The usual
 *   `<!DOCTYPE tv SYSTEM "xmltv.dtd">` found in real guides is accepted and ignored.
 * - Nothing is held in memory but the current element: programmes are produced one at a time.
 * - Limits: [open] caps the decompressed size and the length of any run of text between tags;
 *   nesting depth, the number of programmes and the length of each text field are capped here.
 * - Programmes outside [keepBehindSeconds]/[keepAheadSeconds] around [nowSeconds] are dropped
 *   (counted in [outsideWindow]); ones with unreadable or inverted times are skipped ([skipped]).
 */
class XmltvParser(
    private val nowSeconds: Long,
    private val keepBehindSeconds: Long = KEEP_BEHIND,
    private val keepAheadSeconds: Long = KEEP_AHEAD,
    private val maxProgrammes: Int = MAX_PROGRAMMES,
) {
    var kept = 0
        private set
    var skipped = 0
        private set
    var outsideWindow = 0
        private set

    fun parse(input: InputStream): Sequence<XmltvItem> = sequence {
        try {
            val p = newParser(input)
            readProlog(p)
            if (p.name != "tv") throw EpgFormatException("Not an XMLTV guide")
            while (true) {
                val event = p.next()
                if (event == XmlPullParser.END_DOCUMENT || (event == XmlPullParser.END_TAG && p.name == "tv")) break
                if (event != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "channel" -> readChannel(p)?.let { yield(it) }
                    "programme" -> readProgramme(p)?.let { yield(it) }
                    else -> skipElement(p)
                }
            }
        } catch (e: XmlPullParserException) {
            throw EpgFormatException("The guide file is malformed")
        }
    }

    /** Advances to the root element, rejecting an internal DTD subset on the way. */
    private fun readProlog(p: XmlPullParser) {
        while (true) {
            when (p.nextToken()) {
                XmlPullParser.START_TAG -> return
                XmlPullParser.END_DOCUMENT -> throw EpgFormatException("Empty guide file")
                XmlPullParser.DOCDECL -> if (p.text.orEmpty().contains('[')) throw EpgFormatException("Entity declarations aren't allowed")
            }
        }
    }

    private fun readChannel(p: XmlPullParser): XmltvChannel? {
        val id = p.getAttributeValue(null, "id")?.trim()?.take(MAX_ID)
        val names = ArrayList<String>(2)
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "display-name" && depth == 1) text(p, MAX_NAME)?.let { if (names.size < MAX_NAMES) names += it }
                    else { depth++; limitDepth(depth) }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> throw EpgFormatException("The guide file ends unexpectedly")
            }
        }
        return if (id.isNullOrEmpty()) null else XmltvChannel(id, names)
    }

    private fun readProgramme(p: XmlPullParser): XmltvProgramme? {
        val channel = p.getAttributeValue(null, "channel")?.trim()?.take(MAX_ID)
        val start = parseXmltvTime(p.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(p.getAttributeValue(null, "stop"))
        var title: String? = null
        var description: String? = null
        var category: String? = null
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    if (depth != 1) { depth++; limitDepth(depth); continue }
                    when (p.name) {
                        "title" -> text(p, MAX_TITLE).let { if (title == null) title = it }
                        "desc" -> text(p, MAX_DESCRIPTION).let { if (description == null) description = it }
                        "category" -> text(p, MAX_CATEGORY).let { if (category == null) category = it }
                        else -> { depth++; limitDepth(depth) }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> throw EpgFormatException("The guide file ends unexpectedly")
            }
        }
        if (channel.isNullOrEmpty() || title.isNullOrEmpty() || start == null || stop == null || stop <= start) {
            skipped++
            return null
        }
        if (stop <= nowSeconds - keepBehindSeconds || start >= nowSeconds + keepAheadSeconds) {
            outsideWindow++
            return null
        }
        if (kept >= maxProgrammes) throw EpgFormatException("The guide has too many programmes")
        kept++
        return XmltvProgramme(channel, start, stop, title!!, description, category)
    }

    /** Text of the current element (consumes its end tag), whitespace collapsed and cut to [max]; null if empty. */
    private fun text(p: XmlPullParser, max: Int): String? {
        val raw = try { p.nextText() } catch (e: XmlPullParserException) { throw EpgFormatException("The guide file is malformed") }
        return raw.trim().replace(WHITESPACE, " ").take(max).ifEmpty { null }
    }

    private fun skipElement(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> { depth++; limitDepth(depth) }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> throw EpgFormatException("The guide file ends unexpectedly")
            }
        }
    }

    private fun limitDepth(depth: Int) {
        if (depth > MAX_DEPTH) throw EpgFormatException("The guide file is nested too deeply")
    }

    companion object {
        const val KEEP_BEHIND = 24L * 3600
        const val KEEP_AHEAD = 7L * 24 * 3600
        const val MAX_PROGRAMMES = 2_000_000
        const val MAX_DECOMPRESSED = 512L * 1024 * 1024
        private const val MAX_DEPTH = 32
        private const val MAX_ID = 200
        private const val MAX_NAME = 200
        private const val MAX_NAMES = 8
        private const val MAX_TITLE = 300
        private const val MAX_DESCRIPTION = 600
        private const val MAX_CATEGORY = 100
        private val WHITESPACE = Regex("\\s+")

        /**
         * Wraps a downloaded guide for parsing: gzip is detected by its magic bytes (not the file
         * name or headers), the decompressed size is capped, and a long run of bytes without a `<`
         * (one giant text node or attribute) is refused, because the pull parser holds a whole text
         * node in memory.
         */
        fun open(stream: InputStream, maxDecompressed: Long = MAX_DECOMPRESSED): InputStream {
            val buffered = BufferedInputStream(stream)
            buffered.mark(2)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            val plain = if (b0 == GZIP_MAGIC_0 && b1 == GZIP_MAGIC_1) GZIPInputStream(buffered) else buffered
            return MarkupGuardStream(LimitedInputStream(plain, maxDecompressed))
        }

        private const val GZIP_MAGIC_0 = 0x1f
        private const val GZIP_MAGIC_1 = 0x8b

        private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(input, null)
        }

        // 20261110213000 +0100, 202611102130 Z, 20261110213000, 20261110213000 +01:00
        private val TIME = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*(Z|[+-]\d{2}:?\d{2})?$""")

        /** XMLTV time to epoch seconds; no offset means UTC. Null if it isn't a valid time. */
        fun parseXmltvTime(value: String?): Long? {
            val g = TIME.matchEntire(value?.trim() ?: return null)?.groupValues ?: return null
            val zone = g[7]
            return try {
                val offset = when {
                    zone.isEmpty() || zone == "Z" -> ZoneOffset.UTC
                    else -> {
                        val digits = zone.drop(1).replace(":", "")
                        val total = digits.take(2).toInt() * 3600 + digits.drop(2).toInt() * 60
                        ZoneOffset.ofTotalSeconds(if (zone[0] == '-') -total else total)
                    }
                }
                LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt()).toEpochSecond(offset)
            } catch (e: DateTimeException) {
                null
            }
        }
    }
}

/** Refuses input where more than [maxRun] bytes pass without a `<`. */
internal class MarkupGuardStream(input: InputStream, private val maxRun: Int = 256 * 1024) : FilterInputStream(input) {
    private var run = 0

    override fun read(): Int = super.read().also { if (it >= 0) track(it) }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        for (i in off until off + n.coerceAtLeast(0)) track(b[i].toInt())
        return n
    }

    private fun track(b: Int) {
        if (b == '<'.code) run = 0 else if (++run > maxRun) throw EpgFormatException("The guide file has an unusually long text section")
    }
}
