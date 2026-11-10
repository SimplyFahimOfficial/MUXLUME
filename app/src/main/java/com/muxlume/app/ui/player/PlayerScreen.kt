package com.muxlume.app.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import com.muxlume.app.MainActivity
import com.muxlume.app.container
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.data.AppSettings
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.ui.components.LiveBadge
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.theme.Motion
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.SectionLabel
import kotlinx.coroutines.delay

/** Snapshot of the controller state the screen needs, updated by one listener. */
@OptIn(UnstableApi::class)
private class PlayerUi {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var state by mutableIntStateOf(Player.STATE_IDLE)
    var error by mutableStateOf<PlaybackException?>(null)
    var live by mutableStateOf(true)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var hasNext by mutableStateOf(false)
    var hasPrevious by mutableStateOf(false)
    var tracks by mutableStateOf(Tracks.EMPTY)
    var cues by mutableStateOf(CueGroup.EMPTY_TIME_ZERO)
    var speed by mutableFloatStateOf(1f)

    fun read(p: Player) {
        val meta = p.mediaMetadata
        title = meta.title?.toString().orEmpty()
        subtitle = meta.artist?.toString()
        isPlaying = p.isPlaying
        state = p.playbackState
        error = p.playerError
        live = p.currentMediaItem?.let { MediaIds.kindOf(it) == ItemKind.LIVE } ?: true
        duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        position = p.currentPosition
        hasNext = p.hasNextMediaItem()
        hasPrevious = p.hasPreviousMediaItem()
        tracks = p.currentTracks
        cues = p.currentCues
        speed = p.playbackParameters.speed
    }
}

private val scales = listOf(ContentScale.Fit to "Fit", ContentScale.Crop to "Fill", ContentScale.FillBounds to "Stretch")

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val player by produceState<Player?>(null) { value = runCatching { context.container.player() }.getOrNull() }
    val inPip by (activity?.inPictureInPicture ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsStateWithLifecycle()
    val settings by context.container.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val ui = remember { PlayerUi() }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var scaleIndex by remember { mutableIntStateOf(0) }
    var portrait by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val reduced = MuxTheme.reducedMotion
    val view = LocalView.current
    val focus = remember { FocusRequester() }

    val p = player
    DisposableEffect(p) {
        if (p == null) return@DisposableEffect onDispose { }
        ui.read(p)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = ui.read(player)
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    // Position ticks only while the bar is visible and something is playing.
    LaunchedEffect(p, controls, ui.isPlaying) {
        while (p != null && controls && ui.isPlaying) {
            ui.position = p.currentPosition
            delay(500)
        }
    }
    // Controls fade out after a few seconds of playback without interaction.
    LaunchedEffect(controls, interaction, ui.isPlaying) {
        if (controls && ui.isPlaying && !sheet) {
            delay(3_500)
            controls = false
        }
    }
    // Full screen: system bars hidden, landscape unless the user rotates to portrait, screen kept on.
    DisposableEffect(activity, portrait) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(ui.isPlaying) {
        view.keepScreenOn = ui.isPlaying
        activity?.autoPip = ui.isPlaying
        onDispose {
            view.keepScreenOn = false
            activity?.autoPip = false
        }
    }
    // Leaving the app pauses playback unless background audio is on or the video is in picture-in-picture.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, p, settings.backgroundPlayback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !settings.backgroundPlayback && activity?.isInPictureInPictureMode != true) p?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler {
        if (!settings.backgroundPlayback) p?.pause()
        onBack()
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun poke() { controls = true; interaction++ }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> { p?.seekToNextMediaItem(); poke(); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { p?.seekToPreviousMediaItem(); poke(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls) { poke(); true } else false
                    else -> if (!controls) { poke(); false } else false
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Show or hide controls") {
                if (controls) controls = false else poke()
            },
    ) {
        if (p != null) {
            ContentFrame(player = p, modifier = Modifier.fillMaxSize(), contentScale = scales[scaleIndex].first)
            AndroidView(
                factory = { SubtitleView(it).apply { setUserDefaultStyle(); setUserDefaultTextSize() } },
                update = { it.setCues(ui.cues.cues) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (ui.state == Player.STATE_BUFFERING && ui.error == null) {
            var show by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(400); show = true } // no spinner flash on quick rebuffers
            if (show) CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Mux.Cream, strokeWidth = 3.dp)
        }
        ui.error?.let { e ->
            ErrorPanel(e, onRetry = { p?.prepare(); p?.play() }, onBack = onBack, modifier = Modifier.align(Alignment.Center))
        }
        AnimatedVisibility(
            visible = controls && !inPip && ui.error == null,
            enter = fadeIn(Motion.spec(Motion.SHORT, reduced)),
            exit = fadeOut(Motion.spec(Motion.MEDIUM, reduced)),
        ) {
            Controls(
                ui, p,
                scaleLabel = scales[scaleIndex].second,
                onBack = { if (!settings.backgroundPlayback) p?.pause(); onBack() },
                onScale = { scaleIndex = (scaleIndex + 1) % scales.size; poke() },
                onPip = { if (activity?.enterPip() != true) Toast.makeText(context, "Picture-in-picture isn't available on this device or is turned off for MUXLUME.", Toast.LENGTH_LONG).show() },
                onRotate = { portrait = !portrait; poke() },
                onOptions = { sheet = true },
                poke = ::poke,
            )
        }
    }

    if (sheet && p != null) {
        ModalBottomSheet(onDismissRequest = { sheet = false; poke() }, containerColor = Mux.Surface) {
            OptionsSheet(ui, p)
        }
    }
}

@Composable
private fun Controls(
    ui: PlayerUi, p: Player?, scaleLabel: String,
    onBack: () -> Unit, onScale: () -> Unit, onPip: () -> Unit, onRotate: () -> Unit, onOptions: () -> Unit, poke: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Scrims only behind the top and bottom bars, so the picture stays clean.
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Mux.Scrim, Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Mux.Scrim))))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mux.White) }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(ui.title, style = MaterialTheme.typography.titleLarge, color = Mux.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Mux.Cream.copy(alpha = 0.8f), maxLines = 1) }
                }
                IconButton(onOptions) { Icon(Icons.Rounded.Tune, "Audio, subtitles and info", tint = Mux.White) }
                IconButton(onScale, Modifier.semantics { contentDescription = "Picture size: $scaleLabel" }) { Icon(Icons.Rounded.AspectRatio, null, tint = Mux.White) }
                IconButton(onRotate) { Icon(Icons.Rounded.ScreenRotation, "Rotate", tint = Mux.White) }
                IconButton(onPip) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Mux.White) }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val roundButton = Modifier.size(56.dp).clip(Mux.PillShape).background(Mux.Scrim)
                if (ui.live) {
                    IconButton({ p?.seekToPreviousMediaItem(); poke() }, roundButton, enabled = ui.hasPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous channel", tint = Mux.White) }
                } else {
                    IconButton({ p?.seekBack(); poke() }, roundButton) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Mux.White) }
                }
                Spacer(Modifier.width(28.dp))
                IconButton(
                    { if (ui.isPlaying) p?.pause() else p?.play(); poke() },
                    Modifier.size(76.dp).clip(Mux.PillShape).background(Mux.Cream),
                ) { Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.isPlaying) "Pause" else "Play", tint = Mux.Black, modifier = Modifier.size(40.dp)) }
                Spacer(Modifier.width(28.dp))
                if (ui.live) {
                    IconButton({ p?.seekToNextMediaItem(); poke() }, roundButton, enabled = ui.hasNext) { Icon(Icons.Rounded.SkipNext, "Next channel", tint = Mux.White) }
                } else {
                    IconButton({ p?.seekForward(); poke() }, roundButton) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Mux.White) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (ui.live) {
                LiveBadge(Modifier.padding(8.dp))
            } else if (ui.duration > 0) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it; poke() },
                    onValueChangeFinished = { dragging?.let { f -> p?.seekTo((f * ui.duration).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = Mux.Cream, activeTrackColor = Mux.Orange, inactiveTrackColor = Mux.Outline),
                    modifier = Modifier.semantics { contentDescription = "Playback position" },
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text(time(dragging?.let { (it * ui.duration).toLong() } ?: ui.position), color = Mux.White, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(time(ui.duration), color = Mux.TextSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(e: PlaybackException, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(24.dp).clip(Mux.CardShape).background(Mux.Surface).padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't play this stream", style = MaterialTheme.typography.titleLarge, color = Mux.White)
        Spacer(Modifier.height(8.dp))
        Text(explain(e), style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Back", onBack)
            PrimaryButton("Retry", onRetry)
        }
    }
}

/** Plain-language cause; never the stream URL. */
private fun explain(e: PlaybackException): String {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is CleartextBlockedException) return "This stream uses unencrypted HTTP. Edit the playlist in Library and allow HTTP to play it."
        cause = cause.cause
    }
    return when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT ->
            "The connection to the stream was lost. MUXLUME retries a few times automatically."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused this stream. Your account may have reached its connection limit, or the channel is offline."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The stream wasn't found. The channel may have moved; try refreshing the playlist."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "This stream's format isn't supported."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "This device can't decode the stream's video or audio format."
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED, PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
            "This stream is DRM-protected, which MUXLUME doesn't support."
        else -> "The stream couldn't be played (${e.errorCodeName})."
    }
}

@Composable
private fun OptionsSheet(ui: PlayerUi, p: Player) {
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Mux.ScreenPadding).padding(bottom = 32.dp)) {
        TrackSection("Audio", C.TRACK_TYPE_AUDIO, ui.tracks, p, allowOff = false)
        TrackSection("Subtitles", C.TRACK_TYPE_TEXT, ui.tracks, p, allowOff = true)
        if (!ui.live) {
            Text("SPEED", style = SectionLabel, color = Mux.TextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            com.muxlume.app.ui.components.PillRow(
                listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"),
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).indexOf(ui.speed),
                { i -> p.setPlaybackSpeed(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)[i]) },
            )
        }
        Text("PLAYBACK INFO", style = SectionLabel, color = Mux.TextSecondary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        val info = diagnostics(ui, p)
        Text(info, style = MaterialTheme.typography.bodySmall, color = Mux.Cream)
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Copy playback info", {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("MUXLUME playback info", info))
            Toast.makeText(context, "Copied. It contains no links or credentials.", Toast.LENGTH_SHORT).show()
        })
    }
}

@Composable
private fun TrackSection(title: String, type: Int, tracks: Tracks, p: Player, allowOff: Boolean) {
    val groups = tracks.groups.filter { it.type == type && it.isSupported }
    if (groups.isEmpty() && !allowOff) return
    Text(title.uppercase(), style = SectionLabel, color = Mux.TextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    val options = buildList {
        if (allowOff) add(Triple("Off", null as Tracks.Group?, -1))
        groups.forEach { g -> for (i in 0 until g.length) if (g.isTrackSupported(i)) add(Triple(label(g, i), g, i)) }
    }
    val disabled = type in p.trackSelectionParameters.disabledTrackTypes
    val selected = options.indexOfFirst { (_, g, i) -> if (g == null) disabled || groups.none { it.isSelected } else !disabled && g.isTrackSelected(i) }
    if (options.size <= 1 && allowOff && groups.isEmpty()) {
        Text("None in this stream", style = MaterialTheme.typography.bodyMedium, color = Mux.TextSecondary)
        return
    }
    com.muxlume.app.ui.components.PillRow(options.map { it.first }, selected, { idx ->
        val (_, g, i) = options[idx]
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            if (g == null) setTrackTypeDisabled(type, true)
            else setTrackTypeDisabled(type, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
        }.build()
    })
}

private fun label(g: Tracks.Group, i: Int): String {
    val f = g.getTrackFormat(i)
    val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
    val extra = if (f.channelCount > 0) when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" } else null
    return listOfNotNull(f.label ?: lang, extra).joinToString(" ").ifEmpty { "Track ${i + 1}" }
}

/** Technical details for troubleshooting. Deliberately excludes URLs, ids and credentials. */
@OptIn(UnstableApi::class)
private fun diagnostics(ui: PlayerUi, p: Player): String {
    val video = ui.tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
    val audio = ui.tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
    val size = p.videoSize
    return listOfNotNull(
        "Type: ${if (ui.live) "live" else "on demand"}",
        "State: ${when (ui.state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" }}",
        if (size.width > 0) "Video: ${size.width}×${size.height}${video?.frameRate?.takeIf { it > 0 }?.let { " @ ${"%.0f".format(it)} fps" } ?: ""}" else null,
        video?.codecs?.let { "Video codec: $it" } ?: video?.sampleMimeType?.let { "Video format: $it" },
        video?.bitrate?.takeIf { it > 0 }?.let { "Video bitrate: ${it / 1000} kbps" },
        audio?.let { "Audio: ${it.codecs ?: it.sampleMimeType ?: "?"}, ${it.channelCount} ch, ${it.sampleRate} Hz" },
        "Buffered: ${(p.totalBufferedDuration / 1000)} s",
        ui.error?.let { "Last error: ${it.errorCodeName}" },
        "App: MUXLUME ${com.muxlume.app.BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}",
    ).joinToString("\n")
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
