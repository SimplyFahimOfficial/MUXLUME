package com.muxlume.app.ui.onboarding

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class Mode(val label: String) { LINK("M3U link"), FILE("M3U file"), XTREAM("Xtream Codes") }

sealed interface Phase {
    data object Editing : Phase
    data object Testing : Phase
    data class Importing(val count: Int) : Phase
    data class Done(val result: ImportResult) : Phase
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

    val validationError: String? get() = when (mode) {
        Mode.LINK -> if (Urls.parseHttp(url) == null) "Enter a link that starts with https:// or http://" else null
        Mode.FILE -> if (fileUri == null) "Choose a playlist file" else null
        Mode.XTREAM -> when {
            XtreamApi.normalizeServer(server) == null -> "Enter the server address, for example https://provider.example:8080"
            username.isBlank() -> "Enter your username"
            password.isEmpty() -> "Enter your password"
            else -> null
        }
    }

    val canSubmit: Boolean get() = validationError == null && (!usesHttp || httpAcknowledged) && phase == Phase.Editing
}

class AddSourceViewModel(private val c: AppContainer, private val existingId: Long?) : ViewModel() {
    val state = MutableStateFlow(FormState())
    private var savedId: Long? = existingId
    private var job: Job? = null

    init {
        if (existingId != null) viewModelScope.launch {
            val s = c.sources.get(existingId) ?: return@launch
            val secret = runCatching { c.sources.secretOf(s) }.getOrNull()
            state.value = when (s.type) {
                SourceType.M3U_URL -> FormState(Mode.LINK, s.name, url = secret?.url.orEmpty(), httpAcknowledged = s.allowHttp)
                SourceType.M3U_FILE -> FormState(Mode.FILE, s.name, fileUri = secret?.url?.let(Uri::parse), fileName = s.label, httpAcknowledged = s.allowHttp)
                SourceType.XTREAM -> FormState(Mode.XTREAM, s.name, server = secret?.url.orEmpty(), username = secret?.username.orEmpty(), password = secret?.password.orEmpty(), httpAcknowledged = s.allowHttp)
            }
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
            // The HTTP gate only opens once a source with allowHttp is saved; testing an unsaved http server is allowed explicitly here.
            val result = withHttpIfAcknowledged(s) { runCatching { c.sources.testXtream(input(s) as SourceInput.Xtream) } }
            state.update {
                result.fold(
                    { acc ->
                        val exp = acc.expiresAtEpochSec?.let { e -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochSecond(e).atZone(ZoneId.systemDefault())) }
                        it.copy(phase = Phase.Editing, accountInfo = listOfNotNull("Account ${acc.status.lowercase()}", exp?.let { d -> "expires $d" }, acc.maxConnections?.let { n -> "$n connection${if (n == 1) "" else "s"}" }).joinToString(" · "))
                    },
                    { e -> it.copy(phase = Phase.Editing, error = ErrorText.of(e)) },
                )
            }
        }
    }

    private suspend fun <T> withHttpIfAcknowledged(s: FormState, block: suspend () -> T): T {
        val gate = com.muxlume.app.core.net.CleartextGate
        val before = gate.allowed
        if (s.usesHttp && s.httpAcknowledged) gate.allowed = true
        return try { block() } finally { gate.allowed = before || c.db.sources().anyAllowsHttpNow() }
    }

    fun submit() {
        val s = state.value
        if (!s.canSubmit) return
        job = viewModelScope.launch {
            state.update { it.copy(phase = Phase.Importing(0), error = null) }
            val createdNew = savedId == null
            try {
                val id = c.sources.save(input(s), savedId).also { savedId = it }
                // The gate follows the database asynchronously; open it now so the first import isn't refused.
                if (s.usesHttp && s.httpAcknowledged) com.muxlume.app.core.net.CleartextGate.allowed = true
                val result = c.sources.refresh(id) { n -> state.update { it.copy(phase = Phase.Importing(n)) } }
                c.settings.setOnboardingDone()
                state.update { it.copy(phase = Phase.Done(result)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (createdNew) savedId?.let { id -> c.appScope.launch { c.sources.delete(id) } }
                savedId = if (createdNew) null else savedId
                state.update { it.copy(phase = Phase.Editing) }
                throw e
            } catch (e: Exception) {
                state.update { it.copy(phase = Phase.Editing, error = ErrorText.of(e)) }
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
    val vm: AddSourceViewModel = viewModel { AddSourceViewModel(context.container, existingId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access so the playlist can be refreshed later.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Playlist file"
            vm.edit { it.copy(fileUri = uri, fileName = name) }
        }
    }

    Column(Modifier.fillMaxSize().background(Mux.Black)) {
        TopHeader(if (vm.isEditing) "Edit source" else "Add a playlist", onSearch = null, onBack = onBack)
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
        if (!vm.isEditing) PillRow(Mode.entries.map { it.label }, s.mode.ordinal, { i -> vm.edit { it.copy(mode = Mode.entries[i]) } })
        Column(Modifier.padding(horizontal = Mux.ScreenPadding).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Name (optional)", s.name, { v -> vm.edit { it.copy(name = v) } })
            when (s.mode) {
                Mode.LINK -> Field("Playlist link", s.url, { v -> vm.edit { it.copy(url = v) } }, keyboard = KeyboardType.Uri, placeholder = "https://…/playlist.m3u")
                Mode.FILE -> {
                    SecondaryButton(if (s.fileUri == null) "Choose a file" else s.fileName, onPickFile, Modifier.fillMaxWidth(), icon = Icons.Rounded.FileOpen)
                    Text("The file stays where it is; MUXLUME keeps read access to re-import it.", style = MaterialTheme.typography.bodySmall, color = Mux.TextSecondary)
                }
                Mode.XTREAM -> {
                    Field("Server address", s.server, { v -> vm.edit { it.copy(server = v) } }, keyboard = KeyboardType.Uri, placeholder = "https://provider.example:8080")
                    Field("Username", s.username, { v -> vm.edit { it.copy(username = v) } })
                    Field(
                        "Password", s.password, { v -> vm.edit { it.copy(password = v) } }, keyboard = KeyboardType.Password,
                        visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton({ showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password", tint = Mux.TextSecondary)
                            }
                        },
                    )
                    Text("Credentials are encrypted with a key that never leaves this device and are only sent to this server.", style = MaterialTheme.typography.bodySmall, color = Mux.TextSecondary)
                }
            }
            // A local file can list plain-HTTP streams; that is opt-in here instead of required up front.
            if (s.usesHttp || s.mode == Mode.FILE) HttpWarning(s.httpAcknowledged, optional = s.mode == Mode.FILE) { on -> vm.edit { it.copy(httpAcknowledged = on) } }
            s.accountInfo?.let { info ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Mux.Orange, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(info, color = Mux.Cream, style = MaterialTheme.typography.bodyMedium)
                }
            }
            s.error?.let { Text(it, color = Mux.Error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(if (vm.isEditing) "Save and re-import" else "Add and import", vm::submit, Modifier.fillMaxWidth(), enabled = s.canSubmit)
            if (s.mode == Mode.XTREAM) {
                if (s.phase == Phase.Testing) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), color = Mux.Orange) }
                } else {
                    SecondaryButton("Test connection", vm::testConnection, Modifier.fillMaxWidth(), enabled = s.canSubmit)
                }
            }
            if (!s.canSubmit && s.phase == Phase.Editing) s.validationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mux.TextSecondary) }
        }
    }
}

@Composable
private fun HttpWarning(acknowledged: Boolean, optional: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Mux.CardShape).background(Mux.Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = Mux.Orange)
            Spacer(Modifier.width(10.dp))
            Text(if (optional) "Streams over plain HTTP" else "This server doesn't use encryption", style = MaterialTheme.typography.titleMedium, color = Mux.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            (if (optional) "Many playlist files list http:// streams, which are blocked unless you allow them. " else "") +
                "With plain HTTP, anyone on your network path (public Wi-Fi, your ISP) can see what you watch and read any username, password or token sent to the server. Use an https:// address if your provider offers one.",
            style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(acknowledged, role = Role.Checkbox, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(acknowledged, null, colors = CheckboxDefaults.colors(checkedColor = Mux.Orange, checkmarkColor = Mux.Black))
            Spacer(Modifier.width(8.dp))
            Text("I understand, allow unencrypted HTTP", color = Mux.White, style = MaterialTheme.typography.bodyMedium)
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
        placeholder = placeholder?.let { { Text(it, color = Mux.TextSecondary) } },
        singleLine = true,
        visualTransformation = visual,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        shape = Mux.CardShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Mux.Cream, unfocusedBorderColor = Mux.Outline, focusedLabelColor = Mux.Cream,
            cursorColor = Mux.Orange, focusedContainerColor = Mux.Surface, unfocusedContainerColor = Mux.Surface,
        ),
    )
}

@Composable
private fun Progress(count: Int, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Mux.ScreenPadding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Mux.Orange)
        Spacer(Modifier.height(24.dp))
        Text("Importing", style = MaterialTheme.typography.headlineSmall, color = Mux.White)
        Text(
            if (count == 0) "Connecting…" else "$count items read",
            style = MaterialTheme.typography.bodyLarge, color = Mux.TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(24.dp))
        SecondaryButton("Cancel", onCancel)
    }
}

@Composable
private fun Done(r: ImportResult, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Mux.ScreenPadding), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Mux.Orange, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("You're all set", style = MaterialTheme.typography.displaySmall, color = Mux.White)
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                "${r.live} channels", r.movies.takeIf { it > 0 }?.let { "$it movies" }, r.series.takeIf { it > 0 }?.let { "$it series" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium, color = Mux.Cream,
        )
        if (r.skipped > 0 || r.duplicates > 0) {
            Text(
                listOfNotNull(
                    r.duplicates.takeIf { it > 0 }?.let { "$it duplicates merged" },
                    r.skipped.takeIf { it > 0 }?.let { "$it unreadable or unsupported entries skipped" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary,
            )
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Start watching", onDone, Modifier.fillMaxWidth())
    }
}
