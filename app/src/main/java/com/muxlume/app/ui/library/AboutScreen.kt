package com.muxlume.app.ui.library

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.muxlume.app.BuildConfig
import com.muxlume.app.container
import com.muxlume.app.data.ErrorText
import com.muxlume.app.distribution.Extras
import com.muxlume.app.distribution.ReleaseInfo
import com.muxlume.app.distribution.UpdateChecker
import com.muxlume.app.support.SupportIntents
import com.muxlume.app.ui.components.SecondaryButton
import kotlinx.coroutines.launch
import java.io.IOException
import com.muxlume.app.ui.components.MuxLogo
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme

private val faq = listOf(
    R.string.about_does_muxlume_include_channels to R.string.about_no_muxlume_is_a_player,
    R.string.about_a_channel_doesn_t_play to R.string.about_open_the_player_s_info,
    R.string.about_unencrypted_http_errors to R.string.about_streams_over_http_are_blocked,
    R.string.about_import_failed_or_is_slow to R.string.about_very_large_playlists_take_a,
    R.string.about_which_formats_work to R.string.about_hls_m3u8_mpeg_dash_mpd,
    R.string.about_where_is_the_tv_guide to R.string.about_the_electronic_program_guide_xmltv,
)

private val licenses = listOf(
    R.string.about_androidx_compose_media3_exoplayer_room to R.string.about_apache_license_2_0,
    R.string.about_kotlin_and_kotlinx_coroutines_serialization to R.string.about_apache_license_2_0,
    R.string.about_okhttp_and_okio to R.string.about_apache_license_2_0,
    R.string.about_coil to R.string.about_apache_license_2_0,
    R.string.about_zxing_core_upi_qr_code to R.string.about_apache_license_2_0,
    R.string.about_guava_via_media3 to R.string.about_apache_license_2_0,
)

/** Troubleshooting, version and licences. Project and developer links live on the Support screen. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.about_help_about), onSearch = null, onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 32.dp)) {
            Row(Modifier.padding(Mux.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                MuxLogo(size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.about_muxlume), style = MaterialTheme.typography.headlineMedium, color = MuxTheme.TextPrimary)
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
                }
            }
            Text(
                stringResource(R.string.about_muxlume_is_an_iptv_player) +
                    stringResource(R.string.about_you_are_responsible_for_only),
                style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding),
            )
            Extras.updates(context.container.http)?.let { UpdateRow(it) }
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.about_troubleshooting))
            faq.forEach { (q, a) -> Expandable(stringResource(q), stringResource(a)) }
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.about_open_source_licenses))
            licenses.forEach { (lib, lic) ->
                Column(Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 6.dp)) {
                    Text(stringResource(lib), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextPrimary)
                    Text(stringResource(lic), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun Expandable(question: String, answer: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.about_expanded else R.string.about_collapsed)
    Column(
        Modifier.fillMaxWidth().animateContentSize().pressable({ open = !open }, role = Role.Button)
            .semantics { stateDescription = state }
            .padding(horizontal = Mux.ScreenPadding, vertical = 12.dp),
    ) {
        Text(question, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary)
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(answer, style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
        }
    }
}

/** Manual update check (GitHub build only): contacts GitHub when tapped, never in the background. */
@Composable
private fun UpdateRow(checker: UpdateChecker) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    Column(Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.about_check_for_updates), {
            busy = true
            release = null
            scope.launch {
                try {
                    release = checker.newerThan(BuildConfig.VERSION_NAME.substringBefore('-'))
                    message = if (release == null) res.getString(R.string.about_you_have_the_latest_version) else res.getString(R.string.about_version_available, release!!.version)
                } catch (e: IOException) {
                    message = ErrorText.of(res, e)
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        release?.let { r -> SecondaryButton(stringResource(R.string.about_view_release), { SupportIntents.openUrl(context, r.url) }) }
    }
}
