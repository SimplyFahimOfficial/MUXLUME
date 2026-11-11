package com.muxlume.app.data.epg

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.db.EpgChannelEntity
import org.junit.Test

class EpgMatcherTest {
    private fun ch(id: String, name: String) = EpgChannelEntity(1, id, name)

    private val matcher = EpgMatcher(
        listOf(ch("BBC1.uk", "BBC One"), ch("BBC1.uk", "BBC 1"), ch("news24", "News 24"), ch("cnn.us", "CNN"), ch("dup1", "Sport"), ch("dup2", "Sport")),
    )

    @Test
    fun `names are compared without quality tags, brackets, case and punctuation`() {
        assertThat(EpgMatcher.normalize("BBC One HD")).isEqualTo("bbcone")
        assertThat(EpgMatcher.normalize("  bbc-one [UK] (FHD) ")).isEqualTo("bbcone")
        assertThat(EpgMatcher.normalize("Ünï Çhannel 4K")).isEqualTo("ünïçhannel")
        assertThat(EpgMatcher.normalize("HD")).isEmpty()
    }

    @Test
    fun `tvg-id wins over the name, ignoring case`() {
        assertThat(matcher.match("bbc1.UK", "Something else")).isEqualTo("BBC1.uk")
        assertThat(matcher.match("cnn.us", "BBC One")).isEqualTo("cnn.us")
    }

    @Test
    fun `falls back to the name when the id is missing or unknown`() {
        assertThat(matcher.match(null, "News 24 HD")).isEqualTo("news24")
        assertThat(matcher.match("unknown", "BBC 1 FHD")).isEqualTo("BBC1.uk")
        assertThat(matcher.match("", "bbc one")).isEqualTo("BBC1.uk")
    }

    @Test
    fun `no match, empty names and ambiguous names`() {
        assertThat(matcher.match(null, "Local Cable Channel")).isNull()
        assertThat(matcher.match(null, "HD")).isNull()
        assertThat(matcher.match(null, "Sport")).isEqualTo("dup1") // first one wins
        assertThat(EpgMatcher(emptyList()).match("x", "y")).isNull()
    }
}
