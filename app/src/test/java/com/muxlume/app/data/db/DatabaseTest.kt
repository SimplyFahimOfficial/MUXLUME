package com.muxlume.app.data.db

import android.content.Context
import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.playback.ChannelList.Order
import com.muxlume.app.playback.neighbour
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

    private suspend fun item(name: String) = db.items().byKey(source, name)!!

    /** Walks the whole list from [start] in one direction. */
    private suspend fun walk(start: String, list: ChannelList, forward: Boolean): List<String> {
        val out = mutableListOf(start)
        var cur = item(start)
        while (true) cur = db.neighbour(cur, list, forward)?.also { out += it.name } ?: return out
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

    /**
     * Builds a real version-1 database from its exported schema (app/schemas/.../1.json), as Daysemble's
     * MigrationTest does, then opens it with the current database: Room runs the auto-migration and
     * validates the result against the v2 schema, failing on any mismatch.
     */
    @Test
    fun `migration 1 to 2 keeps rows, adds pending and indexes existing names for search`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.deleteDatabase(DB)
        val schema = Json.parseToJsonElement(File("schemas/com.muxlume.app.data.db.MuxlumeDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(DB).apply { parentFile?.mkdirs() }, null).use { v1 ->
            for (e in schema.getValue("entities").jsonArray) {
                val table = e.jsonObject.getValue("tableName").jsonPrimitive.content
                v1.execSQL(e.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach { v1.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { v1.execSQL(it.jsonPrimitive.content) }
            v1.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema.getValue("identityHash").jsonPrimitive.content}')")
            v1.execSQL("INSERT INTO sources (id, name, type, secret, label, allowHttp, createdAt, liveCount, movieCount, seriesCount) VALUES (1, 'S', 'M3U_URL', 'x', 'l', 0, 0, 1, 0, 0)")
            v1.execSQL("INSERT INTO items (sourceId, kind, stableKey, name, groupTitle, position) VALUES (1, 'LIVE', 'k', 'Sky News HD', 'News', 0)")
            v1.version = 1
        }

        val migrated = Room.databaseBuilder(app, MuxlumeDatabase::class.java, DB).allowMainThreadQueries().build()
        try {
            val found = runBlocking { migrated.items().search(ItemDao.ftsQuery("news")!!, 10).first() }
            assertThat(found.map { it.name }).containsExactly("Sky News HD")
            assertThat(found.single().pending).isFalse()
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
