package com.muxlume.app.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.PlainBox
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.LockedGroupEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.epg.EpgLinker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class StreamResolverTest {
    private lateinit var db: MuxlumeDatabase
    private lateinit var resolver: StreamResolver
    private lateinit var sources: SourceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        sources = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver, context.resources, EpgLinker(db))
        resolver = StreamResolver(db, sources)
    }

    @After fun tearDown() = db.close()

    private suspend fun m3u(allowHttp: Boolean = false) = sources.save(SourceInput.M3uUrl("M", "https://example.com/list.m3u", allowHttp, null))
    private suspend fun xtream() = sources.save(SourceInput.Xtream("X", "https://p.example:8443", "me", "s3cret", allowHttp = false))

    private fun item(source: Long, key: String, group: String = "News", kind: ItemKind = ItemKind.LIVE, url: String? = null, remoteId: String? = null, headers: String? = null) =
        ItemEntity(sourceId = source, kind = kind, stableKey = key, name = key, groupTitle = group, logo = null, url = url, remoteId = remoteId, headers = headers, extension = "mkv", position = 0)

    @Test
    fun `a playlist channel resolves with its headers and the source's HTTP setting, and never prints its URL`(): Unit = runBlocking {
        val s = m3u()
        db.items().insertAll(listOf(item(s, "a", url = "https://cdn.example/a.m3u8?token=T", headers = "User-Agent\tTester\nReferer\thttps://r.example/")))
        val r = resolver.resolve(s, "a", null, unlocked = false)!!
        assertThat(r.url).isEqualTo("https://cdn.example/a.m3u8?token=T")
        assertThat(r.headers).containsExactly("User-Agent", "Tester", "Referer", "https://r.example/")
        assertThat(r.policy).isEqualTo(HttpPolicy.HTTPS_ONLY)
        assertThat(r.toString()).doesNotContain("cdn.example")
    }

    @Test
    fun `RTSP only plays for a source that allows HTTP, and unplayable or missing items do not resolve`(): Unit = runBlocking {
        val strict = m3u()
        val relaxed = m3u(allowHttp = true).let { sources.save(SourceInput.M3uUrl("R", "http://example.com/other.m3u", true, null)) }
        db.items().insertAll(listOf(item(strict, "r", url = "rtsp://cam.example/1"), item(relaxed, "r", url = "rtsp://cam.example/1"), item(strict, "f", url = "file:///x")))
        assertThat(resolver.resolve(strict, "r", null, true)).isNull()
        assertThat(resolver.resolve(relaxed, "r", null, true)).isNotNull()
        assertThat(resolver.resolve(strict, "f", null, true)).isNull()
        assertThat(resolver.resolve(strict, "nope", null, true)).isNull()
        assertThat(resolver.resolve(999, "r", null, true)).isNull()
    }

    @Test
    fun `a channel in a locked group or hidden by the user does not resolve`(): Unit = runBlocking {
        val s = m3u()
        db.items().insertAll(listOf(item(s, "adult", group = "Adult", url = "https://x/a"), item(s, "hidden", url = "https://x/h")))
        db.lockedGroups().add(LockedGroupEntity(s, "Adult"))
        db.channelPrefs().edit(s, "hidden") { it.copy(hidden = true) }
        assertThat(resolver.resolve(s, "adult", null, unlocked = false)).isNull()
        assertThat(resolver.resolve(s, "adult", null, unlocked = true)).isNotNull()
        assertThat(resolver.resolve(s, "hidden", null, unlocked = true)).isNull()
    }

    @Test
    fun `Xtream builds live, movie and episode URLs, an episode only with a visible series`(): Unit = runBlocking {
        val s = xtream()
        db.items().insertAll(
            listOf(
                item(s, "live:1", kind = ItemKind.LIVE, remoteId = "1"), item(s, "movie:2", kind = ItemKind.MOVIE, remoteId = "2"),
                item(s, "series:3", group = "Adult", kind = ItemKind.SERIES, remoteId = "3"),
            ),
        )
        db.lockedGroups().add(LockedGroupEntity(s, "Adult"))
        assertThat(resolver.resolve(s, "live:1", null, false)!!.url).contains("/me/s3cret/1")
        assertThat(resolver.resolve(s, "movie:2", null, false)!!.url).endsWith("/2.mkv")

        val episode = MediaIds.episodeKey("77", "mp4")
        assertThat(resolver.resolve(s, episode, "series:3", unlocked = true)!!.url).contains("/series/me/s3cret/77.mp4")
        assertThat(resolver.resolve(s, episode, "series:3", unlocked = false)).isNull() // as locked as its series
        assertThat(resolver.resolve(s, episode, null, unlocked = true)).isNull() // no series given
        assertThat(resolver.resolve(m3u(), episode, "series:3", unlocked = true)).isNull() // only Xtream has episodes
    }
}
