package com.muxlume.app.data.m3u

/**
 * What an entry is called when its playlist gives it no name: its place in the playlist, "#12". Not made from its stream address (the
 * last part of that can be a token or a login), not worded, so there is no language to freeze, and recognisable: it says nothing about
 * which channel it is, so matching by name (failover to another playlist, guide channels) must skip it. Two playlists' fifth entries are
 * not the same channel.
 */
object UnnamedChannel {
    private val GENERATED = Regex("#\\d{1,9}")

    fun name(place: Int) = "#$place"

    fun isGenerated(name: String) = GENERATED.matches(name.trim())
}
