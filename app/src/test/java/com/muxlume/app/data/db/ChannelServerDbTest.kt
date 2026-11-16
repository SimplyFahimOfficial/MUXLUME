package com.muxlume.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.playback.AlternativeFinder
import com.muxlume.app.playback.ChannelNames.Tier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChannelServerDbTest {
    private lateinit var db: MuxlumeDatabase
    private var a = 0L
    private var b = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        a = db.sources().insert(SourceEntity(name = "Home", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0))
        b = db.sources().insert(SourceEntity(name = "Away", type = SourceType.M3U_URL, secret = "y", label = "l", allowHttp = false, createdAt = 1))
    }

    @After fun tearDown() = db.close()

    private fun live(source: Long, key: String, name: String, group: String = "News", position: Int = 0, kind: ItemKind = ItemKind.LIVE) =
        ItemEntity(sourceId = source, kind = kind, stableKey = key, name = name, groupTitle = group, logo = null, url = "https://x/$key", position = position)

    private suspend fun add(vararg items: ItemEntity) = db.items().insertAll(items.toList())

    private suspend fun servers(unlocked: Boolean = true) = db.channelServers().forChannel(a, "one", unlocked).first().map { it.item.stableKey }

    // ---- Saved servers

    @Test
    fun `saved servers keep the order they were added in, are not added twice, and the channel is not its own server`(): Unit = runBlocking {
        add(live(a, "one", "BBC One"), live(b, "b1", "BBC One"), live(a, "a2", "BBC One 2nd"), live(b, "b3", "BBC One HD"))
        val dao = db.channelServers()
        dao.add(a, "one", b, "b3")
        dao.add(a, "one", b, "b1")
        dao.add(a, "one", a, "a2")
        dao.add(a, "one", b, "b1") // again
        dao.add(a, "one", a, "one") // itself
        assertThat(servers()).containsExactly("b3", "b1", "a2").inOrder()

        dao.remove(a, "one", b, "b1")
        assertThat(servers()).containsExactly("b3", "a2").inOrder()
        dao.add(a, "one", b, "b1") // goes last again
        assertThat(servers()).containsExactly("b3", "a2", "b1").inOrder()
    }

    @Test
    fun `saved servers survive a refresh, and go with their playlist`(): Unit = runBlocking {
        add(live(a, "one", "BBC One"), live(b, "b1", "BBC One"))
        db.channelServers().add(a, "one", b, "b1")
        // A refresh replaces the item rows; the server is kept by key, like a favourite.
        db.openHelper.writableDatabase.execSQL("DELETE FROM items WHERE sourceId = $b")
        assertThat(servers()).isEmpty() // not shown while the channel is not there
        assertThat(db.channelServers().all()).hasSize(1)
        add(live(b, "b1", "BBC One"))
        assertThat(servers()).containsExactly("b1")

        db.sources().delete(b)
        assertThat(db.channelServers().all()).isEmpty()
    }

    @Test
    fun `hidden channels and locked groups are not offered as servers, and a locked one is back after the PIN`(): Unit = runBlocking {
        add(live(a, "one", "BBC One"), live(b, "b1", "BBC One", group = "Adult"), live(b, "b2", "BBC One", group = "News", position = 1), live(b, "m", "BBC One", kind = ItemKind.MOVIE))
        db.channelServers().add(a, "one", b, "b1")
        db.channelServers().add(a, "one", b, "b2")
        db.channelServers().add(a, "one", b, "m")
        db.lockedGroups().add(LockedGroupEntity(b, "Adult"))
        assertThat(servers(unlocked = false)).containsExactly("b2") // a movie is never a server
        assertThat(servers(unlocked = true)).containsExactly("b1", "b2").inOrder()
        db.channelPrefs().edit(b, "b2") { it.copy(hidden = true) }
        assertThat(servers(unlocked = true)).containsExactly("b1")
    }

    // ---- Finding alternatives

    private val finder get() = AlternativeFinder(db)

    @Test
    fun `same names come before similar ones, other playlists before the same one, and each tier by closeness`(): Unit = runBlocking {
        add(
            live(a, "one", "BBC One HD", position = 0),
            live(a, "a-same", "UK: BBC One", group = "UK", position = 1), // same playlist, same name
            live(b, "b-same", "BBC One FHD", position = 0), // other playlist, same name
            live(b, "b-typo", "BBC Oner", position = 1), // similar
            live(b, "b-miss", "BBC Won", position = 2), // similar by letters, but no word of it starts like a word of the name: not found (the known limit of the word index)
            live(a, "a-typo", "BBC Onee", position = 2), // similar, same playlist
        )
        val found = finder.find(db.items().byKey(a, "one", true)!!, true)
        assertThat(found.map { it.item.stableKey }).containsExactly("b-same", "a-same", "b-typo", "a-typo").inOrder()
        assertThat(found.map { it.tier }).containsExactly(Tier.SAME, Tier.SAME, Tier.SIMILAR, Tier.SIMILAR).inOrder()
        assertThat(found.map { it.sourceName }).containsExactly("Away", "Home", "Away", "Home").inOrder()
    }

    @Test
    fun `numbers keep channels apart, and the channel itself, movies, hidden and locked channels are never offered`(): Unit = runBlocking {
        add(
            live(a, "one", "Sky Sports 1"),
            live(b, "n2", "Sky Sports 2"), live(b, "n11", "Sky Sports 11"), live(b, "plus", "Sky Sports 1 +1"),
            live(b, "movie", "Sky Sports 1", kind = ItemKind.MOVIE),
            live(b, "hid", "Sky Sports 1 HD"), live(b, "lock", "Sky Sports 1 UHD", group = "Adult", position = 1), live(b, "ok", "SKY SPORTS 1", group = "Sport", position = 2),
        )
        db.channelPrefs().edit(b, "hid") { it.copy(hidden = true) }
        db.lockedGroups().add(LockedGroupEntity(b, "Adult"))
        val me = db.items().byKey(a, "one", true)!!
        assertThat(finder.find(me, unlocked = false).map { it.item.stableKey }).containsExactly("ok")
        assertThat(finder.find(me, unlocked = true).map { it.item.stableKey }).containsExactly("lock", "ok").inOrder()
    }

    @Test
    fun `a name with nothing to match on has no alternatives, and the list is capped`(): Unit = runBlocking {
        add(live(a, "e4", "E4"), live(b, "e4b", "E4"))
        assertThat(finder.find(db.items().byKey(a, "e4", true)!!, true)).isEmpty()

        add(live(a, "main", "News Channel"))
        add(*(1..50).map { live(b, "n$it", "News Channel", position = it) }.toTypedArray())
        assertThat(finder.find(db.items().byKey(a, "main", true)!!, true, limit = 30)).hasSize(30)
    }
}
