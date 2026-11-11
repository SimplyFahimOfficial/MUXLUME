package com.muxlume.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muxlume.app.container
import com.muxlume.app.ui.components.MuxLogo
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.theme.Motion
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.launch

/** First launch: brand, what the app is (and isn't), and two ways forward. No account needed. */
@Composable
fun WelcomeScreen(onAdd: () -> Unit, onExplore: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = MuxTheme.reducedMotion
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }

    BoxWithConstraints(Modifier.fillMaxSize().background(Mux.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Bottom-aligned on tall screens, scrollable when large fonts need more room.
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 28.dp, vertical = 24.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            AnimatedVisibility(shown, enter = fadeIn(Motion.spec(Motion.MEDIUM * 2, reduced)) + slideInVertically(Motion.spec(Motion.MEDIUM * 2, reduced)) { it / 8 }) {
                Column {
                    MuxLogo(size = 96.dp)
                    Spacer(Modifier.height(28.dp))
                    Text("MUXLUME", style = MaterialTheme.typography.displayLarge, color = Mux.White, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A cinematic player for the live TV, movies and series you already subscribe to.",
                        style = MaterialTheme.typography.titleLarge, color = Mux.Cream.copy(alpha = 0.9f),
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.fillMaxWidth().clip(Mux.CardShape).background(Mux.Surface).padding(16.dp)) {
                        Icon(Icons.Rounded.Info, null, tint = Mux.Orange, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "MUXLUME is only a player. It doesn't provide channels, subscriptions or any media. " +
                                "Add M3U playlists or Xtream Codes-compatible accounts you're authorized to use. Everything stays on this device.",
                            style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    PrimaryButton("Add a playlist", onAdd, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Explore first", {
                        scope.launch {
                            context.container.settings.setOnboardingDone()
                            onExplore()
                        }
                    }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
