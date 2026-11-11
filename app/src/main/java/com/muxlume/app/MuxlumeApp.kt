package com.muxlume.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.muxlume.app.core.net.CleartextGate
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.security.SecretBox
import com.muxlume.app.data.SettingsRepository
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.settingsDataStore
import com.muxlume.app.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/** Manual dependency graph: everything lives for the process lifetime and is created on first use. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http by lazy { Http.client() }
    val db by lazy { MuxlumeDatabase.build(app) }
    val settings by lazy { SettingsRepository(app.settingsDataStore) }
    val sources by lazy { SourceRepository(db, http, SecretBox(), app.contentResolver) }

    private val controllerLock = Mutex()
    private var controller: MediaController? = null

    /** The app's MediaController (main thread only), connected on first use and reconnected if the service went away. */
    suspend fun player(): MediaController = withContext(Dispatchers.Main) {
        controllerLock.withLock {
            controller?.takeIf { it.isConnected }
                ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync().await()
                    .also { controller = it }
        }
    }
}

class MuxlumeApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Artwork isn't tied to a source: allow it over HTTP only while some source allows HTTP.
        container.appScope.launch { container.db.sources().anyAllowsHttp().collect { CleartextGate.artworkAllowed = it } }
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}

val Context.container: AppContainer get() = (applicationContext as MuxlumeApp).container
