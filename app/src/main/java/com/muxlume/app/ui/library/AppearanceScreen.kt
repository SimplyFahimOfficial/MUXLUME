package com.muxlume.app.ui.library

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
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

private val themeLabels = listOf(R.string.appearance_system, R.string.appearance_light, R.string.appearance_dark)
private val accentLabels = mapOf(
    Accent.ORANGE to R.string.appearance_orange, Accent.AMBER to R.string.appearance_amber, Accent.CRIMSON to R.string.appearance_crimson,
    Accent.TEAL to R.string.appearance_teal, Accent.VIOLET to R.string.appearance_violet,
)

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.appearance_appearance), onSearch = null, onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp)) {
            SectionHeader(stringResource(R.string.appearance_theme))
            Spacer(Modifier.height(10.dp))
            PillRow(themeLabels.map { stringResource(it) }, settings.theme.ordinal, { i -> scope.launch { c.settings.setTheme(ThemeMode.entries[i]) } })

            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.appearance_accent_colour))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = Mux.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dynamicOn = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Accent.entries.forEach { accent ->
                    Swatch(
                        color = accentColor(accent, light = MuxTheme.isLight),
                        selected = !dynamicOn && settings.accent == accent,
                        enabled = !dynamicOn,
                        label = stringResource(accentLabels.getValue(accent)),
                    ) { scope.launch { c.settings.setAccent(accent) } }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(Modifier.height(16.dp))
                Toggle(stringResource(R.string.appearance_material_you), stringResource(R.string.appearance_use_the_system_s_accent), settings.dynamicColor) {
                    scope.launch { c.settings.setDynamicColor(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.appearance_dark_mode))
            Toggle(stringResource(R.string.appearance_amoled_black), stringResource(R.string.appearance_use_pure_black_for_cards), settings.amoled) {
                scope.launch { c.settings.setAmoled(it) }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.appearance_motion))
            Toggle(stringResource(R.string.appearance_reduce_motion), stringResource(R.string.appearance_turn_off_transitions_and_animated), settings.reduceMotion) {
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
