package com.muxlume.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.playback.ChannelList.Order
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.playback.neighbour
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Everything that shows items, history, reminders or programmes hides a locked group unless asked not to. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ParentalDbTest {
    private lateinit var db: MuxlumeDatabase
    private var s1 = 0L
    private var s2 = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        fun source(name: String) = SourceEntity(name = name, type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0)
        s1 = db.sources().insert(source("one"))
        s2 = db.sources().insert(source("two"))
        fun item(source: Long, kind: ItemKind, name: String, group: String, position: Int) = ItemEntity(
            sourceId = source, kind = kind, stableKey = name, name = name, groupTitle = group, logo = null, url = "https://x/$name", position = position, addedAt = position.toLong() + 1,
        )
        db.items().insertAll(
            listOf(
                item(s1, ItemKind.LIVE, "Kids One", "Kids", 0), item(s1, ItemKind.LIVE, "Kids Two", "Kids", 1),
                item(s1, ItemKind.LIVE, "Adult One", "Adult", 2), item(s1, ItemKind.LIVE, "Adult Two", "Adult", 3),
                item(s1, ItemKind.MOVIE, "Fun Movie", "Kids", 4), item(s1, ItemKind.MOVIE, "Adult Movie", "Adult", 5),
                item(s1, ItemKind.SERIES, "Adult Show", "Adult", 6),
                item(s2, ItemKind.LIVE, "Other Adult", "Adult", 0), // same group name in another playlist: not locked
            ),
        )
        db.lockedGroups().add(LockedGroupEntity(s1, "Adult"))
    }

    @After fun tearDown() = db.close()

    private suspend fun PagingSource<Int, ItemEntity>.names() =
        (load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page).data.map { it.name }

    private suspend fun walk(start: String, list: ChannelList, unlocked: Boolean): List<String> {
        val out = mutableListOf(start)
        var cur = db.items().byKey(s1, start, true)!!
        while (true) cur = db.neighbour(cur, list, true, unlocked)?.also { out += it.name } ?: return out
    }

    @Test
    fun `lists, groups and single lookups`(): Unit = runBlocking {
        val items = db.items()
        assertThat(items.page(s1, ItemKind.LIVE, null, false, false).names()).containsExactly("Kids One", "Kids Two").inOrder()
        assertThat(items.page(s1, ItemKind.LIVE, null, false, true).names()).containsExactly("Kids One", "Kids Two", "Adult One", "Adult Two").inOrder()
        assertThat(items.page(s1, ItemKind.LIVE, "Adult", false, false).names()).isEmpty()
        assertThat(items.groups(s1, ItemKind.LIVE, false).first()).containsExactly("Kids")
        assertThat(items.groups(s1, ItemKind.LIVE, true).first()).containsExactly("Kids", "Adult").inOrder()
        assertThat(items.groups(s2, ItemKind.LIVE, false).first()).containsExactly("Adult") // the lock is per playlist

        assertThat(items.byKey(s1, "Adult One", false)).isNull()
        assertThat(items.byKey(s1, "Adult One", true)).isNotNull()
        assertThat(items.byKey(s2, "Other Adult", false)).isNotNull()
        assertThat(items.observe(s1, "Adult Show", false).first()).isNull()
        assertThat(items.observe(s1, "Adult Show", true).first()).isNotNull()
        assertThat(items.isLockedKey(s1, "Adult Show")).isTrue()
        assertThat(items.isLockedKey(s1, "Kids One")).isFalse()
        assertThat(items.allGroups(s1).first()).containsExactly("Kids", "Adult") // the lock screen lists them all

        assertThat(items.recentlyAdded(ItemKind.MOVIE, 10, false).first().map { it.name }).containsExactly("Fun Movie")
        assertThat(items.recentlyAdded(ItemKind.MOVIE, 10, true).first().map { it.name }).containsExactly("Adult Movie", "Fun Movie").inOrder()
    }

    @Test
    fun `search finds a locked item only when unlocked, also by a name the user gave it`(): Unit = runBlocking {
        val items = db.items()
        fun find(q: String, unlocked: Boolean) = runBlocking { items.search(ItemDao.ftsQuery(q)!!, "%${EpgDao.likeEscape(q)}%", 20, unlocked).first().map { it.name } }
        assertThat(find("adult", false)).containsExactly("Other Adult")
        assertThat(find("adult", true)).containsExactly("Adult Movie", "Adult One", "Adult Two", "Adult Show", "Other Adult")

        db.channelPrefs().edit(s1, "Adult One") { it.copy(customName = "Evening News") }
        assertThat(find("evening", false)).isEmpty()
        assertThat(find("evening", true)).containsExactly("Evening News")
    }

    @Test
    fun `favorites and channel up and down skip locked channels`(): Unit = runBlocking {
        db.favorites().add(FavoriteEntity(s1, "Kids One", 300))
        db.favorites().add(FavoriteEntity(s1, "Adult One", 200))
        db.favorites().add(FavoriteEntity(s1, "Kids Two", 100))
        db.favorites().add(FavoriteEntity(s1, "Adult Movie", 50))
        assertThat(db.items().favorites(ItemKind.LIVE, null, false).first().map { it.name }).containsExactly("Kids One", "Kids Two").inOrder()
        assertThat(db.items().favorites(ItemKind.LIVE, null, true).first()).hasSize(3)
        assertThat(db.items().favorites(ItemKind.MOVIE, null, false).first()).isEmpty()
        assertThat(db.items().favoritesPage(ItemKind.LIVE, null, false).names()).containsExactly("Kids One", "Kids Two").inOrder()

        val provider = ChannelList(Order.PROVIDER, null)
        assertThat(walk("Kids One", provider, false)).containsExactly("Kids One", "Kids Two").inOrder()
        assertThat(walk("Kids One", provider, true)).containsExactly("Kids One", "Kids Two", "Adult One", "Adult Two").inOrder()
        val byName = ChannelList(Order.NAME, null)
        assertThat(walk("Adult One", byName, false)).containsExactly("Adult One", "Kids One", "Kids Two").inOrder() // the locked ones in between are skipped
        assertThat(walk("Adult One", byName, true)).containsExactly("Adult One", "Adult Two", "Kids One", "Kids Two").inOrder()
        val favorites = ChannelList(Order.FAVORITES, null, s1)
        assertThat(walk("Kids One", favorites, false)).containsExactly("Kids One", "Kids Two").inOrder()
        assertThat(walk("Kids One", favorites, true)).containsExactly("Kids One", "Adult One", "Kids Two").inOrder()
    }

    @Test
    fun `the guide hides locked channels in its lists, its counts and programme search`(): Unit = runBlocking {
        db.epg().insertLinks(
            listOf(
                EpgLinkEntity(s1, "Kids One", "c1", manual = true), EpgLinkEntity(s1, "Adult One", "c3", manual = true),
            ),
        )
        db.epg().insertPrograms(
            listOf(
                ProgramEntity(sourceId = s1, channelId = "c1", start = 2_000, stop = 3_000, title = "Cartoon Hour"),
                ProgramEntity(sourceId = s1, channelId = "c3", start = 2_000, stop = 3_000, title = "Late Cartoon"),
            ),
        )
        val guide = db.guide()
        assertThat(guide.linkedPage(s1, null, false).names()).containsExactly("Kids One")
        assertThat(guide.linkedPage(s1, null, true).names()).containsExactly("Kids One", "Adult One").inOrder()
        assertThat(guide.unmatchedPage(s1, false).names()).containsExactly("Kids Two")
        assertThat(guide.unmatchedPage(s1, true).names()).containsExactly("Kids Two", "Adult Two").inOrder()
        assertThat(guide.manualPage(s1, false).names()).containsExactly("Kids One")
        assertThat(guide.unmatchedCount(s1, false).first()).isEqualTo(1)
        assertThat(guide.unmatchedCount(s1, true).first()).isEqualTo(2)

        fun search(unlocked: Boolean) = runBlocking { db.epg().searchPrograms(s1, EpgDao.likeEscape("cartoon"), 1_000, 10, unlocked).map { it.program.title } }
        assertThat(search(false)).containsExactly("Cartoon Hour")
        assertThat(search(true)).containsExactly("Cartoon Hour", "Late Cartoon")
    }

    @Test
    fun `history, continue watching and recent channels hide locked items, episodes follow their series`(): Unit = runBlocking {
        fun entry(source: Long, key: String, kind: ItemKind, parent: String? = null, position: Long = 60_000) =
            HistoryEntity(MediaIds.of(source, key), source, kind, key, null, null, position, 600_000, position, parent)
        db.history().upsert(entry(s1, "Kids One", ItemKind.LIVE, position = 0))
        db.history().upsert(entry(s1, "Adult One", ItemKind.LIVE, position = 0))
        db.history().upsert(entry(s1, "Fun Movie", ItemKind.MOVIE))
        db.history().upsert(entry(s1, "Adult Movie", ItemKind.MOVIE))
        db.history().upsert(entry(s1, MediaIds.episodeKey("77", "mkv"), ItemKind.SERIES, parent = "Adult Show"))
        db.history().upsert(entry(s1, MediaIds.episodeKey("78", "mkv"), ItemKind.SERIES, parent = "Fun Movie"))

        assertThat(db.history().recentChannels(10, false).first().map { it.title }).containsExactly("Kids One")
        assertThat(db.history().recentChannels(10, true).first()).hasSize(2)
        assertThat(db.history().continueWatching(10, false).first().map { it.title }).containsExactly("Fun Movie", MediaIds.episodeKey("78", "mkv"))
        assertThat(db.history().continueWatching(10, true).first()).hasSize(4)
    }

    @Test
    fun `a reminder on a locked channel is not returned or listed while locked`(): Unit = runBlocking {
        val kids = db.reminders().insertReminder(ReminderEntity(sourceId = s1, stableKey = "Kids One", channelName = "Kids One", title = "A", startAt = 5_000, notifyAt = 4_000))
        val adult = db.reminders().insertReminder(ReminderEntity(sourceId = s1, stableKey = "Adult One", channelName = "Adult One", title = "B", startAt = 5_000, notifyAt = 4_000))
        assertThat(db.reminders().reminder(kids, false)).isNotNull()
        assertThat(db.reminders().reminder(adult, false)).isNull()
        assertThat(db.reminders().reminder(adult, true)).isNotNull()
        assertThat(db.reminders().upcomingReminders(0, false).first().map { it.title }).containsExactly("A")
        assertThat(db.reminders().upcomingReminders(0, true).first().map { it.title }).containsExactly("A", "B")
    }

    @Test
    fun `hidden channels in a locked group stay out of the hidden list while locked`(): Unit = runBlocking {
        db.channelPrefs().edit(s1, "Adult One") { it.copy(hidden = true) }
        db.channelPrefs().edit(s1, "Kids Two") { it.copy(hidden = true) }
        assertThat(db.channelPrefs().hiddenPage(false).names()).containsExactly("Kids Two")
        assertThat(db.channelPrefs().hiddenCount(false).first()).isEqualTo(1)
        assertThat(db.channelPrefs().hiddenPage(true).names()).containsExactly("Adult One", "Kids Two").inOrder()
        assertThat(db.channelPrefs().hiddenCount(true).first()).isEqualTo(2)
    }

    @Test
    fun `unlocking a group, and removing its playlist, take effect at once`(): Unit = runBlocking {
        db.lockedGroups().remove(s1, "Adult")
        assertThat(db.items().page(s1, ItemKind.LIVE, null, false, false).names()).hasSize(4)
        db.lockedGroups().add(LockedGroupEntity(s1, "Adult"))
        assertThat(db.items().page(s1, ItemKind.LIVE, null, false, false).names()).hasSize(2)
        db.sources().delete(s1)
        assertThat(db.lockedGroups().all()).isEmpty()
    }
}
