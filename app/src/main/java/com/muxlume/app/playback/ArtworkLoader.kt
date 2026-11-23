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
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Notification and lock-screen artwork, loaded with the HTTP setting of the source the playing item belongs to
 * ([MediaIds.EXTRA_ARTWORK_HTTP], set by the service). A request without that mark, and one made for a bare URI, is HTTPS only.
 */
@UnstableApi
internal class ArtworkLoader(private val client: OkHttpClient, private val newLoader: (OkHttpDataSource.Factory) -> BitmapLoader) : BitmapLoader {
    private fun loader(policy: HttpPolicy) =
        newLoader(OkHttpDataSource.Factory(Call.Factory { req -> client.newCall(req.newBuilder().tag(HttpPolicy::class.java, policy).build()) }))

    private val https = loader(HttpPolicy.HTTPS_ONLY)
    private val http = loader(HttpPolicy.ALLOW_HTTP)

    override fun supportsMimeType(mimeType: String) = https.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = https.decodeBitmap(data)
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = https.loadBitmap(uri)
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
        (if (metadata.extras?.getBoolean(MediaIds.EXTRA_ARTWORK_HTTP) == true) http else https).loadBitmapFromMetadata(metadata)
}
