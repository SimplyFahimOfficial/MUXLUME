package com.muxlume.app.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.muxlume.app.core.net.HttpPolicy
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * How the player's requests are made: through the app's gated client, with the headers and the HTTP setting of the source that is
 * playing. Stream, playlist and segment requests run on loader threads and read the current values when the call is made, so one
 * source's opt-in and headers never apply to the streams of another source once the player has moved on.
 */
@UnstableApi
internal class StreamRequests(private val client: OkHttpClient) {
    @Volatile private var policy = HttpPolicy.HTTPS_ONLY
    @Volatile private var stillAllowed: () -> Boolean = { true }

    val factory = OkHttpDataSource.Factory(Call.Factory { req -> client.newCall(req.newBuilder().tag(HttpPolicy::class.java, current()).build()) })

    /** The setting at the time of the request: allowing HTTP is withdrawn as soon as the source no longer allows it ([stillAllowed]). */
    private fun current() = if (policy.allowHttp && !stillAllowed()) policy.copy(allowHttp = false) else policy

    /** What every request of the item that plays now carries: the playlist's headers and its source's HTTP setting. */
    fun use(headers: Map<String, String>, policy: HttpPolicy, stillAllowed: () -> Boolean = { true }) {
        factory.setDefaultRequestProperties(headers)
        this.policy = policy
        this.stillAllowed = stillAllowed
    }
}
