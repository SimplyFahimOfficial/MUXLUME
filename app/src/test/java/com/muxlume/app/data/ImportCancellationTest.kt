package com.muxlume.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.HeldBody
import com.muxlume.app.PlainBox
import com.muxlume.app.core.net.Http
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.epg.EpgLinker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A playlist import cancelled while its response body is paused: the call is closed at once, what was staged is dropped, the last
 * successful catalogue and its time stay, no error is recorded for the provider, and other sources are not held up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ImportCancellationTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun repo(client: OkHttpClient) = SourceRepository(db, client, PlainBox(), context.contentResolver, context.resources, EpgLinker(db))

    private fun route(vararg routes: Pair<String, (RecordedRequest) -> String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = routes.firstOrNull { it.first == request.url.encodedPath }?.second?.invoke(request) ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
    }

    private fun m3u(prefix: String, n: Int) = "#EXTM3U\n" + (0 until n).joinToString("") { "#EXTINF:-1 group-title=\"G\",$prefix$it\nhttps://cdn.example/$prefix$it.m3u8\n" }

    private fun names(id: Long): List<String> =
        db.openHelper.readableDatabase.query("SELECT name FROM items WHERE sourceId = ? AND pending = 0 ORDER BY position", arrayOf<Any>(id)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun staged() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items WHERE pending = 1").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun `cancelling an M3U import while its body is paused closes the call, keeps the catalogue and records no error`(): Unit = runBlocking {
        var served = m3u("old", 3)
        route("/a.m3u" to { served }, "/b.m3u" to { m3u("b", 2) })
        val plain = repo(Http.client())
        val a = plain.save(SourceInput.M3uUrl("A", server.url("/a.m3u").toString(), true))
        val b = plain.save(SourceInput.M3uUrl("B", server.url("/b.m3u").toString(), true))
        plain.refresh(a)
        val before = plain.get(a)!!
        val key = db.openHelper.readableDatabase.query("SELECT stableKey FROM items WHERE sourceId = ? AND name = 'old0'", arrayOf<Any>(a)).use { it.moveToFirst(); it.getString(0) }
        db.favorites().add(FavoriteEntity(a, key, 1))

        // The next download of A is 3,000 entries; its body is paused after the first batch (1,000 rows) is staged.
        served = m3u("new", 3_000)
        val held = HeldBody(after = m3u("new", 1_200).length.toLong())
        val repo = repo(held.on(Http.client()) { it.url.encodedPath == "/a.m3u" })
        val running = async(Dispatchers.IO) { repo.refresh(a) }
        withContext(Dispatchers.IO) { assertThat(held.reached.await(10, TimeUnit.SECONDS)).isTrue() }
        assertThat(staged()).isGreaterThan(0) // staging is under way
        assertThat(names(a)).containsExactly("old0", "old1", "old2") // and invisible

        withTimeout(10_000) { running.cancelAndJoin() } // released at once, not when the 20 s hold gives up
        assertThrows(CancellationException::class.java) { runBlocking { running.await() } }

        assertThat(staged()).isEqualTo(0)
        assertThat(names(a)).containsExactly("old0", "old1", "old2").inOrder()
        val after = plain.get(a)!!
        assertThat(after.refreshedAt).isEqualTo(before.refreshedAt)
        assertThat(after.liveCount).isEqualTo(3)
        assertThat(after.lastError).isNull() // giving up is not a failure of the provider
        assertThat(db.favorites().all().map { it.stableKey }).containsExactly(key)

        // The source is free again, and so is another one.
        withTimeout(10_000) { plain.refresh(b) }
        assertThat(names(b)).containsExactly("b0", "b1")
        withTimeout(10_000) { plain.refresh(a) }
        assertThat(names(a)).hasSize(3_000)
        assertThat(plain.get(a)!!.lastError).isNull()
    }

    @Test
    fun `cancelling an Xtream import while a list is paused closes the call, keeps the catalogue and records no error`(): Unit = runBlocking {
        var live = """[{"stream_id":1,"name":"old","category_id":"1"}]"""
        route("/player_api.php" to { r ->
            when (r.url.queryParameter("action")) {
                null -> """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]}}"""
                "get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
                "get_live_streams" -> live
                "get_series" -> """[]"""
                else -> "[]"
            }
        })
        val plain = repo(Http.client())
        val x = plain.save(SourceInput.Xtream("X", server.url("/").toString(), "u", "synthetic-pass", allowHttp = true))
        plain.refresh(x)
        val before = plain.get(x)!!
        assertThat(names(x)).containsExactly("old")

        live = "[" + (0 until 4_000).joinToString(",") { """{"stream_id":${it + 10},"name":"new$it","category_id":"1"}""" } + "]"
        val held = HeldBody(after = 100_000)
        val repo = repo(held.on(Http.client()) { it.url.queryParameter("action") == "get_live_streams" })
        val running = async(Dispatchers.IO) { repo.refresh(x) }
        withContext(Dispatchers.IO) { assertThat(held.reached.await(10, TimeUnit.SECONDS)).isTrue() }
        assertThat(staged()).isGreaterThan(0)

        withTimeout(10_000) { running.cancelAndJoin() }

        assertThat(staged()).isEqualTo(0)
        assertThat(names(x)).containsExactly("old")
        assertThat(plain.get(x)!!.refreshedAt).isEqualTo(before.refreshedAt)
        assertThat(plain.get(x)!!.lastError).isNull()
        withTimeout(10_000) { plain.refresh(x) }
        assertThat(names(x)).hasSize(4_000)
    }
}
