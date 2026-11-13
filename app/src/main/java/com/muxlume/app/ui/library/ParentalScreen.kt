package com.muxlume.app.ui.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
import com.muxlume.app.data.db.LockedGroupEntity
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.lock.ParentalLock
import com.muxlume.app.data.lock.PinResult
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.Toggle
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A playlist's groups and which of them the PIN covers. */
data class SourceGroups(val source: SourceEntity, val groups: List<String>, val locked: Set<String>)

@OptIn(ExperimentalCoroutinesApi::class)
class ParentalViewModel(private val c: AppContainer) : ViewModel() {
    val sourceGroups: StateFlow<List<SourceGroups>> = c.sources.sources.flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(list.map { s -> combine(c.db.items().allGroups(s.id), c.db.lockedGroups().titles(s.id)) { g, l -> SourceGroups(s, g, l.toSet()) } }) { it.toList() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLocked(sourceId: Long, group: String, locked: Boolean) = viewModelScope.launch {
        if (locked) c.db.lockedGroups().add(LockedGroupEntity(sourceId, group)) else c.db.lockedGroups().remove(sourceId, group)
    }
}

@Composable
fun ParentalScreen(nav: Nav) {
    val context = LocalContext.current
    val lock = context.container.lock
    val hasPin by lock.hasPin.collectAsStateWithLifecycle()
    val unlocked by lock.unlocked.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.parental_title), onSearch = null, onBack = { nav.back() })
        when {
            hasPin == null -> Unit
            hasPin == false -> SetPin(lock)
            !unlocked -> EnterPin(lock)
            else -> Manage(lock)
        }
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit, last: Boolean = false) {
    OutlinedTextField(
        value, { onChange(it.filter(Char::isDigit).take(ParentalLock.MAX_PIN)) }, Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        shape = Mux.CardShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MuxTheme.Primary, unfocusedBorderColor = MuxTheme.Outline, focusedLabelColor = MuxTheme.Primary,
            cursorColor = MuxTheme.Accent, focusedContainerColor = MuxTheme.Surface, unfocusedContainerColor = MuxTheme.Surface,
        ),
    )
}

@Composable
private fun Message(text: String?) {
    // Announced by screen readers when it appears.
    Text(
        text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.Error,
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SetPin(lock: ParentalLock) {
    var pin by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val problem = when {
        pin.isNotEmpty() && !ParentalLock.isValid(pin) -> stringResource(R.string.parental_pin_invalid)
        repeat.isNotEmpty() && repeat != pin -> stringResource(R.string.parental_pin_mismatch)
        else -> null
    }
    Column(Modifier.padding(Mux.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.parental_set_intro), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
        PinField(stringResource(R.string.parental_pin), pin, { pin = it })
        PinField(stringResource(R.string.parental_pin_repeat), repeat, { repeat = it }, last = true)
        if (problem != null) Message(problem)
        PrimaryButton(
            stringResource(R.string.parental_set_pin), { scope.launch { lock.setPin(pin) } },
            Modifier.fillMaxWidth(), enabled = ParentalLock.isValid(pin) && pin == repeat,
        )
    }
}

@Composable
private fun EnterPin(lock: ParentalLock) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    fun unlock() = scope.launch {
        message = when (val r = lock.verify(pin)) {
            PinResult.Ok -> null
            is PinResult.Wrong -> res.getString(R.string.parental_wrong, r.triesLeft)
            is PinResult.LockedOut -> res.getString(R.string.parental_locked_out, DateUtils.formatElapsedTime(((r.untilMs - System.currentTimeMillis()) / MILLIS).coerceAtLeast(1)))
        }
        pin = ""
    }
    Column(Modifier.padding(Mux.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.parental_enter_intro), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
        PinField(stringResource(R.string.parental_pin), pin, { pin = it }, last = true)
        if (message != null) Message(message)
        PrimaryButton(stringResource(R.string.parental_unlock), ::unlock, Modifier.fillMaxWidth(), enabled = pin.length >= ParentalLock.MIN_PIN)
    }
}

@Composable
private fun Manage(lock: ParentalLock) {
    val context = LocalContext.current
    val vm: ParentalViewModel = viewModel { ParentalViewModel(context.container) }
    val sources by vm.sourceGroups.collectAsStateWithLifecycle()
    LazyColumn(Modifier.windowInsetsPadding(WindowInsets.navigationBars), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(Mux.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.parental_unlocked_note), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(stringResource(R.string.parental_lock_now), lock::lock)
                }
                ChangePin(lock)
            }
        }
        item {
            SectionHeader(stringResource(R.string.parental_groups), Modifier.padding(top = 12.dp))
            Text(
                stringResource(if (sources.isEmpty()) R.string.parental_no_groups else R.string.parental_groups_body),
                style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 4.dp),
            )
        }
        for (entry in sources) {
            item(key = "source-${entry.source.id}") {
                Text(
                    entry.source.name, style = MaterialTheme.typography.titleMedium, color = MuxTheme.Accent,
                    modifier = Modifier.padding(horizontal = Mux.ScreenPadding).padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(entry.groups, key = { "group-${entry.source.id}-$it" }) { group ->
                val locked = group in entry.locked
                Toggle(
                    group.ifEmpty { stringResource(R.string.live_ungrouped) },
                    stringResource(if (locked) R.string.parental_group_locked else R.string.parental_group_open),
                    locked,
                ) { vm.setLocked(entry.source.id, group, it) }
            }
        }
    }
}

@Composable
private fun ChangePin(lock: ParentalLock) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        SecondaryButton(stringResource(R.string.parental_change_pin), { open = true })
        return
    }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PinField(stringResource(R.string.parental_current_pin), current, { current = it })
        PinField(stringResource(R.string.parental_new_pin), next, { next = it })
        PinField(stringResource(R.string.parental_pin_repeat), repeat, { repeat = it }, last = true)
        if (message != null) Message(message)
        PrimaryButton(
            stringResource(R.string.parental_change_pin),
            {
                scope.launch {
                    when (val r = lock.verify(current)) {
                        PinResult.Ok -> {
                            lock.setPin(next)
                            message = null
                            open = false
                        }
                        is PinResult.Wrong -> message = res.getString(R.string.parental_wrong, r.triesLeft)
                        is PinResult.LockedOut ->
                            message = res.getString(R.string.parental_locked_out, DateUtils.formatElapsedTime(((r.untilMs - System.currentTimeMillis()) / MILLIS).coerceAtLeast(1)))
                    }
                }
            },
            Modifier.fillMaxWidth(), enabled = current.length >= ParentalLock.MIN_PIN && ParentalLock.isValid(next) && next == repeat,
        )
    }
}

private const val MILLIS = 1000L
