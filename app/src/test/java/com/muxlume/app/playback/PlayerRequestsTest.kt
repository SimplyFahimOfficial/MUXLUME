package com.muxlume.app.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.net.HttpPolicy
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A real player, switching between items of two playlists on two servers: each server only ever sees its own item's headers. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlayerRequestsTest {
    private class Origin {
        val server = MockWebServer()
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        val first = CountDownLatch(1)

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    first.countDown()
                    return MockResponse.Builder().addHeader("Content-Type", "video/mp2t").body(Buffer().write(ByteArray(2_000))).build()
                }
            }
            server.start()
        }
    }

    private val a = Origin()
    private val b = Origin()
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requests = StreamRequests(Http.client())
        val contexts = mapOf(
            "1|a" to (mapOf("User-Agent" to "ua-of-a", "Referer" to "https://a.test/page") to HttpPolicy.ALLOW_HTTP.scopedTo(a.server.url("/"))),
            "2|b" to (mapOf("User-Agent" to "ua-of-b", "Referer" to "https://b.test/page") to HttpPolicy.ALLOW_HTTP.scopedTo(b.server.url("/"))),
        )
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(ItemMediaSourceFactory(context) { item -> contexts.getValue(item.mediaId).let { (headers, policy) -> requests.factoryFor(headers, policy) } })
            .build()
    }

    @After
    fun tearDown() {
        player.release()
        a.server.close()
        b.server.close()
    }

    private fun item(id: String, origin: Origin) = MediaItem.Builder().setMediaId(id).setUri(origin.server.url("/live.ts").toString()).build()

    @Test
    fun `switching from one playlist's item to another's, each server sees only its own item's headers`() {
        player.setMediaItem(item("1|a", a))
        player.prepare()
        assertThat(a.first.await(15, TimeUnit.SECONDS)).isTrue()

        player.setMediaItem(item("2|b", b)) // the user zaps while A's stream is loading or retrying
        player.prepare()
        assertThat(b.first.await(15, TimeUnit.SECONDS)).isTrue()

        assertThat(a.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly("ua-of-a")
        assertThat(a.seen.map { it.headers["Referer"] }.toSet()).containsExactly("https://a.test/page")
        assertThat(b.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly("ua-of-b")
        assertThat(b.seen.map { it.headers["Referer"] }.toSet()).containsExactly("https://b.test/page")
    }
}
