package com.muxlume.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.security.SecretBox
import com.muxlume.app.data.DataReset
import com.muxlume.app.data.ResetTargets
import com.muxlume.app.data.ResolverGrants
import com.muxlume.app.data.backup.BackupRepository
import com.muxlume.app.data.PlaylistRefresh
import com.muxlume.app.data.PlaylistRefresher
import com.muxlume.app.data.PlaylistWork
import com.muxlume.app.data.SettingsRepository
import com.muxlume.app.data.lock.ParentalLock
import com.muxlume.app.playback.SleepTimer
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.epg.EpgLinker
import com.muxlume.app.data.epg.EpgRepository
import com.muxlume.app.data.epg.EpgWork
import com.muxlume.app.reminders.Notifications
import com.muxlume.app.reminders.ReminderScheduler
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.settingsDataStore
import com.muxlume.app.playback.PlaybackService
import com.muxlume.app.playback.StreamResolver
import com.muxlume.app.recording.RecordingService
import com.muxlume.app.recording.Recordings
import com.muxlume.app.support.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File

/** Manual dependency graph: everything lives for the process lifetime and is created on first use. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http by lazy { Http.client() }
    val db by lazy { MuxlumeDatabase.build(app) }
    val settings by lazy { SettingsRepository(app.settingsDataStore) }
    val lock by lazy { ParentalLock(app.settingsDataStore, appScope) }
    private val linker by lazy { EpgLinker(db) }
    val sources by lazy { SourceRepository(db, http, SecretBox(), app.contentResolver, app.resources, linker) }
    /** Sources that allow plain HTTP (null until the database has answered): the artwork of those sources may use it, and a stream that is playing loses it when its source is no longer in here. */
    val httpSources: StateFlow<Set<Long>?> by lazy { db.sources().httpIds().map<List<Long>, Set<Long>?> { it.toSet() }.stateIn(appScope, SharingStarted.Eagerly, null) }
    /** Whether [sourceId] allows plain HTTP right now, from the database while [httpSources] has not been filled yet. For code off the main thread. */
    fun allowsHttp(sourceId: Long): Boolean = httpSources.value?.contains(sourceId) ?: runBlocking { db.sources().get(sourceId)?.allowHttp == true }
    val playlistRefresher by lazy { PlaylistRefresher(sources) }
    val backup by lazy { BackupRepository(db, sources, settings, epg) }
    val streams by lazy { StreamResolver(db, sources) }
    val recordings by lazy { Recordings(app, db, settings) }
    val dataReset by lazy {
        DataReset(
            db, settings, lock,
            ResetTargets(crashLog, ::clearImages, ResolverGrants(app.contentResolver), ::stopPlayback, { app.startService(RecordingService.stopIntent(app, -1)) }) {
                RecordingService.busy
            },
        )
    }
    val crashLog by lazy {
        CrashLog(File(app.filesDir, "crash.log")) {
            "MUXLUME ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}"
        }
    }
    val epg by lazy { EpgRepository(db, sources, linker, http, app.resources) }
    val sleepTimer by lazy { SleepTimer(appScope) { player().pause() } }
    val reminders by lazy { ReminderScheduler(app) }

    private val controllerLock = Mutex()
    private var controller: MediaController? = null

    /** Empties the artwork cache (disk and memory) through the image loader that has it open. */
    private fun clearImages() {
        SingletonImageLoader.get(app).run {
            diskCache?.clear()
            memoryCache?.clear()
        }
    }

    /**
     * Stops what plays and empties the queue. Only a controller this process already connected can be playing: nothing else starts
     * the service, so none connected means nothing to stop (and connecting one just to stop would start it).
     */
    suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        controllerLock.withLock { controller?.takeIf { it.isConnected } }?.run {
            stop()
            clearMediaItems()
        }
    }

    /** The app's MediaController (main thread only), connected on first use and reconnected if the service went away. */
    suspend fun player(): MediaController = withContext(Dispatchers.Main) {
        controllerLock.withLock {
            controller?.takeIf { it.isConnected }
                ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync().await()
                    .also { controller = it }
        }
    }
}

class MuxlumeApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    // WorkManager is created on first use with this configuration (also where no startup provider runs, e.g. tests).
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.crashLog.install()
        Notifications.createChannels(this)
        container.appScope.launch { container.recordings.cleanUpAfterCrash() } // rows a dead process left "recording"
        // Background guide refresh runs only while some source has its guide on.
        container.appScope.launch {
            combine(container.db.sources().all().map { list -> list.any { it.guideEnabled } }, container.settings.settings.map { it.guideWifiOnly }, ::Pair)
                .distinctUntilChanged()
                .collect { (anyGuide, wifiOnly) -> if (anyGuide) EpgWork.schedule(this@MuxlumeApp, wifiOnly) else EpgWork.cancel(this@MuxlumeApp) }
        }
        // Background playlist refresh runs only when the user chose an interval; it shares the guide's Wi-Fi setting.
        container.appScope.launch {
            container.settings.settings.map { it.playlistRefresh to it.guideWifiOnly }.distinctUntilChanged()
                .collect { (every, wifiOnly) -> if (every != PlaylistRefresh.OFF) PlaylistWork.schedule(this@MuxlumeApp, wifiOnly) else PlaylistWork.cancel(this@MuxlumeApp) }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}

val Context.container: AppContainer get() = (applicationContext as MuxlumeApp).container
