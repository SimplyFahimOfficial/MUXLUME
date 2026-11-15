package com.muxlume.app.ui.player

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.playback.channelByNumber
import com.muxlume.app.playback.listAfterJump
import com.muxlume.app.ui.playChannel
import com.muxlume.app.ui.theme.MuxTheme

/** Digits typed for a channel number. The player commits them after a pause or on Enter (see PlayerScreen). */
class NumberEntry {
    var digits by mutableStateOf("")
        private set

    fun append(digit: Int) {
        if (digits.length < MAX_DIGITS) digits += digit
    }

    fun clear() { digits = "" }

    /** The number typed, or null if none; leading zeros don't matter. Clears the entry. */
    fun commit(): Int? = digits.toIntOrNull().also { clear() }

    companion object {
        const val MAX_DIGITS = 5
        const val PAUSE_MS = 2_500L

        /** 0 to 9 for a digit key or a numpad digit key. */
        fun digitOf(keyCode: Int): Int? = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
    }
}

/**
 * Plays the channel with [number] in the playlist of the channel that plays now, keeping the list it was picked from
 * where the target is in it. False if there is no such channel (nothing changes).
 */
suspend fun Context.jumpToChannel(player: Player, number: Int): Boolean {
    val (sourceId, _) = player.currentMediaItem?.mediaId?.let(MediaIds::parse) ?: return false
    val c = container
    val unlocked = c.lock.unlockedNow()
    val target = c.db.channelByNumber(sourceId, number, unlocked) ?: return false
    return playLike(player, target, unlocked)
}

/** Plays [target] ("previous channel" and number jumps) with the list the current channel came from. */
suspend fun Context.playLike(player: Player, target: ItemEntity, unlocked: Boolean): Boolean {
    val c = container
    val current = player.currentMediaItem
    val currentItem = current?.mediaId?.let(MediaIds::parse)?.let { (s, k) -> c.db.items().byKey(s, k, unlocked) }
    val list = if (current != null && currentItem != null) {
        listAfterJump(ChannelList.from(current.mediaMetadata.extras, currentItem), target, c.db.favorites().get(target.sourceId, target.stableKey) != null)
    } else {
        null
    }
    return playChannel(target, list)
}

/** A number pad for touch: shows what was typed, Go plays it. */
@Composable
fun NumberPadDialog(onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    val entry = remember { NumberEntry() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_go_to_channel)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val typed = entry.digits.ifEmpty { "–" }
                Text(typed, style = MaterialTheme.typography.headlineMedium, color = MuxTheme.TextPrimary, modifier = Modifier.semantics { contentDescription = typed })
                listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { d -> DigitKey(d.toString()) { entry.append(d) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitKey("⌫", stringResource(R.string.player_number_clear)) { entry.clear() }
                    DigitKey("0") { entry.append(0) }
                }
            }
        },
        confirmButton = { TextButton({ entry.commit()?.let(onGo) }, enabled = entry.digits.isNotEmpty()) { Text(stringResource(R.string.player_number_go), color = MuxTheme.Primary) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.channel_cancel), color = MuxTheme.Primary) } },
        containerColor = MuxTheme.Raised,
    )
}

@Composable
private fun DigitKey(label: String, description: String = label, onClick: () -> Unit) {
    TextButton(onClick, Modifier.semantics { contentDescription = description }) { Text(label, style = MaterialTheme.typography.titleLarge, color = MuxTheme.TextPrimary) }
}
