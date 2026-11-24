package com.muxlume.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.drawable.ColorDrawable
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.ALLOW_HTTP_HEADER
import com.muxlume.app.core.net.Http
import com.muxlume.app.ui.components.artworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Provider artwork through the real image request and client: the source that owns a logo decides whether it may be fetched over plain
 * HTTP, and nothing else does (not another playlist that allows HTTP, not an unknown owner).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArtworkRequestTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val held = CountDownLatch(1)
    private val reached = CountDownLatch(1)
    private lateinit var context: Context
    private lateinit var loader: ImageLoader

    private val png: ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (request.url.encodedPath == "/held.png") {
                    reached.countDown()
                    held.await(10, TimeUnit.SECONDS)
                }
                return MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(png)).build()
            }
        }
        server.start()
        // Robolectric cannot decode a bitmap here; what is under test is which requests are made, so a fetched image decodes to a stand-in.
        val decoder = Decoder.Factory { _, _, _ -> Decoder { DecodeResult(ColorDrawable(0).asImage(), isSampled = false) } }
        loader = ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client() })); add(decoder) }.build()
    }

    @After
    fun tearDown() {
        held.countDown()
        server.close()
    }

    private fun load(path: String, allowHttp: Boolean) = runBlocking { loader.execute(artworkRequest(context, server.url(path).toString(), allowHttp)) }

    @Test
    fun `a source that allows HTTP loads its own http artwork, and the marker never reaches the server`() {
        assertThat(load("/a.png", allowHttp = true)).isInstanceOf(SuccessResult::class.java)
        assertThat(seen.map { it.url.encodedPath }).containsExactly("/a.png")
        assertThat(seen.single().headers[ALLOW_HTTP_HEADER]).isNull()
    }

    @Test
    fun `a source that does not allow HTTP sends nothing for an artwork that is not cached, whatever another source allows`() {
        assertThat(load("/a.png", allowHttp = true)).isInstanceOf(SuccessResult::class.java) // source A
        val before = server.requestCount
        val result = load("/b-only.png", allowHttp = false) // source B, another logo, a cache miss
        assertThat(result).isInstanceOf(ErrorResult::class.java)
        assertThat(server.requestCount).isEqualTo(before)
        assertThat(seen.map { it.url.encodedPath }).doesNotContain("/b-only.png")
    }

    @Test
    fun `the marker never overrides the setting a request already carries`() {
        val strict = okhttp3.Request.Builder().url(server.url("/strict.png")).header(ALLOW_HTTP_HEADER, "1").tag(com.muxlume.app.core.net.HttpPolicy::class.java, com.muxlume.app.core.net.HttpPolicy.HTTPS_ONLY).build()
        assertThrows(com.muxlume.app.core.net.CleartextBlockedException::class.java) { Http.client().newCall(strict).execute() }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `two playlists with the same image URL each follow their own setting, and a cached image is kept`() {
        // B first, on its own: a miss it may not fetch.
        assertThat(load("/same.png", allowHttp = false)).isInstanceOf(ErrorResult::class.java)
        assertThat(server.requestCount).isEqualTo(0)
        // A fetches it once.
        assertThat(load("/same.png", allowHttp = true)).isInstanceOf(SuccessResult::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        // Now B finds it cached: shown, with no request. A cached image is not taken away.
        assertThat(load("/same.png", allowHttp = false)).isInstanceOf(SuccessResult::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an artwork request of one source running while another source's is made keeps its own setting`(): Unit = runBlocking {
        val running = async(Dispatchers.IO) { loader.execute(artworkRequest(context, server.url("/held.png").toString(), allowHttp = true)) }
        withContext(Dispatchers.IO) { assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue() } // A's request is at the server
        // The user switches to a channel of source B meanwhile: its artwork request is refused at once.
        val other = loader.execute(artworkRequest(context, server.url("/held.png?b").toString(), allowHttp = false))
        assertThat(other).isInstanceOf(ErrorResult::class.java)
        held.countDown()
        assertThat(running.await()).isInstanceOf(SuccessResult::class.java)
        assertThat(seen.map { it.url.toString() }).containsExactly(server.url("/held.png").toString())
    }
}
