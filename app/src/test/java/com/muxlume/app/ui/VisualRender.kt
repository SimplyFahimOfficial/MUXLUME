package com.muxlume.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxlume.app.MuxlumeApp
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.ui.main.MainScreen
import com.muxlume.app.ui.onboarding.AddSourceScreen
import com.muxlume.app.ui.onboarding.WelcomeScreen
import com.muxlume.app.ui.theme.MuxlumeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Developer visual check, not a screenshot test: renders key screens to PNG with placeholder data
 * (no real artwork). Skipped unless run with -Dmuxlume.visual=<output dir>:
 *   ./gradlew testDebugUnitTest --tests '*VisualRender*' -Pmuxlume.visual=build/visual
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class VisualRender {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("muxlume.visual")

    @Before fun only() = assumeTrue(out != null)

    private fun save(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seed() = runBlocking {
        val db = (ApplicationProvider.getApplicationContext<MuxlumeApp>()).container.db
        val id = db.sources().insert(SourceEntity(name = "Home IPTV", type = SourceType.M3U_URL, secret = "x", label = "https://iptv.example", allowHttp = false, createdAt = 0, refreshedAt = System.currentTimeMillis(), liveCount = 6, movieCount = 4))
        val groups = listOf("News", "Sport", "Kids")
        db.items().insertAll(List(6) { i -> ItemEntity(sourceId = id, kind = ItemKind.LIVE, stableKey = "c$i", name = listOf("Northwind News", "Harbor Sport", "Studio One", "Cinema Club", "Daily World", "Kids Planet")[i], groupTitle = groups[i % 3], logo = null, url = "https://x/$i", number = 100 + i, position = i) })
        db.items().insertAll(List(4) { i -> ItemEntity(sourceId = id, kind = ItemKind.MOVIE, stableKey = "m$i", name = listOf("The Long Night", "Paper Moons", "Afterglow", "Northern Lines")[i], groupTitle = "Drama", logo = null, url = "https://x/m$i.mp4", year = "202$i", position = i, addedAt = 100L - i) })
        db.favorites().add(FavoriteEntity(id, "c0", 2)); db.favorites().add(FavoriteEntity(id, "c1", 1))
        db.history().upsert(HistoryEntity("$id|m0", id, ItemKind.MOVIE, "The Long Night", "Drama", null, 40 * 60_000, 100 * 60_000, 5))
    }

    @Test fun welcome() { compose.setContent { MuxlumeTheme { WelcomeScreen({}, {}) } }; save("01-welcome") }
    @Test fun addSource() { compose.setContent { MuxlumeTheme { AddSourceScreen(null, {}, {}) } }; save("02-add-source") }

    @Test fun home() {
        seed()
        compose.setContent { MuxlumeTheme { MainScreen(Nav(androidx.navigation.compose.rememberNavController())) } }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Resume")).fetchSemanticsNodes().isNotEmpty() }
        save("03-home")
    }
}
