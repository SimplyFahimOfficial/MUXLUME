package com.muxlume.app.data.db

import android.content.Context
import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.paging.PagingSource
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.playback.ChannelList.Order
import com.muxlume.app.playback.channelByNumber
import com.muxlume.app.playback.listAfterJump
import com.muxlume.app.playback.neighbour
import com.muxlume.app.playback.pagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class DatabaseTest {
    private lateinit var db: MuxlumeDatabase
    private var source = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        source = db.sources().insert(SourceEntity(name = "S", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0))
        // Provider order: position. Names deliberately out of order, with a case-only difference.
        val rows = listOf("delta" to "News", "Alpha" to "Sport", "charlie" to "News", "bravo" to "News", "alpha" to "News")
        db.items().insertAll(rows.mapIndexed { i, (name, group) -> live(name, group, i) })
    }

    @After fun tearDown() = db.close()

    private fun live(name: String, group: String, position: Int, pending: Boolean = false) = ItemEntity(
        sourceId = source, kind = ItemKind.LIVE, stableKey = name, name = name, groupTitle = group, logo = null,
        url = "https://x/$name", position = position, pending = pending,
    )

    private suspend fun item(name: String) = db.items().byKey(source, name, true)!!

    /** Walks the whole list from [start] in one direction. */
    private suspend fun walk(start: String, list: ChannelList, forward: Boolean): List<String> {
        val out = mutableListOf(start)
        var cur = item(start)
        while (true) cur = db.neighbour(cur, list, forward, true)?.also { out += it.name } ?: return out
    }

    @Test
    fun `channel up and down follow provider order, within a group or across all`(): Unit = runBlocking {
        assertThat(walk("delta", ChannelList(Order.PROVIDER, null), true)).containsExactly("delta", "Alpha", "charlie", "bravo", "alpha").inOrder()
        assertThat(walk("delta", ChannelList(Order.PROVIDER, "News"), true)).containsExactly("delta", "charlie", "bravo", "alpha").inOrder()
        assertThat(walk("alpha", ChannelList(Order.PROVIDER, "News"), false)).containsExactly("alpha", "bravo", "charlie", "delta").inOrder()
    }

    @Test
    fun `alphabetical order matches the list's case-insensitive sort with position as tie-break`(): Unit = runBlocking {
        // The list sorts by name COLLATE NOCASE, then position: Alpha(1) before alpha(4).
        assertThat(walk("Alpha", ChannelList(Order.NAME, null), true)).containsExactly("Alpha", "alpha", "bravo", "charlie", "delta").inOrder()
        assertThat(walk("delta", ChannelList(Order.NAME, null), false)).containsExactly("delta", "charlie", "bravo", "alpha", "Alpha").inOrder()
        assertThat(walk("alpha", ChannelList(Order.NAME, "News"), true)).containsExactly("alpha", "bravo", "charlie", "delta").inOrder()
    }

    @Test
    fun `favorites order is newest first, and staged rows are never a neighbour`(): Unit = runBlocking {
        db.favorites().add(FavoriteEntity(source, "charlie", 100))
        db.favorites().add(FavoriteEntity(source, "delta", 300))
        db.favorites().add(FavoriteEntity(source, "bravo", 200))
        val fav = ChannelList(Order.FAVORITES, null, source)
        assertThat(walk("delta", fav, true)).containsExactly("delta", "bravo", "charlie").inOrder()
        assertThat(walk("charlie", fav, false)).containsExactly("charlie", "bravo", "delta").inOrder()

        db.items().insertAll(listOf(live("echo", "News", 9, pending = true)))
        assertThat(walk("delta", ChannelList(Order.PROVIDER, "News"), true)).doesNotContain("echo")
    }

    @Test
    fun `without a list, a channel switches within its own group`(): Unit = runBlocking {
        val list = ChannelList.from(null, item("charlie"))
        assertThat(list).isEqualTo(ChannelList(Order.PROVIDER, "News"))
        val bundle = ChannelList(Order.NAME, null).toBundle(android.os.Bundle())
        assertThat(ChannelList.from(bundle, item("charlie"))).isEqualTo(ChannelList(Order.NAME, null))
    }

    /** A real database at schema [version], built from its exported schema (app/schemas/.../<version>.json). */
    private fun createAt(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.deleteDatabase(DB)
        val schema = Json.parseToJsonElement(File("schemas/com.muxlume.app.data.db.MuxlumeDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(DB).apply { parentFile?.mkdirs() }, null).use { old ->
            for (e in schema.getValue("entities").jsonArray) {
                val table = e.jsonObject.getValue("tableName").jsonPrimitive.content
                old.execSQL(e.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach { old.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["views"]?.jsonArray?.forEach { v ->
                old.execSQL(v.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${VIEW_NAME}", v.jsonObject.getValue("viewName").jsonPrimitive.content))
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema.getValue("identityHash").jsonPrimitive.content}')")
            fill(old)
            old.version = version
        }
    }

    private fun openCurrent() = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MuxlumeDatabase::class.java, DB)
        .allowMainThreadQueries().build()

    private fun sql(db: SQLiteDatabase, vararg statements: String) = statements.forEach(db::execSQL)

    /**
     * Opens a real old database with the current one: Room runs the auto-migrations on open and
     * validates the result against the current schema, failing on any mismatch (the approach of
     * Daysemble's MigrationTest, since Room's own helper needs the schemas as Android assets).
     */
    @Test
    fun `migration 1 to 3 keeps rows, indexes names for search and adds the guide tables`() {
        createAt(1) { old ->
            sql(
                old,
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 1, 0, 0)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'k', 'Sky News HD', 'News', 0)",
            )
        }
        val migrated = openCurrent()
        try {
            val found = runBlocking { migrated.items().search(ItemDao.ftsQuery("news")!!, "", 10, true).first() }
            assertThat(found.map { it.name }).containsExactly("Sky News HD")
            assertThat(found.single().pending).isFalse()
            val source = runBlocking { migrated.sources().get(1) }!!
            assertThat(source.guideEnabled).isFalse()
            assertThat(source.epgPrograms).isEqualTo(0)
            assertThat(runBlocking { migrated.reminders().remindersAfter(0) }).isEmpty()
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `migration 2 to 3 wipes guide links that used to be stored in plain text`() {
        createAt(2) { old ->
            sql(
                old,
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount, epgUrl) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 0, 0, 0, 'https://guide.example/x.xml?token=SECRET')",
            )
        }
        val migrated = openCurrent()
        try {
            assertThat(runBlocking { migrated.sources().get(1) }!!.epgUrl).isNull()
        } finally {
            migrated.close()
        }
    }


    // ---- Channel preferences (hide, rename, pin)

    private val prefs get() = db.channelPrefs()

    private suspend fun edit(key: String, change: (ChannelPrefEntity) -> ChannelPrefEntity) = prefs.edit(source, key, change)

    private suspend fun listed(group: String? = null, alphabetical: Boolean = false): List<String> {
        val page = db.items().page(source, ItemKind.LIVE, group, alphabetical, true).load(PagingSource.LoadParams.Refresh(null, 100, false))
        return (page as PagingSource.LoadResult.Page).data.map { it.name }
    }

    private fun find(q: String) = runBlocking { db.items().search(ItemDao.ftsQuery(q)!!, "%${EpgDao.likeEscape(q)}%", 10, true).first().map { it.name } }

    @Test
    fun `preferences survive a refresh, which replaces the item rows`(): Unit = runBlocking {
        edit("bravo") { it.copy(customName = "Bravo HD", pinned = true) }
        edit("delta") { it.copy(hidden = true) }

        // A refresh stages a new list (the provider renamed bravo) and swaps it in.
        val staged = listOf("delta", "Alpha", "charlie", "bravo", "alpha").mapIndexed { i, key ->
            live(key, if (key == "Alpha") "Sport" else "News", i, pending = true).copy(name = if (key == "bravo") "BRAVO (provider)" else key)
        }
        db.items().insertAll(staged)
        db.items().commitPending(source)

        assertThat(listed()).containsExactly("Bravo HD", "Alpha", "charlie", "alpha").inOrder() // pinned first, delta hidden
        assertThat(db.items().byKey(source, "delta", true)).isNull()
    }

    private suspend fun pageOf(list: ChannelList, unlocked: Boolean = true): List<String> =
        (list.pagingSource(db, source, unlocked).load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.name }

    @Test
    fun `the picker lists a list in the order channel up and down follows, without hidden or locked channels`(): Unit = runBlocking {
        db.favorites().add(FavoriteEntity(source, "bravo", 200))
        db.favorites().add(FavoriteEntity(source, "delta", 300))
        assertThat(pageOf(ChannelList(Order.PROVIDER, null))).containsExactly("delta", "Alpha", "charlie", "bravo", "alpha").inOrder()
        assertThat(pageOf(ChannelList(Order.PROVIDER, "News"))).containsExactly("delta", "charlie", "bravo", "alpha").inOrder()
        assertThat(pageOf(ChannelList(Order.NAME, "News"))).containsExactly("alpha", "bravo", "charlie", "delta").inOrder()
        assertThat(pageOf(ChannelList(Order.FAVORITES, null, source))).containsExactly("delta", "bravo").inOrder() // newest first

        edit("bravo") { it.copy(hidden = true) }
        db.lockedGroups().add(LockedGroupEntity(source, "Sport"))
        assertThat(pageOf(ChannelList(Order.PROVIDER, null), unlocked = false)).containsExactly("delta", "charlie", "alpha").inOrder()
        assertThat(pageOf(ChannelList(Order.PROVIDER, null))).containsExactly("delta", "Alpha", "charlie", "alpha").inOrder()
        assertThat(pageOf(ChannelList(Order.FAVORITES, null, source))).containsExactly("delta")
    }

    @Test
    fun `picker search finds live channels of one playlist, by provider name and by renamed name, without hidden or locked ones`(): Unit = runBlocking {
        val other = db.sources().insert(SourceEntity(name = "O", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 1))
        db.items().insertAll(listOf(live("charlie other", "News", 0).copy(sourceId = other, stableKey = "co"), live("charlie movie", "Films", 9).copy(kind = ItemKind.MOVIE)))
        fun search(q: String, unlocked: Boolean = true) = runBlocking {
            db.channelPrefs().searchLive(ItemDao.ftsQuery(q)!!, "%${EpgDao.likeEscape(q)}%", source, 20, unlocked).first().map { it.name }
        }
        assertThat(search("charl")).containsExactly("charlie") // not the other playlist, not the movie

        edit("delta") { it.copy(customName = "Zulu One") }
        assertThat(search("zulu")).containsExactly("Zulu One") // found by the name the user gave, shown by it
        assertThat(search("delta")).containsExactly("Zulu One")

        edit("charlie") { it.copy(hidden = true) }
        db.lockedGroups().add(LockedGroupEntity(source, "Sport"))
        assertThat(search("charl")).isEmpty()
        assertThat(search("alpha", unlocked = false)).containsExactly("alpha")
        assertThat(search("alpha")).containsExactly("Alpha", "alpha")
    }

    @Test
    fun `a typed number finds the channel with that number, or the place in the list when the playlist has no numbers`(): Unit = runBlocking {
        // No numbers yet: 3 is the third channel in provider order (charlie), 6 is past the end, 0 is nothing.
        assertThat(db.channelByNumber(source, 3, true)!!.name).isEqualTo("charlie")
        assertThat(db.channelByNumber(source, 6, true)).isNull()
        assertThat(db.channelByNumber(source, 0, true)).isNull()

        db.openHelper.writableDatabase.execSQL("UPDATE items SET number = 7 WHERE stableKey = 'bravo'")
        db.openHelper.writableDatabase.execSQL("UPDATE items SET number = 7 WHERE stableKey = 'alpha'") // a clash: the first in provider order wins
        db.openHelper.writableDatabase.execSQL("UPDATE items SET number = 12 WHERE stableKey = 'delta'")
        assertThat(db.channelByNumber(source, 7, true)!!.name).isEqualTo("bravo")
        assertThat(db.channelByNumber(source, 12, true)!!.name).isEqualTo("delta")
        assertThat(db.channelByNumber(source, 3, true)).isNull() // numbered playlist: places no longer count
    }

    @Test
    fun `hidden and locked channels cannot be reached by number`(): Unit = runBlocking {
        db.openHelper.writableDatabase.execSQL("UPDATE items SET number = position + 1")
        edit("delta") { it.copy(hidden = true) } // number 1
        db.lockedGroups().add(LockedGroupEntity(source, "Sport")) // Alpha, number 2
        assertThat(db.channelByNumber(source, 1, true)).isNull()
        assertThat(db.channelByNumber(source, 2, false)).isNull()
        assertThat(db.channelByNumber(source, 2, true)!!.name).isEqualTo("Alpha")
        assertThat(db.channelByNumber(source, 3, false)!!.name).isEqualTo("charlie")
    }

    @Test
    fun `after a jump the list is kept where the target is in it, otherwise the target's own group`(): Unit = runBlocking {
        val news = item("bravo") // group News
        val sport = item("Alpha") // group Sport
        assertThat(listAfterJump(ChannelList(Order.PROVIDER, null), sport, false)).isEqualTo(ChannelList(Order.PROVIDER, null))
        assertThat(listAfterJump(ChannelList(Order.NAME, "News"), news, false)).isEqualTo(ChannelList(Order.NAME, "News"))
        assertThat(listAfterJump(ChannelList(Order.NAME, "News"), sport, false)).isEqualTo(ChannelList(Order.PROVIDER, "Sport"))
        assertThat(listAfterJump(ChannelList(Order.FAVORITES, null, source), news, true)).isEqualTo(ChannelList(Order.FAVORITES, null, source))
        assertThat(listAfterJump(ChannelList(Order.FAVORITES, null, source), news, false)).isEqualTo(ChannelList(Order.PROVIDER, "News"))
    }

    @Test
    fun `hidden channels are absent from lists, groups, search, favorites, recents and channel up and down`(): Unit = runBlocking {
        db.favorites().add(FavoriteEntity(source, "charlie", 100))
        db.favorites().add(FavoriteEntity(source, "bravo", 200))
        db.favorites().add(FavoriteEntity(source, "delta", 300))
        db.history().upsert(HistoryEntity(MediaIds.of(source, "delta"), source, ItemKind.LIVE, "delta", null, null, 0, 0, 1))
        assertThat(db.history().recentChannels(10, true).first()).hasSize(1)

        edit("charlie") { it.copy(hidden = true) }
        edit("Alpha") { it.copy(hidden = true) }
        edit("delta") { it.copy(hidden = true) }

        assertThat(listed()).containsExactly("bravo", "alpha").inOrder()
        assertThat(db.items().groups(source, ItemKind.LIVE, true).first()).containsExactly("News") // "Sport" had only Alpha
        assertThat(find("charlie")).isEmpty()
        assertThat(find("bravo")).containsExactly("bravo")
        assertThat(db.items().favorites(ItemKind.LIVE, null, true).first().map { it.name }).containsExactly("bravo")
        assertThat(db.history().recentChannels(10, true).first()).isEmpty()
        assertThat(walk("bravo", ChannelList(Order.PROVIDER, null), true)).containsExactly("bravo", "alpha").inOrder()
        assertThat(walk("alpha", ChannelList(Order.NAME, null), false)).containsExactly("alpha")

        edit("delta") { it.copy(hidden = false) }
        assertThat(db.history().recentChannels(10, true).first()).hasSize(1) // the history row was kept
        assertThat(walk("delta", ChannelList(Order.FAVORITES, null, source), true)).containsExactly("delta", "bravo").inOrder() // charlie stays hidden
        assertThat(walk("delta", ChannelList(Order.PROVIDER, "News"), true)).containsExactly("delta", "bravo", "alpha").inOrder()
    }

    @Test
    fun `a renamed channel shows its name everywhere and is found by both names, with wildcards taken literally`(): Unit = runBlocking {
        edit("bravo") { it.copy(customName = "Sports 100%_Hub") }

        assertThat(listed()).contains("Sports 100%_Hub")
        assertThat(listed()).doesNotContain("bravo")
        assertThat(item("bravo").name).isEqualTo("Sports 100%_Hub")
        assertThat(find("bravo")).containsExactly("Sports 100%_Hub") // the provider's name still finds it
        assertThat(find("sports")).containsExactly("Sports 100%_Hub") // so does the new name, which the text index doesn't know
        assertThat(find("0%_H")).containsExactly("Sports 100%_Hub")
        assertThat(find("1%0")).isEmpty() // would match if % were a wildcard
        assertThat(find("1_0")).isEmpty() // would match if _ were a wildcard

        edit("bravo") { it.copy(customName = null) }
        assertThat(listed()).contains("bravo")
        assertThat(prefs.all()).isEmpty() // all defaults: the row is gone
    }

    @Test
    fun `pinned channels come first in lists and channel up and down follows`(): Unit = runBlocking {
        edit("charlie") { it.copy(pinned = true) }
        assertThat(listed()).containsExactly("charlie", "delta", "Alpha", "bravo", "alpha").inOrder()
        assertThat(listed(alphabetical = true)).containsExactly("charlie", "Alpha", "alpha", "bravo", "delta").inOrder()
        assertThat(walk("charlie", ChannelList(Order.PROVIDER, null), true)).containsExactly("charlie", "delta", "Alpha", "bravo", "alpha").inOrder()
        assertThat(walk("alpha", ChannelList(Order.PROVIDER, null), false)).containsExactly("alpha", "bravo", "Alpha", "delta", "charlie").inOrder()
        assertThat(walk("charlie", ChannelList(Order.NAME, null), true)).containsExactly("charlie", "Alpha", "alpha", "bravo", "delta").inOrder()
        assertThat(walk("delta", ChannelList(Order.NAME, null), false)).containsExactly("delta", "bravo", "alpha", "Alpha", "charlie").inOrder()

        edit("delta") { it.copy(pinned = true) } // two pins keep provider order among themselves
        assertThat(walk("delta", ChannelList(Order.PROVIDER, null), true)).containsExactly("delta", "charlie", "Alpha", "bravo", "alpha").inOrder()
    }

    @Test
    fun `restore all shows hidden channels again and keeps renames and pins`(): Unit = runBlocking {
        edit("bravo") { it.copy(hidden = true, customName = "B") }
        edit("delta") { it.copy(hidden = true) }
        edit("alpha") { it.copy(pinned = true) }
        assertThat(prefs.hiddenCount(true).first()).isEqualTo(2)

        prefs.restoreAll()

        assertThat(listed()).containsExactly("alpha", "delta", "Alpha", "charlie", "B").inOrder()
        assertThat(prefs.all().map { it.stableKey }).containsExactly("bravo", "alpha") // delta's row had nothing left
        assertThat(prefs.hiddenCount(true).first()).isEqualTo(0)
    }

    @Test
    fun `preferences go with their playlist`(): Unit = runBlocking {
        edit("bravo") { it.copy(hidden = true) }
        db.sources().delete(source)
        assertThat(prefs.all()).isEmpty()
    }

    @Test
    fun `visible_items has every column of items, so queries can map it onto ItemEntity`() {
        fun columns(table: String) = db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toList()
        }
        assertThat(columns("visible_items")).containsAtLeastElementsIn(columns("items"))
        assertThat(columns("visible_items") - columns("items").toSet()).containsExactly("pinned", "isLocked")
    }

    @Test
    fun `migration 3 to 5 keeps the channels and adds preferences and locks`() {
        createAt(3) { old ->
            sql(
                old,
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 2, 0, 0)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'a', 'Alpha', 'News', 0)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'b', 'Bravo', 'News', 1)",
                "INSERT INTO items_fts(items_fts) VALUES('rebuild')", // the fixture has no sync triggers
            )
        }
        val migrated = openCurrent()
        try {
            suspend fun names(): List<String> {
                val page = migrated.items().page(1, ItemKind.LIVE, null, false, true).load(PagingSource.LoadParams.Refresh(null, 10, false))
                return (page as PagingSource.LoadResult.Page).data.map { it.name }
            }
            runBlocking {
                assertThat(names()).containsExactly("Alpha", "Bravo").inOrder()
                assertThat(migrated.items().search(ItemDao.ftsQuery("bra")!!, "", 10, true).first().map { it.name }).containsExactly("Bravo")
                migrated.channelPrefs().edit(1, "a") { it.copy(hidden = true) }
                migrated.channelPrefs().edit(1, "b") { it.copy(customName = "Beta") }
                assertThat(names()).containsExactly("Beta")
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `migration 4 to 5 keeps hidden and renamed channels and adds locked groups`() {
        createAt(4) { old ->
            sql(
                old,
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 3, 0, 0)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'a', 'Alpha', 'News', 0)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'b', 'Bravo', 'Adult', 1)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'c', 'Charlie', 'News', 2)",
                "INSERT INTO channel_prefs (sourceId, stableKey, hidden, customName, pinned) VALUES (1, 'c', 1, NULL, 0)",
                "INSERT INTO channel_prefs (sourceId, stableKey, hidden, customName, pinned) VALUES (1, 'a', 0, 'Alfa', 0)",
            )
        }
        val migrated = openCurrent()
        try {
            suspend fun names(unlocked: Boolean): List<String> {
                val page = migrated.items().page(1, ItemKind.LIVE, null, false, unlocked).load(PagingSource.LoadParams.Refresh(null, 10, false))
                return (page as PagingSource.LoadResult.Page).data.map { it.name }
            }
            runBlocking {
                assertThat(names(false)).containsExactly("Alfa", "Bravo").inOrder() // Charlie is still hidden
                migrated.lockedGroups().add(LockedGroupEntity(1, "Adult"))
                assertThat(names(false)).containsExactly("Alfa")
                assertThat(names(true)).containsExactly("Alfa", "Bravo").inOrder()
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `migration 5 to 6 keeps the channels and adds recordings`() {
        createAt(5) { old ->
            sql(
                old,
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 1, 0, 0)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'a', 'Alpha', 'News', 0)",
            )
        }
        val migrated = openCurrent()
        try {
            runBlocking {
                assertThat(migrated.items().byKey(1, "a", true)!!.name).isEqualTo("Alpha")
                val id = migrated.recordings().insert(RecordingEntity(sourceId = 1, stableKey = "a", channelName = "Alpha", title = "Show", startAt = 1, endAt = 2, state = RecordingState.SCHEDULED))
                assertThat(migrated.recordings().get(id)!!.title).isEqualTo("Show")
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `migration 6 to 7 keeps recordings and gives them no padding`() {
        createAt(6) { old ->
            sql(
                old,
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 0, 0, 0)",
                "INSERT INTO recordings (id, sourceId, stableKey, channelName, title, startAt, endAt, state, bytes) VALUES (1, 1, 'a', 'Alpha', 'Show', 100, 200, 'SCHEDULED', 0)",
            )
        }
        val migrated = openCurrent()
        try {
            runBlocking {
                val r = migrated.recordings().get(1)!!
                assertThat(r.title).isEqualTo("Show")
                assertThat(r.leadSeconds).isEqualTo(0)
                assertThat(r.trailSeconds).isEqualTo(0)
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `migration 7 to 8 keeps the channels and adds saved servers that follow their playlists`() {
        createAt(7) { old ->
            sql(
                old,
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 1, 0, 0)",
                "INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (2, 'T', 'M3U_URL', 'y', 'l', 0, 0, 0, 0, 0)",
                "INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'a', 'Alpha', 'News', 0)",
            )
        }
        val migrated = openCurrent()
        try {
            runBlocking {
                assertThat(migrated.items().byKey(1, "a", true)!!.name).isEqualTo("Alpha")
                migrated.channelServers().add(1, "a", 2, "b")
                assertThat(migrated.channelServers().all()).hasSize(1)
                migrated.sources().delete(2)
                assertThat(migrated.channelServers().all()).isEmpty() // the server's playlist is gone
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
