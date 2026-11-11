package com.muxlume.app.ui.library

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxlume.app.container
import com.muxlume.app.data.Accent
import com.muxlume.app.data.AppSettings
import com.muxlume.app.data.ThemeMode
import com.muxlume.app.ui.components.PillRow
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.Toggle
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.accentColor
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private val themeLabels = listOf("System", "Light", "Dark")
private val accentLabels = mapOf(
    Accent.ORANGE to "Orange", Accent.AMBER to "Amber", Accent.CRIMSON to "Crimson",
    Accent.TEAL to "Teal", Accent.VIOLET to "Violet",
)

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader("Appearance", onSearch = null, onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp)) {
            SectionHeader("Theme")
            Spacer(Modifier.height(10.dp))
            PillRow(themeLabels, settings.theme.ordinal, { i -> scope.launch { c.settings.setTheme(ThemeMode.entries[i]) } })

            Spacer(Modifier.height(20.dp))
            SectionHeader("Accent colour")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = Mux.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dynamicOn = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Accent.entries.forEach { accent ->
                    Swatch(
                        color = accentColor(accent, light = MuxTheme.isLight),
                        selected = !dynamicOn && settings.accent == accent,
                        enabled = !dynamicOn,
                        label = accentLabels.getValue(accent),
                    ) { scope.launch { c.settings.setAccent(accent) } }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(Modifier.height(16.dp))
                Toggle("Material You", "Use the system's accent colour instead of the one above.", settings.dynamicColor) {
                    scope.launch { c.settings.setDynamicColor(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Dark mode")
            Toggle("AMOLED black", "Use pure black for cards and bars in dark mode. Saves power on OLED screens.", settings.amoled) {
                scope.launch { c.settings.setAmoled(it) }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Motion")
            Toggle("Reduce motion", "Turn off transitions and animated effects. Also follows the system's \"Remove animations\" setting.", settings.reduceMotion) {
                scope.launch { c.settings.setReduceMotion(it) }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(Mux.PillShape)
            .background(if (enabled) color else color.copy(alpha = 0.35f))
            .border(if (selected) 3.dp else 0.dp, MuxTheme.TextPrimary, Mux.PillShape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = MuxTheme.OnAccent)
    }
}
