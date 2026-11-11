package com.muxlume.app.core.net

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.data.db.ItemDao
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpTest {
    @Test
    fun `redact keeps only scheme, host and non-default port`() {
        assertThat(Urls.redact("http://user:pw@iptv.example:8080/get.php?username=a&password=b")).isEqualTo("http://iptv.example:8080")
        assertThat(Urls.redact("https://cdn.example/path/x.m3u8?token=abc")).isEqualTo("https://cdn.example")
        assertThat(Urls.redact("rtsp://cam/secret")).isEqualTo("rtsp://…")
        assertThat(Urls.redact(null)).isEmpty()
    }

    @Test
    fun `playable schemes`() {
        assertThat(Urls.isPlayable("https://a.example/x.m3u8")).isTrue()
        assertThat(Urls.isPlayable("http://1.2.3.4:8000/live/1.ts")).isTrue()
        assertThat(Urls.isPlayable("rtsp://cam.example:554/s")).isTrue()
        assertThat(Urls.isPlayable("file:///sdcard/x")).isFalse()
        assertThat(Urls.isPlayable("content://x/y")).isFalse()
        assertThat(Urls.isPlayable("rtmp://x/y")).isFalse()
        assertThat(Urls.isPlayable("https://" + "a".repeat(5000))).isFalse()
    }

    @Test
    fun `limited stream stops at the limit`() {
        val s = LimitedInputStream(ByteArrayInputStream(ByteArray(2048)), 1024)
        assertThrows(TooLargeException::class.java) { s.readBytes() }
    }

    @Test
    fun `media ids round trip`() {
        assertThat(MediaIds.parse(MediaIds.of(12, "live:7"))).isEqualTo(12L to "live:7")
        assertThat(MediaIds.parse("x|y")).isNull()
        assertThat(MediaIds.parse("3|")).isNull()
        assertThat(MediaIds.parseEpisodeKey(MediaIds.episodeKey("55", "mkv"))).isEqualTo("55" to "mkv")
        assertThat(MediaIds.parseEpisodeKey("live:1")).isNull()
    }

    @Test
    fun `fts query matches word prefixes and neutralises operators`() {
        assertThat(ItemDao.ftsQuery("bbc on")).isEqualTo("\"bbc*\" \"on*\"")
        assertThat(ItemDao.ftsQuery("a\" OR NEAR(x) -y*")).isEqualTo("\"a*\" \"OR*\" \"NEAR*\" \"x*\" \"y*\"")
        assertThat(ItemDao.ftsQuery("Ünïcødé 2")).isEqualTo("\"Ünïcødé*\" \"2*\"")
        assertThat(ItemDao.ftsQuery(" - ")).isNull()
    }

    @Test
    fun `gate uses the request's source policy, artwork rule only for untagged requests`() {
        val url = "http://iptv.example/x".toHttpUrl()
        CleartextGate.artworkAllowed = true
        assertThat(CleartextGate.permits(request(url, HttpPolicy.HTTPS_ONLY))).isFalse()
        assertThat(CleartextGate.permits(request(url, HttpPolicy.ALLOW_HTTP))).isTrue()
        assertThat(CleartextGate.permits(Request.Builder().url(url).build())).isTrue()
        CleartextGate.artworkAllowed = false
        assertThat(CleartextGate.permits(Request.Builder().url(url).build())).isFalse()
        assertThat(CleartextGate.permits(request("https://iptv.example/x".toHttpUrl(), HttpPolicy.HTTPS_ONLY))).isTrue()
    }
}
