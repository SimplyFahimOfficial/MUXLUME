package com.muxlume.app.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.Xmltv
import com.muxlume.app.Xmltv.NOW
import com.muxlume.app.core.net.TooLargeException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class XmltvParserTest {
    private val hour = 3600L

    private fun parse(xml: String, parser: XmltvParser = XmltvParser(NOW)) =
        parser.parse(ByteArrayInputStream(xml.toByteArray())).toList()

    @Test
    fun `reads channels and programmes with the usual DOCTYPE`() {
        val items = parse(
            Xmltv.feed(
                Xmltv.channel("bbc1.uk", "BBC One", "BBC 1"),
                Xmltv.programme("bbc1.uk", NOW, NOW + hour, "News &amp; Weather", desc = "  The   day's\nnews ", category = "News"),
            ),
        )
        val channel = items[0] as XmltvChannel
        assertThat(channel.id).isEqualTo("bbc1.uk")
        assertThat(channel.names).containsExactly("BBC One", "BBC 1").inOrder()
        val programme = items[1] as XmltvProgramme
        assertThat(programme.title).isEqualTo("News & Weather")
        assertThat(programme.description).isEqualTo("The day's news")
        assertThat(programme.category).isEqualTo("News")
        assertThat(programme.start).isEqualTo(NOW)
        assertThat(programme.stop).isEqualTo(NOW + hour)
    }

    @Test
    fun `times with offsets, zone names and missing parts`() {
        val base = Xmltv.NOW // 2026-11-10 12:00:00 UTC
        assertThat(XmltvParser.parseXmltvTime("20261110130000 +0100")).isEqualTo(base)
        assertThat(XmltvParser.parseXmltvTime("20261110070000 -0500")).isEqualTo(base)
        assertThat(XmltvParser.parseXmltvTime("20261110173000 +05:30")).isEqualTo(base)
        assertThat(XmltvParser.parseXmltvTime("20261110120000")).isEqualTo(base) // no offset: UTC
        assertThat(XmltvParser.parseXmltvTime("202611101200 Z")).isEqualTo(base) // no seconds
        assertThat(XmltvParser.parseXmltvTime("20261110120000+0000")).isEqualTo(base)
        for (bad in listOf(null, "", "2026-11-10 12:00", "20261310120000", "20261110250000", "20260230120000", "20261110120000 +9999", "abc")) {
            assertThat(XmltvParser.parseXmltvTime(bad)).isNull()
        }
    }

    @Test
    fun `programmes across a daylight saving change keep their real length`() {
        // Europe/Berlin springs forward on 2026-03-29 at 02:00: 01:30 CET to 03:30 CEST is one hour.
        val start = XmltvParser.parseXmltvTime("20260329013000 +0100")!!
        val stop = XmltvParser.parseXmltvTime("20260329033000 +0200")!!
        assertThat(stop - start).isEqualTo(3600)
        // ...and on 2026-10-25 at 03:00 clocks go back: 02:30 CEST to 02:30 CET is one hour too.
        val a = XmltvParser.parseXmltvTime("20261025023000 +0200")!!
        val b = XmltvParser.parseXmltvTime("20261025023000 +0100")!!
        assertThat(b - a).isEqualTo(3600)
    }

    @Test
    fun `window drops old and far-future programmes, bad ones are skipped and counted`() {
        val parser = XmltvParser(NOW)
        val items = parse(
            Xmltv.feed(
                Xmltv.programme("c", NOW - 3 * 24 * hour, NOW - 3 * 24 * hour + hour, "old"),
                Xmltv.programme("c", NOW - hour, NOW + hour, "running"),
                Xmltv.programme("c", NOW + 30 * 24 * hour, NOW + 30 * 24 * hour + hour, "far"),
                Xmltv.programme("c", NOW + hour, NOW, "inverted"),
                """<programme start="bad" stop="20261110130000" channel="c"><title>x</title></programme>""",
                """<programme start="20261110130000" stop="20261110140000" channel="c"></programme>""",
                """<programme start="20261110130000" stop="20261110140000"><title>no channel</title></programme>""",
            ),
            parser,
        )
        assertThat(items.map { (it as XmltvProgramme).title }).containsExactly("running")
        assertThat(parser.kept).isEqualTo(1)
        assertThat(parser.outsideWindow).isEqualTo(2)
        assertThat(parser.skipped).isEqualTo(4)
    }

    @Test
    fun `an internal DTD subset with entities is rejected`() {
        val billionLaughs = Xmltv.feed(
            Xmltv.programme("c", NOW, NOW + hour, "&lol2;"),
            doctype = """<!DOCTYPE tv [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>""",
        )
        assertThrows(EpgFormatException::class.java) { parse(billionLaughs) }
    }

    @Test
    fun `an undefined entity reference is dropped, never resolved, and numeric references work`() {
        fun title(text: String) = (parse(Xmltv.feed(Xmltv.programme("c", NOW, NOW + hour, text))).filterIsInstance<XmltvProgramme>().singleOrNull())?.title
        assertThat(title("a &xxe; b")).isEqualTo("a b")
        assertThat(title("&xxe;")).isNull() // nothing left: skipped as a programme without a title
        assertThat(title("&#65;&#x42; &amp; &lt;")).isEqualTo("AB & <")
    }

    @Test
    fun `malformed, truncated, empty and non-XMLTV input fail cleanly`() {
        val good = Xmltv.feed(Xmltv.programme("c", NOW, NOW + hour, "t"))
        assertThrows(EpgFormatException::class.java) { parse(good.dropLast(30)) }
        assertThrows(EpgFormatException::class.java) { parse("") }
        assertThrows(EpgFormatException::class.java) { parse("<html><body>Not a guide</body></html>") }
        assertThrows(EpgFormatException::class.java) { parse("\u0000\u0001 not xml at all") }
    }

    @Test
    fun `limits on nesting, text runs and programme count`() {
        val deep = Xmltv.feed("<programme start=\"${Xmltv.time(NOW)}\" stop=\"${Xmltv.time(NOW + hour)}\" channel=\"c\">" + "<x>".repeat(40) + "</programme>")
        assertThrows(EpgFormatException::class.java) { parse(deep) }

        val hugeText = Xmltv.feed(Xmltv.programme("c", NOW, NOW + hour, "x".repeat(300_000)))
        assertThrows(EpgFormatException::class.java) { XmltvParser.open(ByteArrayInputStream(hugeText.toByteArray())).use { s -> XmltvParser(NOW).parse(s).toList() } }

        val five = Xmltv.feed(*Array(5) { Xmltv.programme("c", NOW + it * hour, NOW + (it + 1) * hour, "p$it") })
        assertThrows(EpgFormatException::class.java) { parse(five, XmltvParser(NOW, maxProgrammes = 3)) }
    }

    @Test
    fun `text fields are cut to their limits`() {
        val p = parse(Xmltv.feed(Xmltv.programme("c", NOW, NOW + hour, "t".repeat(1_000), desc = "d".repeat(5_000)))).single() as XmltvProgramme
        assertThat(p.title).hasLength(300)
        assertThat(p.description).hasLength(600)
    }

    @Test
    fun `gzip is detected by content, and its decompressed size is capped`() {
        val xml = Xmltv.feed(Xmltv.channel("c", "C"), Xmltv.programme("c", NOW, NOW + hour, "zipped"))
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(xml.toByteArray()) } }.toByteArray()
        val items = XmltvParser.open(ByteArrayInputStream(gz)).use { XmltvParser(NOW).parse(it).toList() }
        assertThat(items.filterIsInstance<XmltvProgramme>().single().title).isEqualTo("zipped")

        val bomb = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(("<tv>" + "<!-- -->".repeat(100_000)).toByteArray()) } }.toByteArray()
        assertThat(bomb.size).isLessThan(10_000)
        assertThrows(TooLargeException::class.java) { XmltvParser.open(ByteArrayInputStream(bomb), maxDecompressed = 50_000).use { it.readBytes() } }
    }

    @Test
    fun `a large guide streams through`() {
        val n = 100_000
        val xml = buildString {
            append("""<?xml version="1.0"?><tv>""")
            repeat(50) { append(Xmltv.channel("c$it", "Channel $it")) }
            repeat(n) { i -> append(Xmltv.programme("c${i % 50}", NOW + (i / 50) * 1800L, NOW + (i / 50) * 1800L + 1800, "Programme $i", desc = "Description of programme $i")) }
            append("</tv>")
        }
        val parser = XmltvParser(NOW, keepAheadSeconds = 400L * 24 * hour)
        val started = System.nanoTime()
        val count = parser.parse(ByteArrayInputStream(xml.toByteArray())).count { it is XmltvProgramme }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertThat(count).isEqualTo(n)
        assertThat(ms).isLessThan(20_000)
    }
}
