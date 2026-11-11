package com.muxlume.app.ui.onboarding

import com.muxlume.app.R
import androidx.annotation.StringRes
import android.content.res.Resources
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.container
import com.muxlume.app.core.net.Urls
import com.muxlume.app.data.ErrorText
import com.muxlume.app.data.ImportResult
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.xtream.XtreamApi
import com.muxlume.app.ui.components.PillRow
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class Mode(@StringRes val label: Int) { LINK(R.string.add_source_m3u_link), FILE(R.string.add_source_m3u_file), XTREAM(R.string.add_source_xtream_codes) }

sealed interface Phase {
    data object Editing : Phase
    data object Testing : Phase
    data class Importing(val count: Int) : Phase
    data class Done(val result: ImportResult) : Phase

    /** Saved without re-importing (only the name or the HTTP setting changed). */
    data object Saved : Phase
}

data class FormState(
    val mode: Mode = Mode.LINK,
    val name: String = "",
    val url: String = "",
    val fileUri: Uri? = null,
    val fileName: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val httpAcknowledged: Boolean = false,
    val phase: Phase = Phase.Editing,
    val error: String? = null,
    val accountInfo: String? = null,
) {
    /** The address the user typed uses plain HTTP and needs an explicit acknowledgement. */
    val usesHttp: Boolean get() = when (mode) {
        Mode.LINK -> url.trim().startsWith("http://", ignoreCase = true)
        Mode.XTREAM -> server.isNotBlank() && XtreamApi.normalizeServer(server)?.isHttps == false
        Mode.FILE -> false
    }

    @get:StringRes val validationError: Int? get() = when (mode) {
        Mode.LINK -> if (Urls.parseHttp(url) == null) R.string.add_source_enter_a_link_that_starts else null
        Mode.FILE -> if (fileUri == null) R.string.add_source_choose_a_playlist_file else null
        Mode.XTREAM -> when {
            XtreamApi.normalizeServer(server) == null -> R.string.add_source_enter_the_server_address_for
            username.isBlank() -> R.string.add_source_enter_your_username
            password.isEmpty() -> R.string.add_source_enter_your_password
            else -> null
        }
    }

    val canSubmit: Boolean get() = validationError == null && (!usesHttp || httpAcknowledged) && phase == Phase.Editing

    /** Where the list comes from; only a change here needs a new download. */
    val location: List<Any?> get() = when (mode) {
        Mode.LINK -> listOf(mode, url.trim())
        Mode.FILE -> listOf(mode, fileUri)
        Mode.XTREAM -> listOf(mode, XtreamApi.normalizeServer(server), username.trim(), password)
    }
}

class AddSourceViewModel(private val c: AppContainer, private val res: Resources, private val existingId: Long?) : ViewModel() {
    val state = MutableStateFlow(FormState())
    private var savedId: Long? = existingId
    private var job: Job? = null
    /** The source's location as loaded, to tell a rename from a new playlist. */
    private var loadedLocation: List<Any?>? = null

    /** Editing a source re-imports only when its link, file or login changed. */
    fun needsImport(s: FormState) = loadedLocation == null || s.location != loadedLocation

    init {
        if (existingId != null) viewModelScope.launch {
            val s = c.sources.get(existingId) ?: return@launch
            val secret = runCatching { c.sources.secretOf(s) }.getOrNull()
            state.value = when (s.type) {
                SourceType.M3U_URL -> FormState(Mode.LINK, s.name, url = secret?.url.orEmpty(), httpAcknowledged = s.allowHttp)
                SourceType.M3U_FILE -> FormState(Mode.FILE, s.name, fileUri = secret?.url?.let(Uri::parse), fileName = s.label, httpAcknowledged = s.allowHttp)
                SourceType.XTREAM -> FormState(Mode.XTREAM, s.name, server = secret?.url.orEmpty(), username = secret?.username.orEmpty(), password = secret?.password.orEmpty(), httpAcknowledged = s.allowHttp)
            }
            // An item list that never imported (or failed) still needs one, even if nothing changed.
            loadedLocation = if (s.refreshedAt != null && s.lastError == null) state.value.location else null
        }
    }

    val isEditing get() = existingId != null

    fun edit(f: (FormState) -> FormState) = state.update { f(it).copy(error = null, accountInfo = if (it.mode == f(it).mode) it.accountInfo else null) }

    private fun input(s: FormState): SourceInput = when (s.mode) {
        Mode.LINK -> SourceInput.M3uUrl(s.name, s.url, s.usesHttp && s.httpAcknowledged)
        Mode.FILE -> SourceInput.M3uFile(s.name, s.fileUri!!, s.fileName, s.httpAcknowledged)
        Mode.XTREAM -> SourceInput.Xtream(s.name, s.server, s.username, s.password, s.usesHttp && s.httpAcknowledged)
    }

    fun testConnection() {
        val s = state.value
        if (s.mode != Mode.XTREAM || !s.canSubmit) return
        job = viewModelScope.launch {
            state.update { it.copy(phase = Phase.Testing, error = null) }
            val result = runCatching { c.sources.testXtream(input(s) as SourceInput.Xtream) }
            state.update {
                result.fold(
                    { acc ->
                        val exp = acc.expiresAtEpochSec?.let { e -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochSecond(e).atZone(ZoneId.systemDefault())) }
                        it.copy(
                            phase = Phase.Editing,
                            accountInfo = listOfNotNull(
                                res.getString(R.string.add_source_account, acc.status.lowercase()),
                                exp?.let { d -> res.getString(R.string.add_source_expires, d) },
                                acc.maxConnections?.let { n -> res.getQuantityString(R.plurals.add_source_connections, n, n) },
                            ).joinToString(" · "),
                        )
                    },
                    { e -> it.copy(phase = Phase.Editing, error = ErrorText.of(res, e)) },
                )
            }
        }
    }

    fun submit() {
        val s = state.value
        if (!s.canSubmit) return
        job = viewModelScope.launch {
            state.update { it.copy(phase = Phase.Importing(0), error = null) }
            val createdNew = savedId == null
            try {
                if (!needsImport(s)) {
                    c.sources.save(input(s), savedId)
                    state.update { it.copy(phase = Phase.Saved) }
                    return@launch
                }
                val id = c.sources.save(input(s), savedId).also { savedId = it }
                val result = c.sources.refresh(id) { n -> state.update { it.copy(phase = Phase.Importing(n)) } }
                c.settings.setOnboardingDone()
                state.update { it.copy(phase = Phase.Done(result)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (createdNew) savedId?.let { id -> c.appScope.launch { c.sources.delete(id) } }
                savedId = if (createdNew) null else savedId
                state.update { it.copy(phase = Phase.Editing) }
                throw e
            } catch (e: Exception) {
                state.update { it.copy(phase = Phase.Editing, error = ErrorText.of(res, e)) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        state.update { it.copy(phase = Phase.Editing) }
    }
}

@Composable
fun AddSourceScreen(existingId: Long?, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val vm: AddSourceViewModel = viewModel { AddSourceViewModel(context.container, context.applicationContext.resources, existingId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access so the playlist can be refreshed later.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: res.getString(R.string.add_source_playlist_file)
            vm.edit { it.copy(fileUri = uri, fileName = name) }
        }
    }

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(if (vm.isEditing) R.string.add_source_edit_source else R.string.add_source_add_a_playlist), onSearch = null, onBack = onBack)
        if (s.phase == Phase.Saved) LaunchedEffect(Unit) { onDone() }
        when (val phase = s.phase) {
            is Phase.Importing -> Progress(phase.count, vm::cancel)
            is Phase.Done -> Done(phase.result, onDone)
            else -> Form(s, vm, onPickFile = { picker.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream")) })
        }
    }
}

@Composable
private fun Form(s: FormState, vm: AddSourceViewModel, onPickFile: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!vm.isEditing) PillRow(Mode.entries.map { stringResource(it.label) }, s.mode.ordinal, { i -> vm.edit { it.copy(mode = Mode.entries[i]) } })
        Column(Modifier.padding(horizontal = Mux.ScreenPadding).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(stringResource(R.string.add_source_name_optional), s.name, { v -> vm.edit { it.copy(name = v) } })
            when (s.mode) {
                Mode.LINK -> Field(stringResource(R.string.add_source_playlist_link), s.url, { v -> vm.edit { it.copy(url = v) } }, keyboard = KeyboardType.Uri, placeholder = "https://…/playlist.m3u")
                Mode.FILE -> {
                    SecondaryButton(if (s.fileUri == null) stringResource(R.string.add_source_choose_a_file) else s.fileName, onPickFile, Modifier.fillMaxWidth(), icon = Icons.Rounded.FileOpen)
                    Text(stringResource(R.string.add_source_the_file_stays_where_it), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary)
                }
                Mode.XTREAM -> {
                    Field(
                        stringResource(R.string.add_source_server_address),
                        s.server,
                        { v -> vm.edit { it.copy(server = v) } },
                        keyboard = KeyboardType.Uri,
                        placeholder = "https://provider.example:8080",
                    )
                    Field(stringResource(R.string.add_source_username), s.username, { v -> vm.edit { it.copy(username = v) } })
                    Field(
                        stringResource(R.string.add_source_password), s.password, { v -> vm.edit { it.copy(password = v) } }, keyboard = KeyboardType.Password,
                        visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton({ showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    stringResource(if (showPassword) R.string.add_source_hide_password else R.string.add_source_show_password),
                                    tint = MuxTheme.TextSecondary,
                                )
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.add_source_credentials_are_encrypted_with_a),
                        style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary,
                    )
                }
            }
            // A local file can list plain-HTTP streams; that is opt-in here instead of required up front.
            if (s.usesHttp || s.mode == Mode.FILE) HttpWarning(s.httpAcknowledged, optional = s.mode == Mode.FILE) { on -> vm.edit { it.copy(httpAcknowledged = on) } }
            s.accountInfo?.let { info ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MuxTheme.Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(info, color = MuxTheme.Primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            s.error?.let { Text(it, color = MuxTheme.Error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                if (!vm.isEditing) stringResource(R.string.add_source_add_and_import) else stringResource(if (vm.needsImport(s)) R.string.add_source_save_and_re_import else R.string.add_source_save),
                vm::submit,
                Modifier.fillMaxWidth(),
                enabled = s.canSubmit,
            )
            if (s.mode == Mode.XTREAM) {
                if (s.phase == Phase.Testing) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), color = MuxTheme.Accent) }
                } else {
                    SecondaryButton(stringResource(R.string.add_source_test_connection), vm::testConnection, Modifier.fillMaxWidth(), enabled = s.canSubmit)
                }
            }
            if (!s.canSubmit && s.phase == Phase.Editing) s.validationError?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary) }
        }
    }
}

@Composable
private fun HttpWarning(acknowledged: Boolean, optional: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Mux.CardShape).background(MuxTheme.Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = MuxTheme.Accent)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (optional) R.string.add_source_streams_over_plain_http else R.string.add_source_this_server_doesn_t_use),
                style = MaterialTheme.typography.titleMedium,
                color = MuxTheme.TextPrimary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            (if (optional) stringResource(R.string.add_source_many_playlist_files_list_http) else "") +
                stringResource(R.string.add_source_with_plain_http_anyone_on),
            style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(acknowledged, role = Role.Checkbox, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(acknowledged, null, colors = CheckboxDefaults.colors(checkedColor = MuxTheme.Accent, checkmarkColor = MuxTheme.OnAccent))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_source_i_understand_allow_unencrypted_http), color = MuxTheme.TextPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text, placeholder: String? = null,
    visual: VisualTransformation = VisualTransformation.None, trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value, { onChange(it.take(4096)) }, Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = MuxTheme.TextSecondary) } },
        singleLine = true,
        visualTransformation = visual,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        shape = Mux.CardShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MuxTheme.Primary, unfocusedBorderColor = MuxTheme.Outline, focusedLabelColor = MuxTheme.Primary,
            cursorColor = MuxTheme.Accent, focusedContainerColor = MuxTheme.Surface, unfocusedContainerColor = MuxTheme.Surface,
        ),
    )
}

@Composable
private fun Progress(count: Int, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Mux.ScreenPadding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MuxTheme.Accent)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.add_source_importing), style = MaterialTheme.typography.headlineSmall, color = MuxTheme.TextPrimary)
        Text(
            if (count == 0) stringResource(R.string.add_source_connecting) else stringResource(R.string.add_source_items_read, count),
            style = MaterialTheme.typography.bodyLarge, color = MuxTheme.TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(24.dp))
        SecondaryButton(stringResource(R.string.add_source_cancel), onCancel)
    }
}

@Composable
private fun Done(r: ImportResult, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Mux.ScreenPadding), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MuxTheme.Accent, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.add_source_you_re_all_set), style = MaterialTheme.typography.displaySmall, color = MuxTheme.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                stringResource(
                    R.string.add_source_channels,
                    r.live,
                ), r.movies.takeIf { it > 0 }?.let { stringResource(R.string.add_source_movies, it) }, r.series.takeIf { it > 0 }?.let { stringResource(R.string.add_source_series, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium, color = MuxTheme.Primary,
        )
        if (r.skipped > 0 || r.duplicates > 0) {
            Text(
                listOfNotNull(
                    r.duplicates.takeIf { it > 0 }?.let { stringResource(R.string.add_source_duplicates_merged, it) },
                    r.skipped.takeIf { it > 0 }?.let { stringResource(R.string.add_source_unreadable_or_unsupported_entries_skipped, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary,
            )
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.add_source_start_watching), onDone, Modifier.fillMaxWidth())
    }
}
