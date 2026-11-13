package com.muxlume.app.recording

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.recording.RecordingException.Reason
import org.junit.Test

class HlsParserTest {
    private fun media(text: String) = HlsParser.parse(text) as HlsPlaylist.Media

    @Test
    fun `a media playlist gives its segments, sequence and end`() {
        val m = media(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:120
            #EXTINF:6.0,
            seg120.ts
            #EXTINF:6.0,
            /abs/seg121.ts?token=1
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertThat(m.segments).containsExactly("seg120.ts", "/abs/seg121.ts?token=1").inOrder()
        assertThat(m.firstSequence).isEqualTo(120)
        assertThat(m.targetDurationSeconds).isEqualTo(6)
        assertThat(m.ended).isTrue()
        assertThat(m.problem).isNull()
    }

    @Test
    fun `a master playlist lists variants with their bandwidth`() {
        val p = HlsParser.parse(
            "\uFEFF#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2400000\nhigh.m3u8\n",
        ) as HlsPlaylist.Master
        assertThat(p.variants.map { it.uri to it.bandwidth }).containsExactly("low.m3u8" to 800_000L, "high.m3u8" to 2_400_000L).inOrder()
    }

    @Test
    fun `what cannot be copied as it is gets a reason`() {
        fun problem(vararg lines: String) = media("#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + lines.joinToString("\n") + "\n").problem
        assertThat(problem("#EXT-X-KEY:METHOD=AES-128,URI=\"https://k/key\"", "#EXTINF:6,", "a.ts")).isEqualTo(Reason.ENCRYPTED)
        assertThat(problem("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"", "#EXTINF:6,", "a.ts")).isEqualTo(Reason.DRM)
        assertThat(problem("#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"x\"", "#EXTINF:6,", "a.ts")).isEqualTo(Reason.DRM)
        assertThat(problem("#EXT-X-MAP:URI=\"init.mp4\"", "#EXTINF:6,", "a.m4s")).isEqualTo(Reason.FMP4)
        assertThat(problem("#EXTINF:6,", "a.m4s?x=1")).isEqualTo(Reason.FMP4)
        assertThat(problem("#EXT-X-KEY:METHOD=NONE", "#EXTINF:6,", "a.ts")).isNull()
        assertThat(problem("#EXTINF:6,", "a.ts")).isNull()
    }

    @Test
    fun `anything else is not a playlist`() {
        assertThat(HlsParser.parse("")).isNull()
        assertThat(HlsParser.parse("<html>nope</html>")).isNull()
        assertThat(HlsParser.parse("\u0047\u0040\u0000")).isNull() // MPEG-TS sync byte
    }
}
