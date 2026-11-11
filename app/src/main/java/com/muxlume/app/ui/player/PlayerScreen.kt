package com.muxlume.app.ui.player

import com.muxlume.app.R
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.platform.LocalResources
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
import com.muxlume.app.ui.theme.OnMedia
import com.muxlume.app.ui.theme.Motion
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.SectionLabel
import com.muxlume.app.ui.guide.NowNextLine
import com.muxlume.app.ui.guide.rememberNowNext
import kotlinx.coroutines.delay

/** Snapshot of the controller state the screen needs, updated by one listener. */
@OptIn(UnstableApi::class)
private class PlayerUi {
    var title by mutableStateOf("")
    var mediaId by mutableStateOf<String?>(null)
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
        mediaId = p.currentMediaItem?.mediaId
        subtitle = meta.artist?.toString()
        isPlaying = p.isPlaying
        state = p.playbackState
        error = p.playerError
        live = p.currentMediaItem?.let { MediaIds.kindOf(it) == ItemKind.LIVE } ?: true
        duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        position = p.currentPosition
        // Live channels switch on demand (the service looks up the neighbour), so there's no queue to check.
        hasNext = live || p.hasNextMediaItem()
        hasPrevious = live || p.hasPreviousMediaItem()
        tracks = p.currentTracks
        cues = p.currentCues
        speed = p.playbackParameters.speed
    }
}

private val scales = listOf(ContentScale.Fit to R.string.player_fit, ContentScale.Crop to R.string.player_fill, ContentScale.FillBounds to R.string.player_stretch)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
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

    // The player always sits on dark video; force the dark palette regardless of the app theme.
    OnMedia {
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
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(R.string.player_show_or_hide_controls)) {
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
                if (show) CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = MuxTheme.Primary, strokeWidth = 3.dp)
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
                    scaleLabel = stringResource(scales[scaleIndex].second),
                    onBack = { if (!settings.backgroundPlayback) p?.pause(); onBack() },
                    onScale = { scaleIndex = (scaleIndex + 1) % scales.size; poke() },
                    onPip = { if (activity?.enterPip() != true) Toast.makeText(context, res.getString(R.string.player_picture_in_picture_isn_t), Toast.LENGTH_LONG).show() },
                    onRotate = { portrait = !portrait; poke() },
                    onOptions = { sheet = true },
                    poke = ::poke,
                )
            }
        }

        if (sheet && p != null) {
            ModalBottomSheet(onDismissRequest = { sheet = false; poke() }, containerColor = MuxTheme.Surface) {
                OptionsSheet(ui, p)
            }
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
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(MuxTheme.Scrim, Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, MuxTheme.Scrim))))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.player_back), tint = MuxTheme.TextPrimary) }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(ui.title, style = MaterialTheme.typography.titleLarge, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MuxTheme.Primary.copy(alpha = 0.8f), maxLines = 1) }
                    if (ui.live) ui.mediaId?.let(MediaIds::parse)?.let { (sourceId, key) -> NowNextLine(rememberNowNext(sourceId, key, true).value) }
                }
                IconButton(onOptions) { Icon(Icons.Rounded.Tune, stringResource(R.string.player_audio_subtitles_and_info), tint = MuxTheme.TextPrimary) }
                val sizeLabel = stringResource(R.string.player_picture_size, scaleLabel)
                IconButton(onScale, Modifier.semantics { contentDescription = sizeLabel }) { Icon(Icons.Rounded.AspectRatio, null, tint = MuxTheme.TextPrimary) }
                IconButton(onRotate) { Icon(Icons.Rounded.ScreenRotation, stringResource(R.string.player_rotate), tint = MuxTheme.TextPrimary) }
                IconButton(onPip) { Icon(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.player_picture_in_picture), tint = MuxTheme.TextPrimary) }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val roundButton = Modifier.size(56.dp).clip(Mux.PillShape).background(MuxTheme.Scrim)
                if (ui.live) {
                    IconButton(
                        { p?.seekToPreviousMediaItem(); poke() },
                        roundButton,
                        enabled = ui.hasPrevious,
                    ) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous_channel), tint = MuxTheme.TextPrimary) }
                } else {
                    IconButton({ p?.seekBack(); poke() }, roundButton) { Icon(Icons.Rounded.Replay10, stringResource(R.string.player_back_10_seconds), tint = MuxTheme.TextPrimary) }
                }
                Spacer(Modifier.width(28.dp))
                IconButton(
                    { if (ui.isPlaying) p?.pause() else p?.play(); poke() },
                    Modifier.size(76.dp).clip(Mux.PillShape).background(MuxTheme.Primary),
                ) { Icon(
                    if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (ui.isPlaying) R.string.player_pause else R.string.player_play),
                    tint = MuxTheme.OnPrimary,
                    modifier = Modifier.size(40.dp),
                ) }
                Spacer(Modifier.width(28.dp))
                if (ui.live) {
                    IconButton(
                        { p?.seekToNextMediaItem(); poke() },
                        roundButton,
                        enabled = ui.hasNext,
                    ) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next_channel), tint = MuxTheme.TextPrimary) }
                } else {
                    IconButton({ p?.seekForward(); poke() }, roundButton) { Icon(Icons.Rounded.Forward10, stringResource(R.string.player_forward_10_seconds), tint = MuxTheme.TextPrimary) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (ui.live) {
                LiveBadge(Modifier.padding(8.dp))
            } else if (ui.duration > 0) {
                val positionLabel = stringResource(R.string.player_playback_position)
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it; poke() },
                    onValueChangeFinished = { dragging?.let { f -> p?.seekTo((f * ui.duration).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = MuxTheme.Primary, activeTrackColor = MuxTheme.Accent, inactiveTrackColor = MuxTheme.Outline),
                    modifier = Modifier.semantics { contentDescription = positionLabel },
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text(time(dragging?.let { (it * ui.duration).toLong() } ?: ui.position), color = MuxTheme.TextPrimary, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(time(ui.duration), color = MuxTheme.TextSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(e: PlaybackException, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(24.dp).clip(Mux.CardShape).background(MuxTheme.Surface).padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.player_can_t_play_this_stream), style = MaterialTheme.typography.titleLarge, color = MuxTheme.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(explain(e, LocalResources.current), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.player_back), onBack)
            PrimaryButton(stringResource(R.string.player_retry), onRetry)
        }
    }
}

/** Plain-language cause; never the stream URL. */
private fun explain(e: PlaybackException, res: Resources): String {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is CleartextBlockedException) return res.getString(R.string.player_this_stream_uses_unencrypted_http)
        cause = cause.cause
    }
    return when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT ->
            res.getString(R.string.player_the_connection_to_the_stream)
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> res.getString(R.string.player_the_provider_refused_this_stream)
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> res.getString(R.string.player_the_stream_wasn_t_found)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            res.getString(R.string.player_this_stream_s_format_isn)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            res.getString(R.string.player_this_device_can_t_decode)
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED, PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
            res.getString(R.string.player_this_stream_is_drm_protected)
        else -> res.getString(R.string.player_the_stream_couldn_t_be, e.errorCodeName)
    }
}

@Composable
private fun OptionsSheet(ui: PlayerUi, p: Player) {
    val context = LocalContext.current
    val res = LocalResources.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Mux.ScreenPadding).padding(bottom = 32.dp)) {
        TrackSection(stringResource(R.string.player_audio), C.TRACK_TYPE_AUDIO, ui.tracks, p, allowOff = false)
        TrackSection(stringResource(R.string.player_subtitles), C.TRACK_TYPE_TEXT, ui.tracks, p, allowOff = true)
        if (!ui.live) {
            Text(stringResource(R.string.player_speed), style = SectionLabel, color = MuxTheme.TextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            com.muxlume.app.ui.components.PillRow(
                listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"),
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).indexOf(ui.speed),
                { i -> p.setPlaybackSpeed(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)[i]) },
            )
        }
        Text(stringResource(R.string.player_playback_info), style = SectionLabel, color = MuxTheme.TextSecondary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        val info = diagnostics(ui, p, res)
        Text(info, style = MaterialTheme.typography.bodySmall, color = MuxTheme.Primary)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.player_copy_playback_info), {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(res.getString(R.string.player_muxlume_playback_info), info))
            Toast.makeText(context, res.getString(R.string.player_copied_it_contains_no_links), Toast.LENGTH_SHORT).show()
        })
    }
}

@Composable
private fun TrackSection(title: String, type: Int, tracks: Tracks, p: Player, allowOff: Boolean) {
    val groups = tracks.groups.filter { it.type == type && it.isSupported }
    if (groups.isEmpty() && !allowOff) return
    Text(title.uppercase(), style = SectionLabel, color = MuxTheme.TextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    val res = LocalResources.current
    val options = buildList {
        if (allowOff) add(Triple(stringResource(R.string.player_off), null as Tracks.Group?, -1))
        groups.forEach { g -> for (i in 0 until g.length) if (g.isTrackSupported(i)) add(Triple(label(g, i, res), g, i)) }
    }
    val disabled = type in p.trackSelectionParameters.disabledTrackTypes
    val selected = options.indexOfFirst { (_, g, i) -> if (g == null) disabled || groups.none { it.isSelected } else !disabled && g.isTrackSelected(i) }
    if (options.size <= 1 && allowOff && groups.isEmpty()) {
        Text(stringResource(R.string.player_none_in_this_stream), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
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

private fun label(g: Tracks.Group, i: Int, res: Resources): String {
    val f = g.getTrackFormat(i)
    val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
    val extra = if (f.channelCount > 0) when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" } else null
    return listOfNotNull(f.label ?: lang, extra).joinToString(" ").ifEmpty { res.getString(R.string.player_track, i + 1) }
}

/** The playing track of [type], if one is selected. */
private fun selectedFormat(tracks: Tracks, type: Int): androidx.media3.common.Format? =
    tracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }

/** "Video codec: …", or the container's format when the codec string is missing. */
private fun codecLine(f: androidx.media3.common.Format): Pair<Int, String>? =
    f.codecs?.let { R.string.player_video_codec to it } ?: f.sampleMimeType?.let { R.string.player_video_format to it }

@StringRes
private fun stateName(state: Int): Int = when (state) {
    Player.STATE_BUFFERING -> R.string.player_info_buffering
    Player.STATE_READY -> R.string.player_info_ready
    Player.STATE_ENDED -> R.string.player_info_ended
    else -> R.string.player_info_idle
}

private fun videoLine(res: Resources, width: Int, height: Int, frameRate: Float?): String =
    if (frameRate != null && frameRate > 0) res.getString(R.string.player_info_video_fps, width, height, "%.0f".format(frameRate))
    else res.getString(R.string.player_info_video, width, height)

/** Technical details for troubleshooting. Deliberately excludes URLs, ids and credentials. */
@OptIn(UnstableApi::class)
private fun diagnostics(ui: PlayerUi, p: Player, res: Resources): String {
    val video = selectedFormat(ui.tracks, C.TRACK_TYPE_VIDEO)
    val audio = selectedFormat(ui.tracks, C.TRACK_TYPE_AUDIO)
    val size = p.videoSize
    return listOfNotNull(
        res.getString(R.string.player_info_type, res.getString(if (ui.live) R.string.player_info_live else R.string.player_info_on_demand)),
        res.getString(R.string.player_info_state, res.getString(stateName(ui.state))),
        if (size.width > 0) videoLine(res, size.width, size.height, video?.frameRate) else null,
        video?.let(::codecLine)?.let { (id, value) -> res.getString(id, value) },
        video?.bitrate?.takeIf { it > 0 }?.let { res.getString(R.string.player_video_bitrate_kbps, it / 1000) },
        audio?.let { res.getString(R.string.player_info_audio, it.codecs ?: it.sampleMimeType ?: "?", it.channelCount, it.sampleRate) },
        res.getString(R.string.player_buffered_s, (p.totalBufferedDuration / 1000)),
        ui.error?.let { res.getString(R.string.player_last_error, it.errorCodeName) },
        res.getString(R.string.player_app_muxlume_android, com.muxlume.app.BuildConfig.VERSION_NAME, android.os.Build.VERSION.RELEASE, android.os.Build.MODEL),
    ).joinToString("\n")
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
