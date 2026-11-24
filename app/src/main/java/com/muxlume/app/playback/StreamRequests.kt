package com.muxlume.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.muxlume.app.core.net.HttpPolicy
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * How the player's requests are made: through the app's gated client, with the headers and the HTTP setting of the item they are for.
 * Each item gets its own factory when its media source is made ([ItemMediaSourceFactory]), and every request of that item (stream,
 * playlist, segment, key) goes through it, so a request made late, after the player moved on to another item or another playlist,
 * still carries its own item's values and never the new one's.
 */
@UnstableApi
internal class StreamRequests(private val client: OkHttpClient) {
    /**
     * The request builder of one item. Allowing HTTP is withdrawn from it as soon as [stillAllowed] says the source no longer allows it.
     */
    fun factoryFor(headers: Map<String, String>, policy: HttpPolicy, stillAllowed: () -> Boolean = { true }): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(
            Call.Factory { req ->
                val now = if (policy.allowHttp && !stillAllowed()) policy.copy(allowHttp = false) else policy
                client.newCall(headers.entries.fold(req.newBuilder()) { b, (name, value) -> b.header(name, value) }.tag(HttpPolicy::class.java, now).build())
            },
        )
}

/**
 * The player's media source factory with a data source factory of its own for each item ([dataSourceFor], looked up by the item when
 * its source is made), instead of one shared factory whose headers and setting change with the item that plays.
 */
@UnstableApi
internal class ItemMediaSourceFactory(private val context: Context, private val dataSourceFor: (MediaItem) -> DataSource.Factory) : MediaSource.Factory {
    // The player sets neither (nothing in this app does); the per-item sources below use their defaults.
    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) = this

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) = this

    override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(context).supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        DefaultMediaSourceFactory(DefaultDataSource.Factory(context, dataSourceFor(mediaItem))).createMediaSource(mediaItem)
}
