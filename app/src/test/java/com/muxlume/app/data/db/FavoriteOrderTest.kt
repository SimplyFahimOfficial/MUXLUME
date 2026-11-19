package com.muxlume.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.playback.neighbour
import com.muxlume.app.playback.pagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class FavoriteOrderTest {
    private lateinit var db: MuxlumeDatabase
    private var a = 0L
    private var b = 0L
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp(): Unit = runBlocking {
        context.deleteDatabase(NAME)
        db = open()
        a = db.sources().insert(SourceEntity(name = "A", type = SourceType.M3U_URL, secret = "x", label = "a", allowHttp = false, createdAt = 0))
        b = db.sources().insert(SourceEntity(name = "B", type = SourceType.M3U_URL, secret = "x", label = "b", allowHttp = false, createdAt = 1))
        db.items().insertAll(listOf("a1", "a2", "a3", "a4").mapIndexed { i, k -> live(a, k, i) } + listOf("b1", "b2").mapIndexed { i, k -> live(b, k, i) })
        // Added oldest to newest: a1, b1, a2, a3 (default order, newest first: a3, a2, b1, a1).
        listOf(a to "a1", b to "b1", a to "a2", a to "a3").forEachIndexed { i, (s, k) -> db.favorites().add(FavoriteEntity(s, k, (i + 1) * 100L)) }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(NAME)
    }

    private fun open() = Room.databaseBuilder(context, MuxlumeDatabase::class.java, NAME).allowMainThreadQueries().build()

    private fun live(source: Long, key: String, position: Int) =
        ItemEntity(sourceId = source, kind = ItemKind.LIVE, stableKey = key, name = key, groupTitle = "G", logo = null, url = "https://x/$key", position = position)

    private suspend fun flowOrder(sourceId: Long? = null) = db.items().favorites(ItemKind.LIVE, sourceId, true).first().map { it.name }

    private suspend fun pagedOrder(sourceId: Long? = null): List<String> {
        val page = ChannelList(ChannelList.Order.FAVORITES, null, sourceId).pagingSource(db, sourceId ?: a, true).load(PagingSource.LoadParams.Refresh(null, 50, false))
        return (page as PagingSource.LoadResult.Page).data.map { it.name }
    }

    /** What channel up (forward) walks through from the first favorite. */
    private suspend fun walked(sourceId: Long? = null): List<String> {
        val list = ChannelList(ChannelList.Order.FAVORITES, null, sourceId)
        var cur = db.items().favorites(ItemKind.LIVE, sourceId, true).first().first()
        val out = mutableListOf(cur.name)
        while (true) cur = db.neighbour(cur, list, true, true)?.also { out += it.name } ?: return out
    }

    private suspend fun walkedBack(sourceId: Long? = null): List<String> {
        val list = ChannelList(ChannelList.Order.FAVORITES, null, sourceId)
        var cur = db.items().favorites(ItemKind.LIVE, sourceId, true).first().last()
        val out = mutableListOf(cur.name)
        while (true) cur = db.neighbour(cur, list, false, true)?.also { out += it.name } ?: return out
    }

    private suspend fun move(key: String, up: Boolean, source: Long = a, listSource: Long? = null): Boolean {
        val item = db.items().byKey(source, key, true) ?: return false
        return db.favorites().move(item, listSource, true, up)
    }

    private suspend fun assertEverywhere(vararg names: String, sourceId: Long? = null) {
        assertThat(flowOrder(sourceId)).containsExactlyElementsIn(names).inOrder() // the list and the home row
        assertThat(pagedOrder(sourceId)).containsExactlyElementsIn(names).inOrder() // the screen and the player's picker
        assertThat(walked(sourceId)).containsExactlyElementsIn(names).inOrder() // channel down
        assertThat(walkedBack(sourceId)).containsExactlyElementsIn(names.reversed()).inOrder() // channel up
    }

    @Test
    fun `the default order is newest first until the user moves something`(): Unit = runBlocking {
        assertEverywhere("a3", "a2", "b1", "a1")
        assertThat(db.favorites().all().map { it.sortOrder }).containsExactly(null, null, null, null)
    }

    @Test
    fun `move up and down change the same order for the list, the picker and channel up down`(): Unit = runBlocking {
        assertThat(move("a1", up = true)).isTrue()
        assertEverywhere("a3", "a2", "a1", "b1")
        assertThat(move("a3", up = false)).isTrue()
        assertEverywhere("a2", "a3", "a1", "b1")
        assertThat(move("b1", up = true, source = b)).isTrue()
        assertEverywhere("a2", "a3", "b1", "a1")
    }

    @Test
    fun `the first cannot go up, the last cannot go down, and nothing changes`(): Unit = runBlocking {
        assertThat(move("a3", up = true)).isFalse()
        assertThat(move("a1", up = false)).isFalse()
        assertThat(move("nope", up = true)).isFalse()
        assertEverywhere("a3", "a2", "b1", "a1")
        assertThat(db.favorites().all().map { it.sortOrder }).containsExactly(null, null, null, null) // a refused move writes nothing
    }

    @Test
    fun `removing keeps the rest in order, and a favorite added after reordering goes first`(): Unit = runBlocking {
        move("a1", up = true)
        move("a1", up = true) // a3, a1, a2, b1
        assertEverywhere("a3", "a1", "a2", "b1")
        db.favorites().remove(a, "a1")
        assertEverywhere("a3", "a2", "b1")
        db.favorites().add(FavoriteEntity(a, "a4", 900))
        assertEverywhere("a4", "a3", "a2", "b1")
        assertThat(move("a4", up = false)).isTrue()
        assertEverywhere("a3", "a4", "a2", "b1")
    }

    @Test
    fun `in a list limited to one playlist, moves skip the other playlist's favorites`(): Unit = runBlocking {
        // A's favorites are a3, a2, a1 with b1 between a2 and a1 in the full list.
        assertThat(move("a1", up = true, listSource = a)).isTrue()
        assertEverywhere("a3", "a1", "a2", sourceId = a)
        assertThat(flowOrder().filter { it.startsWith("a") }).containsExactly("a3", "a1", "a2").inOrder() // the same relative order in the full list
        assertThat(flowOrder()).contains("b1")
        assertThat(move("a3", up = true, listSource = a)).isFalse()
    }

    @Test
    fun `hidden favorites stay out of the moves and keep where they were`(): Unit = runBlocking {
        db.channelPrefs().edit(a, "a2") { it.copy(hidden = true) }
        assertEverywhere("a3", "b1", "a1")
        move("a1", up = true) // swaps with b1, the neighbour it can see
        assertEverywhere("a3", "a1", "b1")
    }

    @Test
    fun `the order survives closing and reopening the database`(): Unit = runBlocking {
        move("a1", up = true)
        move("a1", up = true)
        db.close()
        db = open()
        assertEverywhere("a3", "a1", "a2", "b1")
    }

    private companion object {
        const val NAME = "favorite-order-test.db"
    }
}
