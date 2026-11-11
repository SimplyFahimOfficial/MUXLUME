package com.muxlume.app.ui.library

import android.content.ContentResolver
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.ErrorText
import com.muxlume.app.data.backup.BackupCrypto
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate

data class BackupState(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val restoreFile: Uri? = null,
    val restoreFileName: String = "",
)

class BackupViewModel(private val c: AppContainer, private val res: Resources, private val files: ContentResolver) : ViewModel() {
    val state = MutableStateFlow(BackupState())

    private fun done(message: String, isError: Boolean = false) = state.update { it.copy(busy = false, message = message, isError = isError) }

    fun pickRestoreFile(uri: Uri) {
        val name = files.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        state.update { it.copy(restoreFile = uri, restoreFileName = name ?: res.getString(R.string.backup_backup_file), message = null) }
    }

    fun export(uri: Uri, passphrase: String) {
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val out = files.openOutputStream(uri, "wt") ?: throw IOException("Cannot write the file")
                val s = out.use { c.backup.export(it, passphrase.toCharArray()) }
                var text = res.getQuantityString(R.plurals.backup_saved, s.sources, s.sources)
                if (s.skippedFileSources > 0) text += " " + res.getQuantityString(R.plurals.backup_file_playlists_left_out, s.skippedFileSources, s.skippedFileSources)
                done(text)
            } catch (e: IOException) {
                done(ErrorText.of(res, e), isError = true)
            }
        }
    }

    fun restore(passphrase: String) {
        val uri = state.value.restoreFile ?: return
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val input = files.openInputStream(uri) ?: throw IOException("Cannot read the file")
                val r = input.use { c.backup.import(it, passphrase.toCharArray()) }
                if (r.addedIds.isNotEmpty()) c.settings.setOnboardingDone()
                // Fetch the channel lists of the restored playlists in the background; errors are kept on each source.
                c.appScope.launch { r.addedIds.forEach { id -> runCatching { c.sources.refresh(id) } } }
                done(
                    res.getString(R.string.backup_restored, r.addedIds.size, r.alreadyThere, r.favorites, r.history) +
                        if (r.skipped > 0) " " + res.getString(R.string.backup_skipped, r.skipped) else "",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                done(ErrorText.of(res, e), isError = true)
            }
        }
    }
}

@Composable
fun BackupScreen(nav: Nav) {
    val context = LocalContext.current
    val res = LocalResources.current
    val vm: BackupViewModel = viewModel { BackupViewModel(context.container, res, context.applicationContext.contentResolver) }
    val s by vm.state.collectAsStateWithLifecycle()
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var restorePass by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) vm.export(uri, pass) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickRestoreFile(uri) }
    val pad = Modifier.padding(horizontal = Mux.ScreenPadding)
    val canSave = !s.busy && pass.length >= BackupCrypto.MIN_PASSPHRASE && pass == confirm

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.backup_title), onSearch = null, onBack = nav::back)
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.backup_intro), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, modifier = pad)

            SectionHeader(stringResource(R.string.backup_create))
            PassField(stringResource(R.string.backup_passphrase), pass) { pass = it }
            PassField(stringResource(R.string.backup_repeat_passphrase), confirm) { confirm = it }
            Text(
                stringResource(
                    if (pass.isNotEmpty() && pass.length < BackupCrypto.MIN_PASSPHRASE) R.string.backup_passphrase_short
                    else if (confirm.isNotEmpty() && pass != confirm) R.string.backup_passphrase_mismatch
                    else R.string.backup_passphrase_warning,
                ),
                style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, modifier = pad,
            )
            PrimaryButton(stringResource(R.string.backup_save_backup), { saver.launch("muxlume-backup-${LocalDate.now()}.mxbackup") }, pad.fillMaxWidth(), enabled = canSave)

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.backup_restore))
            SecondaryButton(
                if (s.restoreFile == null) stringResource(R.string.backup_choose_backup) else s.restoreFileName,
                { picker.launch(arrayOf("*/*")) }, pad.fillMaxWidth(), enabled = !s.busy,
            )
            PassField(stringResource(R.string.backup_passphrase), restorePass) { restorePass = it }
            Text(stringResource(R.string.backup_restore_note), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, modifier = pad)
            PrimaryButton(stringResource(R.string.backup_restore_button), { vm.restore(restorePass) }, pad.fillMaxWidth(), enabled = !s.busy && s.restoreFile != null && restorePass.isNotEmpty())

            s.message?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = if (s.isError) MuxTheme.Error else MuxTheme.Primary,
                    modifier = pad.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun PassField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.take(MAX_PASSPHRASE)) }, Modifier.fillMaxWidth().padding(horizontal = Mux.ScreenPadding), label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
}

private const val MAX_PASSPHRASE = 256
