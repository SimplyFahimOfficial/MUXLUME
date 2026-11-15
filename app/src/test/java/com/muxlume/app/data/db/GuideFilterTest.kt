package com.muxlume.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The guide's "Favorites" and "On now" filters, alone and with a group, and what they never show. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class GuideFilterTest {
    private lateinit var db: MuxlumeDatabase
    private var source = 0L
    private val now = 10_000L

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        source = db.sources().insert(SourceEntity(name = "S", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0))
        fun channel(key: String, group: String, position: Int) =
            ItemEntity(sourceId = source, kind = ItemKind.LIVE, stableKey = key, name = key, groupTitle = group, logo = null, position = position)
        db.items().insertAll(
            listOf(
                channel("A", "News", 0), // favourite, on now
                channel("B", "News", 1), // on now
                channel("C", "Sport", 2), // favourite, only later
                channel("D", "News", 3), // favourite, on now, hidden
                channel("E", "Adult", 4), // on now, locked
                channel("F", "Sport", 5), // on now, programme ends exactly now
            ),
        )
        db.epg().insertLinks(listOf("A", "B", "C", "D", "E", "F").map { EpgLinkEntity(source, it, "ch$it") })
        fun programme(key: String, start: Long, stop: Long, pending: Boolean = false) =
            ProgramEntity(sourceId = source, channelId = "ch$key", start = start, stop = stop, title = "P$key", pending = pending)
        db.epg().insertPrograms(
            listOf(
                programme("A", now - 600, now + 600), programme("B", now, now + 600), // starts exactly now: on
                programme("C", now + 1, now + 900), programme("D", now - 60, now + 60), programme("E", now - 60, now + 60),
                programme("F", now - 600, now), // ends exactly now: not on
            ),
        )
        listOf("A", "C", "D").forEachIndexed { i, key -> db.favorites().add(FavoriteEntity(source, key, i.toLong())) }
        db.channelPrefs().edit(source, "D") { it.copy(hidden = true) }
        db.lockedGroups().add(LockedGroupEntity(source, "Adult"))
    }

    @After fun tearDown() = db.close()

    private suspend fun list(group: String? = null, favorites: Boolean = false, onNow: Boolean = false, unlocked: Boolean = false): List<String> {
        val source = db.guide().linkedPage(source, group, unlocked, favorites, if (onNow) now else null)
        return (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.name }
    }

    @Test
    fun `all lists every visible channel with guide data`(): Unit = runBlocking {
        assertThat(list()).containsExactly("A", "B", "C", "F").inOrder() // D hidden, E locked
        assertThat(list(unlocked = true)).containsExactly("A", "B", "C", "E", "F").inOrder()
    }

    @Test
    fun `favorites keeps favourite channels, not hidden ones`(): Unit = runBlocking {
        assertThat(list(favorites = true)).containsExactly("A", "C").inOrder()
    }

    @Test
    fun `on now keeps channels with a programme airing at that moment, start inclusive and end exclusive`(): Unit = runBlocking {
        assertThat(list(onNow = true)).containsExactly("A", "B").inOrder() // C is later, F just ended, D hidden, E locked
        assertThat(list(onNow = true, unlocked = true)).containsExactly("A", "B", "E").inOrder()
    }

    @Test
    fun `a programme that is still being imported does not count`(): Unit = runBlocking {
        db.epg().insertPrograms(listOf(ProgramEntity(sourceId = source, channelId = "chC", start = now - 10, stop = now + 10, title = "staged", pending = true)))
        assertThat(list(onNow = true)).doesNotContain("C")
    }

    @Test
    fun `the filters combine with each other and with a group`(): Unit = runBlocking {
        assertThat(list(favorites = true, onNow = true)).containsExactly("A")
        assertThat(list(group = "News", onNow = true)).containsExactly("A", "B").inOrder()
        assertThat(list(group = "Sport", onNow = true)).isEmpty() // C is only later, F is over
        assertThat(list(group = "Sport", favorites = true)).containsExactly("C")
        assertThat(list(group = "Sport", favorites = true, onNow = true)).isEmpty()
    }
}
