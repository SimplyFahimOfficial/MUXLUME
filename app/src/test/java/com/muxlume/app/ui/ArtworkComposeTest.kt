package com.muxlume.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.AppContainer
import com.muxlume.app.MuxlumeApp
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.theme.MuxlumeTheme
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The artwork cell of a source: it starts loading when its source's HTTP setting arrives or changes, and not before. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArtworkComposeTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val container: AppContainer get() = (RuntimeEnvironment.getApplication() as MuxlumeApp).container

    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Before
    fun setUp() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(png)).build())
        server.start()
    }

    @After
    fun tearDown() {
        runBlocking { container.db.sources().allNow().forEach { container.db.sources().delete(it.id) } }
        server.close()
    }

    @Test
    fun `an artwork cell blocked at first loads once its source allows HTTP`() {
        val c = container
        val source = SourceEntity(name = "S", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0)
        val id = runBlocking { c.db.sources().insert(source) }
        compose.setContent { MuxlumeTheme { Artwork(server.url("/logo.png").toString(), "Title", id, Modifier.size(64.dp)) } }
        compose.waitForIdle()
        assertThat(server.requestCount).isEqualTo(0) // the source does not allow HTTP: nothing is sent

        runBlocking { c.db.sources().update(source.copy(id = id, allowHttp = true)) } // the user allows it in Edit
        compose.waitUntil(20_000) {
            ShadowLooper.idleMainLooper()
            server.requestCount == 1
        }
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/logo.png")
    }
}
