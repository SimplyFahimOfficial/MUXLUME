package com.muxlume.app.ui.library

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxlume.app.support.SupportConfig
import com.muxlume.app.support.SupportIntents
import com.muxlume.app.support.UpiQrCode
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme

/** Native "Support my work": on-device UPI QR and payment, plus the project and developer links. */
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    fun open(url: String) {
        if (!SupportIntents.openUrl(context, url)) Toast.makeText(context, res.getString(R.string.support_no_browser_found_link_copied), Toast.LENGTH_SHORT).show()
    }
    // Encoded once; the payload identifies only the payee (no amount) and never leaves the device.
    val qr = remember { UpiQrCode.toBitmap(UpiQrCode.encode(SupportConfig.upiPaymentUri())).asImageBitmap() }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SupportIntents.QR_MIME_TYPE)) { uri ->
        if (uri != null) {
            val ok = SupportIntents.saveQr(context, uri)
            Toast.makeText(context, res.getString(if (ok) R.string.support_qr_code_saved else R.string.support_couldn_t_save_the_qr), Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.support_support_my_work), onSearch = null, onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.support_voluntary_support_keeps_muxlume_ad),
                style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding),
            )
            Spacer(Modifier.height(12.dp))

            Column(Modifier.padding(horizontal = Mux.ScreenPadding).fillMaxWidth().clip(Mux.CardShape).background(MuxTheme.Surface).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Dark-on-white, as scanners expect, in every theme.
                    Box(Modifier.clip(Mux.CardShape).background(Color.White).padding(8.dp)) {
                        Image(
                            bitmap = qr,
                            contentDescription = stringResource(R.string.support_upi_qr_code_for, SupportConfig.UPI_ID),
                            filterQuality = FilterQuality.None,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(132.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.support_upi), style = MaterialTheme.typography.labelMedium, color = MuxTheme.TextSecondary)
                        SelectionContainer {
                            Text(SupportConfig.UPI_ID, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 2)
                        }
                        Text(stringResource(R.string.support_payee, SupportConfig.UPI_PAYEE_NAME), style = MaterialTheme.typography.labelSmall, color = MuxTheme.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.support_scan_with_any_upi_app), style = MaterialTheme.typography.labelSmall, color = MuxTheme.TextSecondary)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip(Icons.Rounded.ContentCopy, stringResource(R.string.support_copy_upi_id), Modifier.weight(1f)) {
                        SupportIntents.copyUpiId(context)
                        Toast.makeText(context, res.getString(R.string.support_upi_id_copied), Toast.LENGTH_SHORT).show()
                    }
                    ActionChip(Icons.Rounded.Favorite, stringResource(R.string.support_pay_with_upi), Modifier.weight(1f), accent = true) {
                        if (SupportIntents.openUpiApp(context) == SupportIntents.UpiResult.NO_UPI_APP) {
                            Toast.makeText(context, res.getString(R.string.support_no_upi_app_found_upi), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip(Icons.Rounded.Share, stringResource(R.string.support_share_qr_2), Modifier.weight(1f)) {
                        if (!SupportIntents.shareQr(context)) Toast.makeText(context, res.getString(R.string.support_couldn_t_share_the_qr), Toast.LENGTH_SHORT).show()
                    }
                    ActionChip(Icons.Rounded.Download, stringResource(R.string.support_save_qr), Modifier.weight(1f)) { saveLauncher.launch(SupportIntents.QR_FILE_NAME) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.support_check_the_payee_is_only, SupportConfig.UPI_PAYEE_NAME),
                    style = MaterialTheme.typography.labelSmall, color = MuxTheme.TextSecondary,
                )
            }

            LinkRow(Icons.Rounded.Favorite, stringResource(R.string.support_more_ways_to_pay), stringResource(R.string.support_cards_and_other_options), { open(SupportConfig.MORE_WAYS_TO_PAY) })
            Spacer(Modifier.height(4.dp))
            SectionHeader(stringResource(R.string.support_project_and_developer))
            LinkRow(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.support_telegram_community), "t.me/Muxlume", { open(SupportConfig.TELEGRAM) })
            LinkRow(Icons.Rounded.Language, stringResource(R.string.support_project_website), "simplyfahim.pages.dev/Muxlume", { open(SupportConfig.WEBSITE) })
            LinkRow(Icons.Rounded.Email, stringResource(R.string.support_contact_the_developer), "simplyfahim.pages.dev", { open(SupportConfig.CONTACT) })
            LinkRow(Icons.Rounded.Code, stringResource(R.string.support_github), stringResource(R.string.support_simplyfahimofficial), { open(SupportConfig.GITHUB_PROFILE) })
            LinkRow(Icons.Rounded.PhotoCamera, stringResource(R.string.support_instagram), stringResource(R.string.support_simplyfahimofficial_2), { open(SupportConfig.INSTAGRAM) })
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val bg = if (accent) MuxTheme.Accent else MuxTheme.Raised
    val fg = if (accent) MuxTheme.OnAccent else MuxTheme.TextPrimary
    Row(
        modifier.height(48.dp).clip(Mux.PillShape).background(bg).pressable(onClick, label = label).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
