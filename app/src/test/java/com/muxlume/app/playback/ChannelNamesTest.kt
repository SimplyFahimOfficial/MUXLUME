package com.muxlume.app.playback

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.muxlume.app.playback.ChannelNames.Tier
import org.junit.Test

/** The matching rules as a table: a change to a constant or a rule shows up here as a failing pair. */
class ChannelNamesTest {
    private fun tier(a: String, b: String): Tier? = ChannelNames.tier(ChannelNames.normalise(a)!!, ChannelNames.normalise(b)!!)

    private val same = listOf(
        "BBC One HD" to "BBC One",
        "UK: BBC One" to "BBC One",
        "|UK| BBC One FHD" to "bbc one",
        "IN - Star Plus" to "Star Plus HD",
        "Sky Sports 1" to "SKY SPORTS 1 [Backup]",
        "Canal+" to "Canal Plus",
        "Café TV" to "Cafe TV",
        "ESPN (US)" to "ESPN HD",
        "ITV +1" to "ITV Plus 1",
        "CNN International 4K" to "CNN International",
        "US | HBO" to "HBO",
        "FR: TF1 HD" to "TF1",
        "Star_Plus" to "Star Plus",
        "star.plus" to "STAR PLUS",
        "Sky Sports Main Event UHD" to "Sky Sports Main Event",
        "Zee TV HD 1080p" to "Zee TV",
        "Nat Geo Wild HEVC" to "Nat Geo Wild",
        "{DE} RTL" to "RTL",
        "USA: CNN" to "CNN",
        "GBR - BBC Two" to "BBC Two",
        "Sky News" to "UK: Sky News",
        "Ｓｋｙ Ｎｅｗｓ" to "Sky News", // full-width letters
        "Discovery   Channel" to "Discovery Channel",
        "Fox Sports 2 SD" to "Fox Sports 2 50fps",
    )

    private val similar = listOf(
        "Discovery Channel" to "Discovery Chanel",
        "National Geographic" to "National Geografic",
        "Sky Sport News" to "Sky Sports News",
        "Bein Sports 1" to "Bein Sport 1",
        "Fox Sports" to "Fox Sport",
        "Star Sports 1 Hindi" to "Star Sports 1",
        "Comedy Central" to "Comedy Centrall",
    )

    private val different = listOf(
        "Sky Sports 1" to "Sky Sports 2", // numbers are part of the name
        "ITV" to "ITV +1",
        "ITV" to "ITV2",
        "Channel 4" to "Channel 5",
        "BBC One" to "BBC Two",
        "Sky Sports F1" to "Sky Sports F2",
        "CNN" to "CNN International",
        "Star Plus" to "Star Bharat",
        "Zee TV" to "Zee Cinema",
        "Sony SAB" to "Sony MAX",
        "Fox News" to "Fox Sports",
        "Discovery Science" to "Discovery Channel",
        "MTV Hits" to "MTV Live",
        "Eurosport 1" to "Eurosport 2",
        "Star Sports 1 Tamil" to "Star Sports 1 Hindi",
        "BBC - News" to "News", // "BBC" is not a country code, so it stays
        "Animal Planet" to "Animal Planet Asia",
        "History" to "History Channel",
        "Eurosport" to "Euro Sport", // no word in common: the known limit of matching by words
        "Star Plus" to "Star Plus 2",
        "Sky Sports 1" to "Sky Sports 11",
    )

    @Test
    fun `equal after normalising is the same channel`() {
        same.forEach { (a, b) -> assertWithMessage("$a / $b").that(tier(a, b)).isEqualTo(Tier.SAME) }
    }

    @Test
    fun `a typo or a plural is similar, not the same`() {
        similar.forEach { (a, b) -> assertWithMessage("$a / $b").that(tier(a, b)).isEqualTo(Tier.SIMILAR) }
    }

    @Test
    fun `different numbers, different words and short overlaps are not alternatives, in either order`() {
        different.forEach { (a, b) ->
            assertWithMessage("$a / $b").that(tier(a, b)).isNull()
            assertWithMessage("$b / $a").that(tier(b, a)).isNull()
        }
    }

    @Test
    fun `a name with nothing usable left has no alternatives`() {
        listOf("", "HD", "E4", "TV", "(HD)", "| |", "UK:").forEach { assertWithMessage(it).that(ChannelNames.normalise(it)).isNull() }
    }

    @Test
    fun `normalising drops brackets, quality words, country prefixes and accents, and keeps the rest`() {
        assertThat(ChannelNames.normalise("|UK| BBC One HD [Backup 2]")!!.key).isEqualTo("bbc one")
        assertThat(ChannelNames.normalise("Canal+ Séries")!!.key).isEqualTo("canal plus series")
        assertThat(ChannelNames.normalise("IN: Star Plus")!!.key).isEqualTo("star plus")
        assertThat(ChannelNames.normalise("BBC - News")!!.key).isEqualTo("bbc news")
        assertThat(ChannelNames.normalise("NEWS: Today")!!.key).isEqualTo("news today") // "news" is not a country code
        assertThat(ChannelNames.normalise("Sky Sports 1")!!.identity).containsExactly("1")
    }

    @Test
    fun `the similarity is a share between 0 and 1, and 1 for equal names`() {
        val a = ChannelNames.normalise("Discovery Channel")!!
        assertThat(ChannelNames.similarity(a, a)).isEqualTo(1.0)
        assertThat(ChannelNames.similarity(a, ChannelNames.normalise("Fox News")!!)).isLessThan(ChannelNames.SIMILAR_THRESHOLD)
    }

    @Test
    fun `fifty names that are different channels never match each other`() {
        val names = listOf(
            "BBC One", "BBC Two", "BBC Three", "BBC Four", "ITV", "ITV2", "ITV3", "Channel 4", "Channel 5", "Sky News", "Sky Sports 1", "Sky Sports 2",
            "CNN", "Fox News", "MSNBC", "ESPN", "ESPN 2", "HBO", "AMC", "Star Plus", "Star Bharat", "Zee TV", "Colors", "Sony SAB", "Sony MAX", "Aaj Tak",
            "NDTV 24x7", "Al Jazeera", "France 24", "TF1", "M6", "Arte", "RTL", "ProSieben", "Sat 1", "Rai 1", "Rai 2", "Antena 3", "La Sexta", "TVE 1",
        )
        val pairs = names.indices.flatMap { i -> (i + 1 until names.size).map { j -> names[i] to names[j] } }
        pairs.forEach { (x, y) -> assertWithMessage("$x / $y").that(ChannelNames.normalise(x)?.let { a -> ChannelNames.normalise(y)?.let { b -> ChannelNames.tier(a, b) } }).isNull() }
    }

    @Test
    fun `a name made only of a number sign and digits has no alternatives, so generated names never match across playlists`() {
        // Why: a generated name made of words, like "Channel 1", would be the same channel in every playlist.
        assertThat(ChannelNames.tier(ChannelNames.normalise("Channel 1")!!, ChannelNames.normalise("Channel 1")!!)).isEqualTo(ChannelNames.Tier.SAME)
        assertThat(ChannelNames.normalise("#1")).isNull()
        assertThat(ChannelNames.normalise("#42")).isNull()
        val long = ChannelNames.normalise("#1234")!! // long enough to be kept, but it has no word to search by
        assertThat(long.tokens.filter { t -> t.none(Char::isDigit) }).isEmpty()
    }
}
