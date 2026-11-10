package com.muxlume.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.CleartextGate
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.net.HttpStatusException
import com.muxlume.app.core.security.SecretBox
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
// Plain Application: MuxlumeApp would sync CleartextGate from its own (empty) database and close it mid-test.
@Config(sdk = [35], application = android.app.Application::class)
class SourceRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var repo: SourceRepository

    @Before
    fun setUp() {
        CleartextGate.allowed = true
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        repo = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver)
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
        CleartextGate.allowed = false
    }

    private fun playlist(vararg channels: String) = buildString {
        append("#EXTM3U\n")
        channels.forEach { append("#EXTINF:-1 group-title=\"G\",$it\nhttps://cdn.example/$it.m3u8\n") }
    }

    private fun serve(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private suspend fun addM3u(): Long = repo.save(SourceInput.M3uUrl("Test", server.url("/list.m3u").toString(), allowHttp = true))

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
        assertThat(db.items().list(id, ItemKind.LIVE, null, false).first().map { it.name }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `favorites survive a refresh, failed refresh keeps previous items`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b"))
        repo.refresh(id)
        val a = db.items().list(id, ItemKind.LIVE, null, false).first().first { it.name == "a" }
        db.favorites().add(FavoriteEntity(id, a.stableKey, 1))

        serve(playlist("b", "a", "c"))
        repo.refresh(id)
        assertThat(db.items().favorites(ItemKind.LIVE, id).first().map { it.name }).containsExactly("a")

        serve("", code = 500)
        assertThrows(HttpStatusException::class.java) { runBlocking { repo.refresh(id) } }
        assertThat(db.items().list(id, ItemKind.LIVE, null, false).first()).hasSize(3)
        assertThat(repo.get(id)!!.lastError).contains("500")
    }

    @Test
    fun `cancelled import rolls back`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("old"))
        repo.refresh(id)
        serve(playlist(*Array(5_000) { "n$it" }))
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.refresh(id) { n -> if (n >= 1_000) throw CancellationException("user cancelled") } }
        }
        assertThat(db.items().list(id, ItemKind.LIVE, null, false).first().map { it.name }).containsExactly("old")
    }

    @Test
    fun `deleting a source removes its items and favorites`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a"))
        repo.refresh(id)
        val key = db.items().list(id, ItemKind.LIVE, null, false).first().single().stableKey
        db.favorites().add(FavoriteEntity(id, key, 1))
        repo.delete(id)
        assertThat(db.items().favorites(ItemKind.LIVE, null).first()).isEmpty()
        assertThat(db.items().search("a", 10).first()).isEmpty()
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
        val live = db.items().list(id, ItemKind.LIVE, null, false).first().single()
        assertThat(live.stableKey).isEqualTo("live:10")
        assertThat(live.groupTitle).isEqualTo("News")
        assertThat(live.url).isNull()
        assertThat(repo.get(id)!!.liveExtension).isEqualTo("ts")
        assertThat(db.items().recentlyAdded(ItemKind.MOVIE, 5).first().single().extension).isEqualTo("mkv")
    }
}
