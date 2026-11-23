package com.muxlume.app.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.muxlume.app.core.net.HttpPolicy
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * How the player's requests are made: through the app's gated client, with the headers and the HTTP setting of the source that is
 * playing. Stream, playlist and segment requests run on loader threads and read the current values when the call is made, as one value:
 * a request never mixes two items' headers and setting. A request made after a switch takes the new item's values; the player
 * releases the old item, which bounds that window.
 */
@UnstableApi
internal class StreamRequests(private val client: OkHttpClient) {
    /** What the requests of the item that plays carry, replaced as one value so a request never mixes two items' headers and setting. */
    private class Item(val headers: Map<String, String>, val policy: HttpPolicy, val stillAllowed: () -> Boolean)

    @Volatile private var item = Item(emptyMap(), HttpPolicy.HTTPS_ONLY) { true }

    val factory = OkHttpDataSource.Factory(
        Call.Factory { req ->
            val now = item
            // Allowing HTTP is withdrawn as soon as the source no longer allows it.
            val policy = if (now.policy.allowHttp && !now.stillAllowed()) now.policy.copy(allowHttp = false) else now.policy
            client.newCall(now.headers.entries.fold(req.newBuilder()) { b, (name, value) -> b.header(name, value) }.tag(HttpPolicy::class.java, policy).build())
        },
    )

    /** What every request of the item that plays now carries: the playlist's headers and its source's HTTP setting. */
    fun use(headers: Map<String, String>, policy: HttpPolicy, stillAllowed: () -> Boolean = { true }) {
        item = Item(headers, policy, stillAllowed)
    }
}
