package com.muxlume.app.ui.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.muxlume.app.BuildConfig
import com.muxlume.app.ui.components.MuxLogo
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux

/**
 * Project and developer links. Only destinations that were checked to exist are listed: the
 * project website and the GitHub repository are added once they are public (see docs/RELEASE.md).
 */
object Links {
    const val TELEGRAM = "https://t.me/Muxlume"
    const val CONTACT = "https://simplyfahim.pages.dev/contactme"
    const val SUPPORT = "https://simplyfahim.pages.dev/pay-options"
    const val GITHUB_PROFILE = "https://github.com/SimplyFahimOfficial"
    const val INSTAGRAM = "https://instagram.com/SimplyFahimofficial"
}

private val faq = listOf(
    "Does MUXLUME include channels?" to "No. MUXLUME is a player. You add playlists or provider accounts you're authorized to use; nothing is bundled or sold.",
    "A channel doesn't play" to "Open the player's info panel (the sliders icon) to see the error. Common causes: the provider's connection limit is reached, the channel is offline, or the stream uses a codec this device can't decode. Refreshing the playlist fixes moved channels.",
    "\"Unencrypted HTTP\" errors" to "Streams over http:// are blocked until you allow them. Edit the playlist in Library and tick \"allow unencrypted HTTP\". Prefer https:// if your provider offers it.",
    "Import failed or is slow" to "Very large playlists take a while; the import can be cancelled and your previous list stays intact. If it fails, the reason is shown on the playlist's card in Library.",
    "Which formats work?" to "HLS (.m3u8), MPEG-DASH (.mpd), RTSP and progressive files such as MPEG-TS, MP4 and MKV, as far as this device's decoders support the codecs. DRM-protected streams and RTMP are not supported.",
    "Where is the TV guide?" to "The electronic program guide (XMLTV) is in development and not available in this version.",
)

private val licenses = listOf(
    "AndroidX (Compose, Media3/ExoPlayer, Room, DataStore, Navigation, Lifecycle)" to "Apache License 2.0",
    "Kotlin and kotlinx (coroutines, serialization)" to "Apache License 2.0",
    "OkHttp and Okio" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Guava (via Media3)" to "Apache License 2.0",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Mux.Black)) {
        TopHeader("Help and about", onSearch = null, onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 32.dp)) {
            Row(Modifier.padding(Mux.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                MuxLogo(size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("MUXLUME", style = MaterialTheme.typography.headlineMedium, color = Mux.White)
                    Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary)
                }
            }
            Text(
                "MUXLUME is an IPTV player. It doesn't provide TV channels, subscriptions or copyrighted media, and isn't affiliated with any provider. " +
                    "You are responsible for only adding content you're legally allowed to watch.",
                style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary, modifier = Modifier.padding(horizontal = Mux.ScreenPadding),
            )
            Spacer(Modifier.height(20.dp))
            SectionHeader("Troubleshooting")
            faq.forEach { (q, a) -> Expandable(q, a) }
            Spacer(Modifier.height(20.dp))
            SectionHeader("Community")
            LinkRow(Icons.AutoMirrored.Rounded.Send, "Telegram community", "News, help and feedback", { open(context, Links.TELEGRAM) })
            Spacer(Modifier.height(12.dp))
            SectionHeader("Developer")
            LinkRow(Icons.Rounded.Email, "Contact the developer", "simplyfahim.pages.dev", { open(context, Links.CONTACT) })
            LinkRow(Icons.Rounded.Favorite, "Support development", "Payment options", { open(context, Links.SUPPORT) })
            LinkRow(Icons.Rounded.Code, "GitHub", "SimplyFahimOfficial", { open(context, Links.GITHUB_PROFILE) })
            LinkRow(Icons.Rounded.PhotoCamera, "Instagram", "@SimplyFahimofficial", { open(context, Links.INSTAGRAM) })
            Spacer(Modifier.height(20.dp))
            SectionHeader("Open-source licenses")
            licenses.forEach { (lib, lic) ->
                Column(Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 6.dp)) {
                    Text(lib, style = MaterialTheme.typography.bodyMedium, color = Mux.White)
                    Text(lic, style = MaterialTheme.typography.bodySmall, color = Mux.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun Expandable(question: String, answer: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().animateContentSize().pressable({ open = !open }, role = Role.Button)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(horizontal = Mux.ScreenPadding, vertical = 12.dp),
    ) {
        Text(question, style = MaterialTheme.typography.titleMedium, color = Mux.White)
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(answer, style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary)
        }
    }
}

/** Opens an https link in the user's browser; copies it if no app can open it. */
private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(context, "No browser found. Link copied.", Toast.LENGTH_SHORT).show()
    }
}
