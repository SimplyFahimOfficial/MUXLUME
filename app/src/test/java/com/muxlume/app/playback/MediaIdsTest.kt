package com.muxlume.app.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaIdsTest {
    private val item = ItemEntity(sourceId = 2, kind = ItemKind.LIVE, stableKey = "k", name = "BBC One", groupTitle = "UK", logo = null, url = "https://x/k", position = 0)

    @Test
    fun `a channel is its own primary, a server carries the channel the user chose, and never a URL`() {
        val own = MediaIds.request(item)
        assertThat(own.primaryId).isEqualTo("2|k")
        val server = MediaIds.request(item, primary = "1|chosen")
        assertThat(server.mediaId).isEqualTo("2|k")
        assertThat(server.primaryId).isEqualTo("1|chosen")
        assertThat(server.localConfiguration).isNull() // the service resolves the stream, the request has none
    }
}
