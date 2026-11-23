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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allowed = mutableSetOf<Long>() // the sources that allow plain HTTP, as the app knows them
    private var answer: suspend (Long) -> Boolean = { it in allowed }

    private val png: ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        server.start()
        loader = ArtworkLoader(Http.client(), scope, { answer(it) }) { DataSourceBitmapLoader.Builder(context).setDataSourceFactory(it).build() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun item(source: Long?): MediaMetadata {
        val base = MediaItem.Builder().setMediaId("1|k").setMediaMetadata(MediaMetadata.Builder().setArtworkUri(Uri.parse(server.url("/logo.png").toString())).setExtras(Bundle()).build()).build()
        return (source?.let { base.withArtworkSource(it) } ?: base).mediaMetadata
    }

    private fun load(metadata: MediaMetadata) = loader.loadBitmapFromMetadata(metadata)!!.get(10, TimeUnit.SECONDS)

    @Test
    fun `artwork of an item whose source allows HTTP is loaded`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build())
        allowed += 7
        assertThat(load(item(source = 7))).isNotNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `artwork of an item whose source does not allow HTTP, or of an unmarked one, sends no request`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build()) // never asked for
        allowed += 7 // another source allows it
        for (metadata in listOf(item(source = 8), item(source = null))) {
            val e = assertThrows(ExecutionException::class.java) { load(metadata) }
            assertThat(generateSequence<Throwable>(e) { it.cause }.any { it is CleartextBlockedException }).isTrue()
        }
        assertThat(loader.loadBitmap(Uri.parse(server.url("/logo.png").toString())).let { f -> runCatching { f.get(10, TimeUnit.SECONDS) }.isFailure }).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `allowing HTTP is withdrawn from the next artwork when the source stops allowing it, and given again when it starts`() {
        repeat(2) { server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build()) }
        allowed += 7
        assertThat(load(item(source = 7))).isNotNull()
        allowed -= 7 // the user turned it off for that playlist
        val e = assertThrows(ExecutionException::class.java) { load(item(source = 7)) }
        assertThat(generateSequence<Throwable>(e) { it.cause }.any { it is CleartextBlockedException }).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        allowed += 7 // and on again
        assertThat(load(item(source = 7))).isNotNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an item's image waits for the source's setting when it is not known yet, and is then loaded as the setting says`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build())
        val known = CompletableDeferred<Boolean>() // a cold start: the settings are still being read from the database
        answer = { known.await() }
        val future = loader.loadBitmapFromMetadata(item(source = 7))!!
        assertThat(future.isDone).isFalse()
        assertThat(server.requestCount).isEqualTo(0) // nothing was sent, and nothing was refused: it waits
        known.complete(true) // the source had opted in
        assertThat(future.get(10, TimeUnit.SECONDS)).isNotNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `the item's own mark is what counts, and what a controller sent is replaced`() {
        val request = MediaIds.request("1|k", "K", null, server.url("/logo.png").toString(), ItemKind.LIVE)
        val forged = request.withArtworkSource(1) // a controller's claim
        assertThat(forged.withArtworkSource(9).mediaMetadata.extras!!.getLong(MediaIds.EXTRA_ARTWORK_SOURCE)).isEqualTo(9) // the service's answer wins
        assertThat(request.mediaMetadata.extras!!.containsKey(MediaIds.EXTRA_ARTWORK_SOURCE)).isFalse()
        assertThat(request.mediaMetadata.extras!!.getString(MediaIds.EXTRA_KIND)).isEqualTo("LIVE") // the rest of the extras stay
    }
}
