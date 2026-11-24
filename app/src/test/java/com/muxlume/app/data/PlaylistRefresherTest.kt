package com.muxlume.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.PlainBox
import com.muxlume.app.core.net.Http
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.epg.EpgLinker
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaylistRefresherTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var repo: SourceRepository
    private val hour = TimeUnit.HOURS.toMillis(1)
    private var now = 1_000 * hour

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        repo = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver, context.resources, EpgLinker(db)) { now }
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun playlist(vararg names: String) = names.joinToString("\n", prefix = "#EXTM3U\n") { "#EXTINF:-1 group-title=\"G\",$it\nhttps://cdn.example/$it.m3u8" }

    private fun serve(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private suspend fun add(name: String, refreshedHoursAgo: Long?): Long {
        val id = repo.save(SourceInput.M3uUrl(name, server.url("/$name.m3u").toString(), allowHttp = true))
        db.sources().update(db.sources().get(id)!!.copy(refreshedAt = refreshedHoursAgo?.let { now - it * hour }))
        return id
    }

    private fun source(refreshedAt: Long?) = SourceEntity(name = "s", type = SourceType.M3U_URL, secret = "", label = "", allowHttp = false, createdAt = 0, refreshedAt = refreshedAt)

    @Test
    fun `a playlist is due at 90 percent of the interval, never when off, always when never imported`() {
        fun due(hoursAgo: Long?, every: PlaylistRefresh) = PlaylistRefresher.isDue(source(hoursAgo?.let { now - it * hour }), every, now)
        assertThat(due(21, PlaylistRefresh.DAILY)).isFalse() // 21 h < 21.6 h
        assertThat(due(22, PlaylistRefresh.DAILY)).isTrue()
        assertThat(due(60, PlaylistRefresh.EVERY_3_DAYS)).isFalse()
        assertThat(due(65, PlaylistRefresh.EVERY_3_DAYS)).isTrue()
        assertThat(due(151, PlaylistRefresh.WEEKLY)).isFalse()
        assertThat(due(152, PlaylistRefresh.WEEKLY)).isTrue()
        assertThat(due(10_000, PlaylistRefresh.OFF)).isFalse()
        assertThat(due(null, PlaylistRefresh.WEEKLY)).isTrue()
    }

    @Test
    fun `only due playlists are downloaded and one failing playlist does not stop the others`(): Unit = runBlocking {
        val fresh = add("fresh", 1)
        val broken = add("broken", 30)
        val stale = add("stale", 30)
        // Requests come in source order: broken, then stale ("fresh" is not due).
        server.enqueue(MockResponse.Builder().code(500).build())
        serve(playlist("a", "b"))

        val done = PlaylistRefresher(repo) { now }.run(PlaylistRefresh.DAILY)

        assertThat(done).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(repo.get(fresh)!!.liveCount).isEqualTo(0)
        assertThat(repo.get(broken)!!.lastError).isNotNull()
        assertThat(repo.get(stale)!!.liveCount).isEqualTo(2)
        assertThat(repo.get(stale)!!.refreshedAt).isEqualTo(now)
    }

    @Test
    fun `off downloads nothing`(): Unit = runBlocking {
        add("a", 1000)
        assertThat(PlaylistRefresher(repo) { now }.run(PlaylistRefresh.OFF)).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `two refreshes of the same playlist at once download it once`(): Unit = runBlocking {
        val id = add("a", null)
        server.enqueue(MockResponse.Builder().body(playlist("a", "b")).bodyDelay(600, TimeUnit.MILLISECONDS).build())

        val first = async { repo.refresh(id) }
        delay(200) // the first is now holding the lock, waiting for the slow body
        val second = async { repo.refresh(id) }

        assertThat(first.await().live).isEqualTo(2)
        assertThat(second.await().live).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
