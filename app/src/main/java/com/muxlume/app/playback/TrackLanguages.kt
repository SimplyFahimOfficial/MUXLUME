package com.muxlume.app.playback

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/**
 * The user's default audio and subtitle language, applied to the player's track selection. A setting is one of:
 * null (Automatic: the player's own choice, which is what the app did before this setting existed), [DEVICE], [OFF]
 * (subtitles only), or a language tag from [CHOICES]. A track the user picks by hand in the player options still wins
 * for that stream, because it is an override on top of these preferences.
 */
object TrackLanguages {
    const val DEVICE = "device"
    const val OFF = "off"
    const val AUTOMATIC_IN_BACKUP = "auto"

    val CHOICES = listOf("en", "hi", "es", "fr", "de", "it", "pt", "ar", "ru", "tr", "bn", "ta", "te", "mr", "ur", "id", "ja", "ko", "zh")

    /** True for a value a setting may hold; used where it comes from a backup file. */
    fun isValid(value: String, subtitles: Boolean) = value == DEVICE || value in CHOICES || (subtitles && value == OFF)

    /** Nothing to apply: both are Automatic. */
    fun isAutomatic(audio: String?, subtitles: String?) = audio == null && subtitles == null

    /** What to apply: the two settings, the phone's language, and whether this code turned subtitles off earlier. */
    class Wanted(val audio: String?, val subtitles: String?, val device: String, val subtitlesWereOff: Boolean)

    /**
     * [current] with [wanted] applied. [base] is what the player started with; Automatic puts its values back, so going from a
     * language to Automatic undoes the change. `subtitlesWereOff` makes this code, and not the user's own choice, the one that
     * turns subtitles back on.
     */
    @Suppress("SpreadOperator") // media3's setters only take varargs; the lists are one to a few entries
    fun apply(current: TrackSelectionParameters, base: TrackSelectionParameters, wanted: Wanted): TrackSelectionParameters {
        val builder = current.buildUpon()
        builder.setPreferredAudioLanguages(*languages(wanted.audio, wanted.device, base.preferredAudioLanguages))
        builder.setPreferredTextLanguages(*languages(wanted.subtitles.takeIf { it != OFF }, wanted.device, base.preferredTextLanguages))
        if (wanted.subtitles == OFF) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else if (wanted.subtitlesWereOff) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        return builder.build()
    }

    private fun languages(setting: String?, device: String, base: List<String>): Array<String> = when (setting) {
        null -> base.toTypedArray()
        DEVICE -> arrayOf(device)
        else -> arrayOf(setting)
    }
}
