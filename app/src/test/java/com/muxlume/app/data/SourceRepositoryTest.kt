package com.muxlume.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.core.net.CleartextGate
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.net.HttpStatusException
import com.muxlume.app.core.security.SecretBox
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.ItemDao
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

/** Robolectric has no AndroidKeyStore; the storage format is what's under test here, not the cipher. */
private class PlainBox : SecretBox() {
    override fun seal(plain: String): String = Base64.getEncoder().encodeToString(plain.toByteArray())
    override fun open(sealed: String): String = String(Base64.getDecoder().decode(sealed))
}

@RunWith(AndroidJUnit4::class)
// Plain Application: MuxlumeApp would start its own database and observers during the test.
@Config(sdk = [35], application = android.app.Application::class)
class SourceRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var repo: SourceRepository

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        repo = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver)
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
        CleartextGate.artworkAllowed = false
    }

    private fun playlist(vararg channels: String) = buildString {
        append("#EXTM3U\n")
        channels.forEach { append("#EXTINF:-1 group-title=\"G\",$it\nhttps://cdn.example/$it.m3u8\n") }
    }

    private fun serve(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    // MockWebServer is plain HTTP, so test sources allow HTTP unless a test says otherwise.
    private suspend fun addM3u(allowHttp: Boolean = true): Long =
        repo.save(SourceInput.M3uUrl("Test", server.url("/list.m3u").toString(), allowHttp))

    /** Visible live channel names of a source, in provider order. */
    private fun names(id: Long): List<String> =
        db.openHelper.readableDatabase.query("SELECT name FROM items WHERE sourceId = ? AND kind = 'LIVE' AND pending = 0 ORDER BY position", arrayOf<Any>(id))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun key(id: Long, name: String): String =
        db.openHelper.readableDatabase.query("SELECT stableKey FROM items WHERE sourceId = ? AND name = ? AND pending = 0", arrayOf<Any>(id, name))
            .use { c -> c.moveToFirst(); c.getString(0) }

    @Test
    fun `import stores channels, merges duplicates and never stores the full url as label`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b", "a") + "#EXTINF:-1,Movie\nhttps://cdn.example/movie/u/p/9.mp4\n")
        val result = repo.refresh(id)
        assertThat(result.live).isEqualTo(2)
        assertThat(result.movies).isEqualTo(1)
        assertThat(result.duplicates).isEqualTo(1)
        val source = repo.get(id)!!
        assertThat(source.liveCount).isEqualTo(2)
        assertThat(source.label).doesNotContain("list.m3u")
        assertThat(source.secret).doesNotContain("cdn.example")
        assertThat(names(id)).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `favorites survive a refresh, failed refresh keeps previous items`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b"))
        repo.refresh(id)
        db.favorites().add(FavoriteEntity(id, key(id, "a"), 1))

        serve(playlist("b", "a", "c"))
        repo.refresh(id)
        assertThat(db.items().favorites(ItemKind.LIVE, id).first().map { it.name }).containsExactly("a")

        serve("", code = 500)
        assertThrows(HttpStatusException::class.java) { runBlocking { repo.refresh(id) } }
        assertThat(names(id)).hasSize(3)
        assertThat(repo.get(id)!!.lastError).contains("500")
    }

    @Test
    fun `cancelled import rolls back and leaves no staged rows`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("old"))
        repo.refresh(id)
        serve(playlist(*Array(5_000) { "n$it" }))
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.refresh(id) { n -> if (n >= 1_000) throw CancellationException("user cancelled") } }
        }
        assertThat(names(id)).containsExactly("old")
        val staged = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items WHERE pending = 1").use { it.moveToFirst(); it.getInt(0) }
        assertThat(staged).isEqualTo(0)
    }

    @Test
    fun `a running import is invisible and doesn't block other writes`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("old"))
        repo.refresh(id)
        serve(playlist(*Array(3_000) { "n$it" }))
        var checked = false
        repo.refresh(id) { n ->
            if (n in 1_000..1_999 && !checked) {
                checked = true
                assertThat(names(id)).containsExactly("old") // staged rows aren't shown yet
                // Another coroutine can write while the import runs (a long write transaction would block this).
                runBlocking(Dispatchers.IO) { withTimeout(5_000) { db.favorites().add(FavoriteEntity(id, key(id, "old"), 1)) } }
                runBlocking(Dispatchers.IO) { withTimeout(5_000) { repo.rename(id, "Renamed") } }
            }
        }
        assertThat(checked).isTrue()
        assertThat(names(id)).hasSize(3_000)
        assertThat(repo.get(id)!!.name).isEqualTo("Renamed") // the final update doesn't undo a rename made meanwhile
    }

    @Test
    fun `one source's HTTP opt-in doesn't apply to another source`(): Unit = runBlocking {
        addM3u(allowHttp = true)
        CleartextGate.artworkAllowed = true // as the app does once any source allows HTTP
        val strict = addM3u(allowHttp = false)
        assertThrows(CleartextBlockedException::class.java) { runBlocking { repo.refresh(strict) } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `deleting a source removes its items and favorites`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a"))
        repo.refresh(id)
        db.favorites().add(FavoriteEntity(id, key(id, "a"), 1))
        repo.delete(id)
        assertThat(db.items().favorites(ItemKind.LIVE, null).first()).isEmpty()
        assertThat(db.items().search(ItemDao.ftsQuery("a")!!, 10).first()).isEmpty()
    }

    @Test
    fun `full-text search finds word prefixes and ignores staged rows`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("BBC One HD", "Sky News", "Cartoon Network"))
        repo.refresh(id)
        fun find(q: String) = runBlocking { db.items().search(ItemDao.ftsQuery(q)!!, 10).first().map { it.name } }
        assertThat(find("bbc on")).containsExactly("BBC One HD")
        assertThat(find("NEW")).containsExactly("Sky News")
        assertThat(find("net")).containsExactly("Cartoon Network")
        assertThat(find("xyz")).isEmpty()

        serve(playlist(*Array(2_000) { "Staged $it" }))
        repo.refresh(id) { n -> if (n == 1_000) assertThat(find("staged")).isEmpty() }
        assertThat(find("sky")).isEmpty() // replaced by the new list
        assertThat(find("staged 1999")).containsExactly("Staged 1999")
    }

    @Test
    fun `xtream import uses stream ids as stable keys and keeps credentials out of items`(): Unit = runBlocking {
        val id = repo.save(SourceInput.Xtream("X", server.url("/").toString(), "u", "secret-pass", allowHttp = true))
        serve("""{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]}}""")
        serve("""[{"category_id":"1","category_name":"News"}]""")
        serve("""[{"stream_id":10,"name":"N1","category_id":"1","stream_icon":"https://img/x.png","epg_channel_id":"n1"}]""")
        serve("[]")
        serve("""[{"stream_id":"20","name":"M","container_extension":"mkv","added":"100"}]""")
        serve("[]")
        serve("""[{"series_id":30,"name":"S","cover":"https://img/s.png"}]""")
        val r = repo.refresh(id)
        assertThat(listOf(r.live, r.movies, r.series)).containsExactly(1, 1, 1).inOrder()
        val live = db.items().byKey(id, "live:10")!!
        assertThat(live.groupTitle).isEqualTo("News")
        assertThat(live.url).isNull()
        assertThat(repo.get(id)!!.liveExtension).isEqualTo("ts")
        assertThat(db.items().recentlyAdded(ItemKind.MOVIE, 5).first().single().extension).isEqualTo("mkv")
    }
}
