package com.muxlume.app.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.google.common.util.concurrent.ListenableFuture
import com.muxlume.app.core.net.HttpPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Notification and lock-screen artwork, loaded with the HTTP setting of the source the playing item belongs to
 * ([MediaIds.EXTRA_ARTWORK_SOURCE], set by the service). [allowsHttp] is asked when the image is loaded, so a change applies to the next
 * one, and it may take a moment (the settings are read from the database at a cold start): the image is requested once it has answered,
 * on the caller's thread nothing waits. A request without that mark, and one made for a bare URI, is HTTPS only.
 */
@UnstableApi
internal class ArtworkLoader(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val allowsHttp: suspend (sourceId: Long) -> Boolean,
    private val newLoader: (OkHttpDataSource.Factory) -> BitmapLoader,
) : BitmapLoader {
    private fun loader(policy: HttpPolicy) =
        newLoader(OkHttpDataSource.Factory(Call.Factory { req -> client.newCall(req.newBuilder().tag(HttpPolicy::class.java, policy).build()) }))

    private val https = loader(HttpPolicy.HTTPS_ONLY)
    private val http = loader(HttpPolicy.ALLOW_HTTP)

    override fun supportsMimeType(mimeType: String) = https.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = https.decodeBitmap(data)
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = https.loadBitmap(uri)
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        if (metadata.artworkData == null && metadata.artworkUri == null) return null // nothing to load: the contract's answer, not a failure
        val source = metadata.extras?.getLong(MediaIds.EXTRA_ARTWORK_SOURCE, NO_SOURCE) ?: NO_SOURCE
        if (source == NO_SOURCE) return https.loadBitmapFromMetadata(metadata)
        return scope.future { (if (allowsHttp(source)) http else https).loadBitmapFromMetadata(metadata)?.await() ?: throw IOException("No artwork") }
    }

    private companion object {
        const val NO_SOURCE = -1L
    }
}
