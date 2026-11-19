package com.muxlume.app.ui.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.muxlume.app.R
import com.muxlume.app.recording.RecordingWarning
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.CompletableDeferred

/**
 * Asks whether to record despite a [RecordingWarning]: the guide and the player both hand [confirm] to `Recordings.add`, so both ask
 * the same way. Leaving the screen cancels the pending question, and nothing is saved until it is answered.
 */
class RecordingPrompt {
    private var pending by mutableStateOf<Pair<RecordingWarning, CompletableDeferred<Boolean>>?>(null)

    suspend fun confirm(warning: RecordingWarning): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pending = warning to answer
        try {
            return answer.await()
        } finally {
            pending = null
        }
    }

    @Composable
    fun Dialog() {
        val (warning, answer) = pending ?: return
        AlertDialog(
            onDismissRequest = { answer.complete(false) },
            title = { Text(stringResource(R.string.recording_conflict_title)) },
            text = {
                Column {
                    warning.overLimit?.let { Text(pluralStringResource(R.plurals.recording_conflict_limit, it, it), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary) }
                    warning.shortened.forEach { s ->
                        val minutes = minutesOf(s.seconds)
                        val text = if (s.isNew) stringResource(R.string.recording_conflict_new_shortened, minutes) else stringResource(R.string.recording_conflict_old_shortened, s.title, minutes)
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
                    }
                }
            },
            confirmButton = { TextButton({ answer.complete(true) }) { Text(stringResource(R.string.recording_conflict_continue), color = MuxTheme.Primary) } },
            dismissButton = { TextButton({ answer.complete(false) }) { Text(stringResource(R.string.channel_cancel), color = MuxTheme.Primary) } },
            containerColor = MuxTheme.Raised,
        )
    }

    private fun minutesOf(seconds: Long) = maxOf(1L, (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
