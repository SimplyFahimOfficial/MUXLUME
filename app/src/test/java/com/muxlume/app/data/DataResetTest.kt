package com.muxlume.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.AppContainer
import com.muxlume.app.PlainBox
import com.muxlume.app.core.net.Http
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.LockedGroupEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.epg.EpgLinker
import com.muxlume.app.data.lock.ParentalLock
import com.muxlume.app.support.CrashLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Settings → Delete all data with local fixtures: a real database, a real settings file (closed and reopened to see what a relaunch
 * sees), the real PIN, and fakes for the two things only a device has: the access to picked files, and the playback and recording services.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class DataResetTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val closers = mutableListOf<() -> Unit>()
    private val server = MockWebServer()

    init {
        context.deleteDatabase(DB)
    }

    @After
    fun tearDown() {
        closers.forEach { it() }
        context.deleteDatabase(DB)
        server.close()
    }

    /** One run of the app: its database and settings file, closed by [close] like a process that ended. */
    private inner class App {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db: MuxlumeDatabase = Room.databaseBuilder(context, MuxlumeDatabase::class.java, DB).allowMainThreadQueries().build()
        private val store = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") }
        val settings = SettingsRepository(store)
        val lock = ParentalLock(store, scope, iterations = 300)
        val crashFile = folder.root.resolve("crash.log")
        val imageCache = folder.root.resolve("images")
        val grants = FakeGrants()
        var playbackStops = 0
        var recordingStops = 0
        val order = mutableListOf<String>()

        /** A recording job the service has but whose row is not RECORDING yet (it is resolving the stream and creating the file). */
        var preparing = false
        var finishes = true

        private fun rowRunning() = db.openHelper.readableDatabase.query("SELECT 1 FROM recordings WHERE state = 'RECORDING'").use { it.moveToFirst() }

        fun reset(recordingFinishes: Boolean = true, waitMs: Long = 2_000): DataReset {
            finishes = recordingFinishes
            return DataReset(
                db, settings, lock,
                ResetTargets(
                    CrashLog(crashFile) { "header" }, { imageCache.deleteRecursively() }, grants,
                    { order += "playback"; playbackStops++ },
                    {
                        order += "recordings"
                        recordingStops++
                        if (finishes) {
                            preparing = false
                            db.openHelper.writableDatabase.execSQL("UPDATE recordings SET state = 'DONE' WHERE state = 'RECORDING'")
                        }
                    },
                    { preparing || rowRunning() },
                ),
                recordingWaitMs = waitMs,
            )
        }

        fun close() {
            scope.cancel()
            db.close()
        }
    }

    private class FakeGrants(var failures: Int = 0) : UriGrants {
        /** What the app holds: the recordings folder (read and write) and a playlist file (read). */
        val held = mutableListOf("content://tree/recordings" to true, "content://doc/list.m3u" to false)
        override fun releaseAll() {
            if (failures > 0) {
                failures--
                throw IOException("interrupted")
            }
            held.clear()
        }
    }

    private fun app(): App = App().also { closers += it::close }

    private suspend fun App.fill(): Long {
        val source = db.sources().insert(SourceEntity(name = "S", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0))
        db.items().insertAll(listOf(ItemEntity(sourceId = source, kind = ItemKind.LIVE, stableKey = "k", name = "K", groupTitle = "Adult", logo = null, url = "https://x/k", position = 0)))
        db.favorites().add(FavoriteEntity(source, "k", 1))
        db.history().upsert(HistoryEntity("$source|k", source, ItemKind.LIVE, "K", null, null, 0, 0, 5))
        db.lockedGroups().add(LockedGroupEntity(source, "Adult"))
        settings.setOnboardingDone()
        settings.setTheme(ThemeMode.LIGHT)
        settings.setAccent(Accent.TEAL)
        settings.setAmoled(true)
        settings.setBuffer(BufferMode.entries.last())
        settings.setAudioLanguage("fr")
        settings.setPlaylistRefresh(PlaylistRefresh.DAILY)
        settings.setFailover(false)
        settings.setGuideWifiOnly(false)
        settings.setRecordLeadMinutes(AppSettings.LEAD_CHOICES.max())
        settings.setRecordingFolder("content://tree/recordings")
        lock.setPin("4821")
        crashFile.writeText("a crash")
        imageCache.resolve("a.img").apply { parentFile!!.mkdirs() }.writeText("pixels")
        return source
    }

    /** What the app would show on its next launch: the database and settings file opened again by a new process. */
    private suspend fun relaunch(before: App): Triple<AppSettings, Boolean, Boolean> {
        before.close()
        val again = app()
        val hasPin = again.lock.run { load(); hasPin.value == true }
        return Triple(again.settings.current(), hasPin, again.db.sources().allNow().isEmpty())
    }

    @Test
    fun `reset returns the app to first-run defaults, and a relaunch finds nothing`(): Unit = runBlocking {
        val first = app()
        first.fill()
        assertThat(first.settings.current()).isNotEqualTo(AppSettings())

        first.reset().run()

        assertThat(first.db.sources().allNow()).isEmpty()
        assertThat(first.db.items().favorites(ItemKind.LIVE, null, true).first()).isEmpty()
        assertThat(first.db.history().all()).isEmpty()
        assertThat(first.db.lockedGroups().all()).isEmpty()
        assertThat(first.lock.hasPin.value).isFalse()
        assertThat(first.lock.unlocked.value).isTrue() // no PIN: nothing is enforced
        assertThat(first.crashFile.exists()).isFalse()
        assertThat(first.imageCache.exists()).isFalse()
        assertThat(first.grants.held).isEmpty()
        assertThat(first.playbackStops).isEqualTo(1)

        val (settings, hasPin, noSources) = relaunch(first)
        assertThat(settings).isEqualTo(AppSettings()) // first run: onboarding not done, default theme, no folder, failover on ...
        assertThat(settings.onboardingDone).isFalse()
        assertThat(hasPin).isFalse()
        assertThat(noSources).isTrue()
    }

    @Test
    fun `the confirmation says that recording files and saved backups stay`() {
        val text = context.getString(com.muxlume.app.R.string.settings_everything_muxlume_stored_on_this)
        assertThat(text).contains("Recording files")
        assertThat(text).contains("backup files")
        assertThat(text).contains("stay")
        assertThat(java.io.File("../PRIVACY.md").readText()).contains("does **not** delete the recording files")
    }

    @Test
    fun `the recording folder grant is released for both modes it holds`() {
        assertThat(ResolverGrants.releaseFlags(read = true, write = true)).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThat(ResolverGrants.releaseFlags(read = true, write = false)).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThat(ResolverGrants.releaseFlags(read = false, write = false)).isEqualTo(0)
    }

    @Test
    fun `a running recording is stopped and awaited, its file in the user's folder stays, and its row goes`(): Unit = runBlocking {
        val app = app()
        val source = app.fill()
        val userFile = folder.newFile("News - 2026-11-22 2000.ts").apply { writeText("recorded video") } // in the folder the user chose
        app.db.recordings().insert(
            RecordingEntity(sourceId = source, stableKey = "k", channelName = "K", title = "T", startAt = 1, endAt = 2, state = RecordingState.RECORDING, fileUri = userFile.toURI().toString()),
        )

        app.reset().run()

        assertThat(app.recordingStops).isEqualTo(1)
        assertThat(userFile.readText()).isEqualTo("recorded video")
        assertThat(app.db.recordings().anyRunning()).isFalse()
        assertThat(app.db.recordings().find(source, "k", 1)).isNull()
    }

    @Test
    fun `a recording that is still being prepared is stopped too, and playback is stopped after it`(): Unit = runBlocking {
        val app = app()
        val source = app.fill()
        app.db.recordings().insert(RecordingEntity(sourceId = source, stableKey = "k", channelName = "K", title = "T", startAt = 1, endAt = 2, state = RecordingState.SCHEDULED))
        app.preparing = true // the service has the job; the row is not RECORDING yet

        app.reset().run()

        assertThat(app.recordingStops).isEqualTo(1)
        assertThat(app.order).containsExactly("recordings", "playback").inOrder()
        assertThat(app.db.sources().allNow()).isEmpty()
    }

    @Test
    fun `a recording that does not stop in time fails the reset and changes nothing, and a retry after it stopped succeeds`(): Unit = runBlocking {
        val app = app()
        val source = app.fill()
        app.db.recordings().insert(RecordingEntity(sourceId = source, stableKey = "k", channelName = "K", title = "T", startAt = 1, endAt = 2, state = RecordingState.RECORDING))

        assertThrows(IOException::class.java) { runBlocking { app.reset(recordingFinishes = false, waitMs = 300).run() } }
        assertThat(app.db.sources().allNow()).hasSize(1)
        assertThat(app.settings.current().onboardingDone).isTrue()
        assertThat(app.lock.hasPin.value).isTrue()
        assertThat(app.grants.held).isNotEmpty()
        assertThat(app.playbackStops).isEqualTo(0)

        app.reset().run()
        assertThat(app.db.sources().allNow()).isEmpty()
        assertThat(app.settings.current()).isEqualTo(AppSettings())
    }

    @Test
    fun `a failure before the end leaves the PIN, which goes with the settings file`(): Unit = runBlocking {
        val app = app()
        app.fill()
        app.grants.failures = 1
        assertThrows(IOException::class.java) { runBlocking { app.reset().run() } }
        assertThat(app.lock.hasPin.value).isTrue()
        app.reset().run()
        assertThat(app.lock.hasPin.value).isFalse()
        assertThat(app.lock.unlocked.value).isTrue()
    }

    @Test
    fun `a second reset while one runs waits, and the running flag shows it`(): Unit = runBlocking {
        val app = app()
        app.fill()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reset = DataReset(
            app.db, app.settings, app.lock,
            ResetTargets(CrashLog(app.crashFile) { "h" }, { }, app.grants, { entered.complete(Unit); release.await() }, { }, { false }),
        )
        val first = async(Dispatchers.IO) { reset.run() }
        entered.await()
        assertThat(reset.running.value).isTrue()
        val second = async(Dispatchers.IO) { reset.run() } // would clear twice at once without the lock
        release.complete(Unit)
        first.await()
        second.await()
        assertThat(reset.running.value).isFalse()
        assertThat(app.settings.current()).isEqualTo(AppSettings())
    }

    @Test
    fun `a grant the app no longer holds does not stop the reset`() {
        val released = mutableListOf<Pair<String, Int>>()
        val read = ResolverGrants.Held(Uri.parse("content://doc/list.m3u"), read = true, write = false)
        val folder = ResolverGrants.Held(Uri.parse("content://tree/recordings"), read = true, write = true)
        val stale = ResolverGrants.Held(Uri.parse("content://doc/gone"), read = true, write = false)
        val none = ResolverGrants.Held(Uri.parse("content://doc/none"), read = false, write = false)
        ResolverGrants({ listOf(stale, folder, none, read) }, { uri, flags ->
            if (uri == stale.uri) throw SecurityException("No persistable permission grants found")
            released += uri.toString() to flags
        }).releaseAll()
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertThat(released).containsExactly("content://tree/recordings" to both, "content://doc/list.m3u" to Intent.FLAG_GRANT_READ_URI_PERMISSION).inOrder()
    }

    @Test
    fun `stopping playback with no controller connected starts nothing`(): Unit = runBlocking {
        val container = AppContainer(context)
        // It switches to the main thread, which this test occupies: let the main looper run while it waits.
        val stopping = async(Dispatchers.Default) { container.stopPlayback() }
        kotlinx.coroutines.withTimeout(10_000) {
            while (!stopping.isCompleted) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(20)
            }
        }
        stopping.await()
        val shadow = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        assertThat(shadow.nextStartedService).isNull()
        assertThat(shadow.boundServiceConnections).isEmpty()
    }

    @Test
    fun `a reset that failed half way is finished by running it again, and a second reset on an empty app succeeds`(): Unit = runBlocking {
        val app = app()
        app.fill()
        app.grants.failures = 1

        assertThrows(IOException::class.java) { runBlocking { app.reset().run() } }
        // Interrupted after the database and the PIN: the settings file is untouched, so the app still looks set up and the button is there to retry.
        assertThat(app.db.sources().allNow()).isEmpty()
        assertThat(app.settings.current().onboardingDone).isTrue()
        assertThat(app.grants.held).isNotEmpty()

        app.reset().run()
        assertThat(app.grants.held).isEmpty()
        assertThat(app.settings.current()).isEqualTo(AppSettings())

        app.reset().run() // nothing left: still fine
        assertThat(app.settings.current()).isEqualTo(AppSettings())
        assertThat(relaunch(app).third).isTrue()
    }

    @Test
    fun `a history write that comes after the reset stores nothing`(): Unit = runBlocking {
        val app = app()
        val source = app.fill()
        app.reset().run()
        // PlaybackService.saveHistory runs in a coroutine of its own and ignores a failure: the playlist is gone, so the row is refused.
        runCatching { app.db.history().upsert(HistoryEntity("$source|k", source, ItemKind.LIVE, "K", null, null, 0, 0, 9)) }
        assertThat(app.db.history().all()).isEmpty()
    }

    @Test
    fun `an import that is running during the reset stores nothing and fails`(): Unit = runBlocking {
        val app = app()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.complete(Unit)
                runBlocking { release.await() }
                return MockResponse.Builder().code(200).body("#EXTM3U\n#EXTINF:-1,A\nhttps://cdn.example/a.m3u8\n").build()
            }
        }
        val sources = SourceRepository(app.db, Http.client(), PlainBox(), context.contentResolver, context.resources, EpgLinker(app.db))
        val id = sources.save(SourceInput.M3uUrl("T", server.url("/list.m3u").toString(), true))
        val importing = async(Dispatchers.IO) { runCatching { sources.refresh(id) } }
        withTimeoutMs { entered.await() }

        app.reset().run() // the playlist is gone while its download is still in flight
        release.complete(Unit)

        assertThat(importing.await().isFailure).isTrue()
        assertThat(app.db.sources().allNow()).isEmpty()
        assertThat(app.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items").use { it.moveToFirst(); it.getInt(0) }).isEqualTo(0)
    }

    @Test
    fun `clearing the watch history leaves everything else`(): Unit = runBlocking {
        val app = app()
        val source = app.fill()
        app.db.history().clear() // what the other button in Settings does
        assertThat(app.db.history().all()).isEmpty()
        assertThat(app.db.sources().allNow().map { it.id }).containsExactly(source)
        assertThat(app.settings.current().onboardingDone).isTrue()
        assertThat(app.lock.hasPin.value).isTrue()
    }

    private suspend fun <T> withTimeoutMs(block: suspend () -> T): T = kotlinx.coroutines.withTimeout(TimeUnit.SECONDS.toMillis(10)) { block() }

    private companion object {
        const val DB = "data-reset-test.db"
    }
}
