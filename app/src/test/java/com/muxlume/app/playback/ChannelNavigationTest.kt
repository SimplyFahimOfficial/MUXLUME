package com.muxlume.app.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.LockedGroupEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.ui.player.isPlayingRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Playlist A's channel 7 is playing through a server from playlist B (failover or the servers sheet): navigation stays on A. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChannelNavigationTest {
    private lateinit var db: MuxlumeDatabase
    private var a = 0L
    private var b = 0L
    private val list = ChannelList(ChannelList.Order.PROVIDER, "G")

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        a = db.sources().insert(SourceEntity(name = "A", type = SourceType.M3U_URL, secret = "x", label = "a", allowHttp = false, createdAt = 0))
        b = db.sources().insert(SourceEntity(name = "B", type = SourceType.M3U_URL, secret = "x", label = "b", allowHttp = false, createdAt = 0))
        db.items().insertAll(listOf(ch(a, "a6", 6, 0), ch(a, "a7", 7, 1), ch(a, "a8", 8, 2), ch(a, "a9", 9, 3, "L")) + listOf(ch(b, "b7", 7, 0, "H"), ch(b, "b8", 8, 1, "H")))
    }

    @After fun tearDown() = db.close()

    private fun ch(source: Long, key: String, number: Int, position: Int, group: String = "G") = ItemEntity(
        sourceId = source, kind = ItemKind.LIVE, stableKey = key, name = key, groupTitle = group, logo = null, url = "https://x/$key", number = number, position = position,
    )

    private suspend fun playingServer(withList: Boolean = true) = MediaIds.request(
        db.items().byKey(b, "b7", true)!!, list.takeIf { withList }, primary = MediaIds.of(a, "a7"),
    )

    @Test
    fun `number entry and channel up down start from the channel the user chose`(): Unit = runBlocking {
        val playing = playingServer()
        assertThat(playing.mediaId).isEqualTo("$b|b7")
        assertThat(db.channelByNumberFrom(playing, 8, true)?.name).isEqualTo("a8") // not B's channel 8

        val (chosen, chosenList) = db.chosenChannel(playing, true)!!
        assertThat(chosen.name).isEqualTo("a7")
        assertThat(chosenList).isEqualTo(list)
        assertThat(db.neighbour(chosen, chosenList, true, true)?.name).isEqualTo("a8")
        assertThat(db.neighbour(chosen, chosenList, false, true)?.name).isEqualTo("a6")
    }

    @Test
    fun `without a stored list the chosen channel's group is used, not the server's`(): Unit = runBlocking {
        assertThat(db.chosenChannel(playingServer(withList = false), true)!!.second).isEqualTo(ChannelList(ChannelList.Order.PROVIDER, "G"))
    }

    @Test
    fun `a channel that is its own primary behaves as before`(): Unit = runBlocking {
        val own = MediaIds.request(db.items().byKey(a, "a7", true)!!, list)
        assertThat(db.chosenChannel(own, true)!!.first.name).isEqualTo("a7")
        assertThat(db.channelByNumberFrom(own, 9, true)?.name).isEqualTo("a9")
    }

    @Test
    fun `hidden channels and locked groups stay out of navigation`(): Unit = runBlocking {
        val playing = playingServer()
        db.channelPrefs().edit(a, "a8") { it.copy(hidden = true) }
        assertThat(db.channelByNumberFrom(playing, 8, true)).isNull()
        val (chosen, chosenList) = db.chosenChannel(playing, true)!!
        assertThat(db.neighbour(chosen, ChannelList(ChannelList.Order.PROVIDER, null), true, true)?.name).isEqualTo("a9") // skips the hidden one

        db.lockedGroups().add(LockedGroupEntity(a, "L"))
        assertThat(db.channelByNumberFrom(playing, 9, false)).isNull()
        assertThat(db.channelByNumberFrom(playing, 9, true)?.name).isEqualTo("a9")
        assertThat(db.neighbour(chosen, ChannelList(ChannelList.Order.PROVIDER, null), true, false)).isNull()
    }

    @Test
    fun `one row is the playing one in favorites that hold the same key in two playlists`(): Unit = runBlocking {
        db.items().insertAll(listOf(ch(a, "dup", 20, 10), ch(b, "dup", 21, 10)))
        db.favorites().add(FavoriteEntity(a, "dup", 1))
        db.favorites().add(FavoriteEntity(b, "dup", 2))
        val rows = db.items().favorites(ItemKind.LIVE, null, true).first()
        assertThat(rows).hasSize(2)
        assertThat(rows.filter { isPlayingRow(it, MediaIds.of(a, "dup")) }.map { it.sourceId }).containsExactly(a)
        assertThat(rows.filter { isPlayingRow(it, MediaIds.of(b, "dup")) }.map { it.sourceId }).containsExactly(b)
    }
}
