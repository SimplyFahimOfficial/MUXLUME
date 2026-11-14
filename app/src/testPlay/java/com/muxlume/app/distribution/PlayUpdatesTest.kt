package com.muxlume.app.distribution

import com.google.android.play.core.install.model.UpdateAvailability
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val CODE = 42

class PlayUpdatesTest {
    @Test
    fun anAvailableUpdatePlayLetsUsStartImmediatelyIsOfferedWithItsVersionCode() {
        assertThat(playUpdate(UpdateAvailability.UPDATE_AVAILABLE, immediateAllowed = true, versionCode = CODE)).isNotNull()
        assertThat(playUpdate(UpdateAvailability.UPDATE_AVAILABLE, true, CODE)!!.version).isEqualTo("42")
        assertThat(playUpdate(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, true, CODE)).isNotNull() // resume it
    }

    @Test
    fun noUpdateAnUnknownStateOrOneThatCannotBeStartedImmediatelyGivesNothingToDo() {
        assertThat(playUpdate(UpdateAvailability.UPDATE_NOT_AVAILABLE, true, CODE)).isNull()
        assertThat(playUpdate(UpdateAvailability.UNKNOWN, true, CODE)).isNull()
        assertThat(playUpdate(UpdateAvailability.UPDATE_AVAILABLE, immediateAllowed = false, versionCode = CODE)).isNull()
    }
}
