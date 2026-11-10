package com.muxlume.app.data.m3u

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.StringReader

class M3uParserTest {

    private fun parse(text: String, maxLine: Int = 64 * 1024, maxEntries: Int = 500_000): Pair<List<M3uEntry>, M3uParser> {
        val parser = M3uParser(StringReader(text), maxLine, maxEntries)
        return parser.asSequence().toList() to parser
    }

    @Test
    fun `extended playlist with attributes, groups and epg header`() {
        val (entries, parser) = parse(
            """
            #EXTM3U url-tvg="https://epg.example/guide.xml.gz,https://epg2.example/g.xml" x-tvg-url="ignored"
            #EXTINF:-1 tvg-id="news.uk" tvg-name="News HD" tvg-logo="https://img.example/news.png" tvg-chno="101" group-title="News",News HD
            https://cdn.example/news/index.m3u8
            #EXTINF:0 tvg-id=sport group-title=Sport,Sport One
            http://cdn.example/sport.ts
            """.trimIndent(),
        )
        assertThat(parser.epgUrl).isEqualTo("https://epg.example/guide.xml.gz")
        assertThat(entries).hasSize(2)
        with(entries[0]) {
            assertThat(name).isEqualTo("News HD")
            assertThat(group).isEqualTo("News")
            assertThat(tvgId).isEqualTo("news.uk")
            assertThat(logo).isEqualTo("https://img.example/news.png")
            assertThat(number).isEqualTo(101)
            assertThat(url).isEqualTo("https://cdn.example/news/index.m3u8")
            assertThat(isVod).isFalse()
        }
        assertThat(entries[1].group).isEqualTo("Sport")
        assertThat(entries[1].tvgId).isEqualTo("sport")
    }

    @Test
    fun `commas and special characters inside quoted attributes and titles`() {
        val (entries, _) = parse(
            "#EXTM3U\n#EXTINF:-1 group-title=\"News, World & More\" tvg-name=\"Ünïcødé 📺\",Title, with comma — ünicode\nhttps://a.example/s?x=1&y=2\n",
        )
        assertThat(entries.single().group).isEqualTo("News, World & More")
        assertThat(entries.single().name).isEqualTo("Title, with comma — ünicode")
        assertThat(entries.single().url).isEqualTo("https://a.example/s?x=1&y=2")
    }

    @Test
    fun `empty and header-only playlists produce nothing`() {
        assertThat(parse("").first).isEmpty()
        assertThat(parse("#EXTM3U\n\n\n").first).isEmpty()
    }

    @Test
    fun `malformed lines are skipped without breaking later entries`() {
        val (entries, parser) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="unterminated,Broken
            #EXTINF:-1,Orphan without url
            #EXTINF garbage
            not a url at all
            #EXTINF:-1,Good
            https://ok.example/good.m3u8
            """.trimIndent(),
        )
        assertThat(entries.map { it.name }).containsExactly("Good")
        assertThat(parser.skipped).isAtLeast(2)
    }

    @Test
    fun `non-network schemes are rejected`() {
        val (entries, parser) = parse(
            "#EXTM3U\n#EXTINF:-1,Local\nfile:///data/data/com.muxlume.app/databases/muxlume.db\n" +
                "#EXTINF:-1,Content\ncontent://com.example/secret\n#EXTINF:-1,Js\njavascript:alert(1)\n" +
                "#EXTINF:-1,Rtsp\nrtsp://cam.example/live\n#EXTINF:-1,Logo\nhttps://x.example/a.ts\n",
        )
        assertThat(entries.map { it.name }).containsExactly("Rtsp", "Logo").inOrder()
        assertThat(parser.skipped).isEqualTo(3)
    }

    @Test
    fun `custom headers from EXTVLCOPT and pipe syntax, injection attempts dropped`() {
        val (entries, _) = parse(
            "#EXTM3U\n" +
                "#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=MyPlayer/1.0\n#EXTVLCOPT:http-referrer=https://ref.example/\nhttps://a.example/1.m3u8\n" +
                "#EXTINF:-1,B\nhttps://b.example/2.m3u8|User-Agent=Kodi&Referer=https://r.example/&Cookie=steal\n" +
                "#EXTINF:-1,C\n#EXTVLCOPT:http-user-agent=evil\u0007bell\nhttps://c.example/3.m3u8\n",
        )
        assertThat(entries[0].headers).containsExactly("User-Agent", "MyPlayer/1.0", "Referer", "https://ref.example/")
        assertThat(entries[1].headers).containsExactly("User-Agent", "Kodi", "Referer", "https://r.example/")
        assertThat(entries[1].url).isEqualTo("https://b.example/2.m3u8")
        assertThat(entries[2].headers).isEmpty()
    }

    @Test
    fun `headers do not leak from one entry to the next`() {
        val (entries, _) = parse("#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=UA\nhttps://a.example/1\n#EXTINF:-1,B\nhttps://b.example/2\n")
        assertThat(entries[1].headers).isEmpty()
    }

    @Test
    fun `EXTGRP, BOM, CRLF and bare urls`() {
        val (entries, _) = parse("\uFEFF#EXTM3U\r\n#EXTINF:-1,One\r\n#EXTGRP:Movies\r\nhttps://v.example/movie/u/p/1.mkv\r\nhttps://bare.example/path/stream.ts\r\n")
        assertThat(entries).hasSize(2)
        assertThat(entries[0].group).isEqualTo("Movies")
        assertThat(entries[0].isVod).isTrue()
        assertThat(entries[1].name).isEqualTo("stream.ts")
        assertThat(entries[1].group).isEmpty()
    }

    @Test
    fun `over-long lines are discarded, not buffered`() {
        val huge = "x".repeat(10_000)
        val (entries, parser) = parse("#EXTINF:-1,$huge\nhttps://a.example/1\n#EXTINF:-1,Ok\nhttps://a.example/2\n", maxLine = 1_000)
        // The long #EXTINF is dropped, so its URL becomes a bare entry; the next entry is intact.
        assertThat(entries.map { it.name }).containsExactly("1", "Ok").inOrder()
        assertThat(parser.skipped).isEqualTo(1)
    }

    @Test
    fun `entry limit stops parsing`() {
        val text = buildString { repeat(50) { append("#EXTINF:-1,C$it\nhttps://a.example/$it\n") } }
        assertThat(parse(text, maxEntries = 10).first).hasSize(10)
    }

    @Test
    fun `large playlist streams quickly`() {
        val n = 200_000
        val text = buildString(n * 120) {
            append("#EXTM3U\n")
            repeat(n) { append("#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 40}\",Channel $it\nhttps://cdn.example/live/$it.m3u8\n") }
        }
        val start = System.nanoTime()
        var count = 0
        M3uParser(StringReader(text)).forEach { count++ }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertThat(count).isEqualTo(n)
        assertThat(ms).isLessThan(10_000)
    }

    @Test
    fun `vod detection`() {
        assertThat(M3uParser.isVodUrl("http://h:80/movie/u/p/12.mp4")).isTrue()
        assertThat(M3uParser.isVodUrl("http://h/series/u/p/3.mkv")).isTrue()
        assertThat(M3uParser.isVodUrl("https://h/films/file.MKV?token=1")).isTrue()
        assertThat(M3uParser.isVodUrl("http://h/live/u/p/12.ts")).isFalse()
        assertThat(M3uParser.isVodUrl("https://h/index.m3u8")).isFalse()
    }
}
