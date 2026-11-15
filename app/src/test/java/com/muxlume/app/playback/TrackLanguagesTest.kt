package com.muxlume.app.playback

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackLanguagesTest {
    // What the player starts with: German audio and English text preferred, as a device in Germany might.
    private val base = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
        .setPreferredAudioLanguages("de").setPreferredTextLanguages("en").build()

    private fun apply(audio: String?, subtitles: String?, current: TrackSelectionParameters = base, wereOff: Boolean = false) =
        TrackLanguages.apply(current, base, TrackLanguages.Wanted(audio, subtitles, device = "fr", subtitlesWereOff = wereOff))

    @Test
    fun `automatic changes nothing`() {
        val p = apply(null, null)
        assertThat(p.preferredAudioLanguages).containsExactly("de")
        assertThat(p.preferredTextLanguages).containsExactly("en")
        assertThat(p.disabledTrackTypes).isEmpty()
        assertThat(TrackLanguages.isAutomatic(null, null)).isTrue()
        assertThat(TrackLanguages.isAutomatic("en", null)).isFalse()
    }

    @Test
    fun `a language replaces the preference, the device language uses the phone's`() {
        assertThat(apply("hi", null).preferredAudioLanguages).containsExactly("hi")
        assertThat(apply("hi", null).preferredTextLanguages).containsExactly("en") // the other one stays automatic
        assertThat(apply(TrackLanguages.DEVICE, TrackLanguages.DEVICE).let { it.preferredAudioLanguages to it.preferredTextLanguages })
            .isEqualTo(listOf("fr") to listOf("fr"))
    }

    @Test
    fun `subtitles off disables text, and only this function turns it back on`() {
        val off = apply(null, TrackLanguages.OFF)
        assertThat(off.disabledTrackTypes).contains(C.TRACK_TYPE_TEXT)
        assertThat(off.preferredTextLanguages).containsExactly("en") // nothing to prefer while they are off

        // Back to a language: re-enabled because we had disabled them.
        assertThat(apply(null, "es", current = off, wereOff = true).disabledTrackTypes).doesNotContain(C.TRACK_TYPE_TEXT)
        // The user turned them off by hand (we did not): a language setting does not undo that.
        val byHand = base.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        assertThat(apply(null, "es", current = byHand, wereOff = false).disabledTrackTypes).contains(C.TRACK_TYPE_TEXT)
    }

    @Test
    fun `going back to automatic restores what the player started with`() {
        val changed = apply("hi", "es")
        val restored = apply(null, null, current = changed)
        assertThat(restored.preferredAudioLanguages).containsExactly("de")
        assertThat(restored.preferredTextLanguages).containsExactly("en")
    }

    @Test
    fun `an override the user chose by hand is kept`() {
        val group = androidx.media3.common.TrackGroup(androidx.media3.common.Format.Builder().setLanguage("ta").setSampleMimeType("audio/mp4a-latm").build())
        val withOverride = base.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(group, 0)).build()
        val p = apply("hi", null, current = withOverride)
        assertThat(p.overrides).isNotEmpty()
        assertThat(p.preferredAudioLanguages).containsExactly("hi")
    }

    @Test
    fun `values from a backup are checked`() {
        assertThat(TrackLanguages.isValid("en", subtitles = false)).isTrue()
        assertThat(TrackLanguages.isValid(TrackLanguages.DEVICE, subtitles = false)).isTrue()
        assertThat(TrackLanguages.isValid(TrackLanguages.OFF, subtitles = true)).isTrue()
        assertThat(TrackLanguages.isValid(TrackLanguages.OFF, subtitles = false)).isFalse() // there is no "audio off"
        assertThat(TrackLanguages.isValid("xx", subtitles = true)).isFalse()
        assertThat(TrackLanguages.isValid("en; DROP", subtitles = true)).isFalse()
    }
}
