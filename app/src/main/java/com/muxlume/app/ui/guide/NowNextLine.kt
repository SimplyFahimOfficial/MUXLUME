package com.muxlume.app.ui.guide

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.epg.EpgTime
import com.muxlume.app.data.epg.NowNext
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.delay

private const val TICK_MS = 60_000L

/** What is on this channel now, re-read every minute. Null while off, unmatched or not loaded. */
@Composable
fun rememberNowNext(sourceId: Long, stableKey: String, enabled: Boolean): State<NowNext?> {
    val epg = LocalContext.current.container.epg
    return produceState<NowNext?>(null, sourceId, stableKey, enabled) {
        if (!enabled) return@produceState
        while (true) {
            value = epg.nowNext(sourceId, stableKey)
            delay(TICK_MS)
        }
    }
}

/** "20:00 News" for the running programme, else "Next: ..." for the upcoming one. */
@Composable
fun NowNextLine(nn: NowNext?) {
    val now = nn?.now
    val text = when {
        now != null -> "${EpgTime.time(now.start)} ${now.title}"
        nn?.next != null -> stringResource(R.string.guide_next, "${EpgTime.time(nn.next.start)} ${nn.next.title}")
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
