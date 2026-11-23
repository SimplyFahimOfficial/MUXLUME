package com.muxlume.app.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.core.net.Http
import com.muxlume.app.data.db.ItemKind
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Notification artwork through the real loader the playback service uses: the item's source decides, and a bare URI is https only. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArtworkLoaderTest {
    private val server = MockWebServer()
    private lateinit var loader: ArtworkLoader

    private val png: ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        server.start()
        loader = ArtworkLoader(Http.client()) { DataSourceBitmapLoader.Builder(context).setDataSourceFactory(it).build() }
    }

    @After fun tearDown() = server.close()

    private fun item(allowHttp: Boolean?): MediaMetadata {
        val base = MediaItem.Builder().setMediaId("1|k").setMediaMetadata(MediaMetadata.Builder().setArtworkUri(Uri.parse(server.url("/logo.png").toString())).setExtras(Bundle()).build()).build()
        return (allowHttp?.let { base.withArtworkPolicy(it) } ?: base).mediaMetadata
    }

    private fun load(metadata: MediaMetadata) = loader.loadBitmapFromMetadata(metadata)!!.get(10, TimeUnit.SECONDS)

    @Test
    fun `artwork of an item whose source allows HTTP is loaded`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build())
        assertThat(load(item(allowHttp = true))).isNotNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `artwork of an item whose source does not allow HTTP, or of an unmarked one, sends no request`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build()) // never asked for
        for (metadata in listOf(item(allowHttp = false), item(allowHttp = null))) {
            val e = assertThrows(ExecutionException::class.java) { load(metadata) }
            assertThat(generateSequence<Throwable>(e) { it.cause }.any { it is CleartextBlockedException }).isTrue()
        }
        assertThat(loader.loadBitmap(Uri.parse(server.url("/logo.png").toString())).let { f -> runCatching { f.get(10, TimeUnit.SECONDS) }.isFailure }).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the item's own mark is what counts, and what a controller sent is replaced`() {
        val request = MediaIds.request("1|k", "K", null, server.url("/logo.png").toString(), ItemKind.LIVE)
        val forged = request.withArtworkPolicy(true) // a controller's claim
        assertThat(forged.withArtworkPolicy(false).mediaMetadata.extras!!.getBoolean(MediaIds.EXTRA_ARTWORK_HTTP, true)).isFalse() // the service's answer wins
        assertThat(request.mediaMetadata.extras!!.containsKey(MediaIds.EXTRA_ARTWORK_HTTP)).isFalse()
        assertThat(request.mediaMetadata.extras!!.getString(MediaIds.EXTRA_KIND)).isEqualTo("LIVE") // the rest of the extras stay
    }
}
