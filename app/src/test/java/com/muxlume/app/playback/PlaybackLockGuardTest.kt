package com.muxlume.app.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.LockedGroupEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.lock.ParentalLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What happens to what plays when the parental lock closes: real ExoPlayer and media session, real lock, real Room queries.
 * Playlist A has an "Adult" group that is locked; playlist B has one too, and "News" groups that are not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaybackLockGuardTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: MuxlumeDatabase
    private lateinit var lock: ParentalLock
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var a = 0L
    private var b = 0L
    private val saved = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        lock = ParentalLock(PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("lock.preferences_pb") }, ioScope, iterations = 300)
        a = db.sources().insert(SourceEntity(name = "A", type = SourceType.M3U_URL, secret = "x", label = "a", allowHttp = false, createdAt = 0))
        b = db.sources().insert(SourceEntity(name = "B", type = SourceType.M3U_URL, secret = "x", label = "b", allowHttp = false, createdAt = 1))
        db.items().insertAll(
            listOf(live(a, "a-adult", "Adult"), live(a, "a-news", "News"), live(b, "b-adult", "Adult"), live(b, "b-news", "News"))
                + ItemEntity(sourceId = a, kind = ItemKind.SERIES, stableKey = "show", name = "Show", groupTitle = "Adult", logo = null, position = 9),
        )
        db.lockedGroups().add(LockedGroupEntity(a, "Adult"))
        db.lockedGroups().add(LockedGroupEntity(b, "Adult"))
        player = ExoPlayer.Builder(context).build()
        session = MediaSession.Builder(context, player).build()
        lock.setPin("1234") // the person who sets the PIN is unlocked
    }

    @After
    fun tearDown() {
        session.release()
        player.release()
        db.close()
        ioScope.cancel()
    }

    private fun live(source: Long, key: String, group: String) =
        ItemEntity(sourceId = source, kind = ItemKind.LIVE, stableKey = key, name = key, groupTitle = group, logo = null, url = "https://x/$key", position = 0)

    private fun guide() = PlaybackLockGuard(db, lock::unlockedNow, { saved += it.currentMediaItem!!.mediaId })

    private fun media(id: String, primary: String? = null, parent: String? = null): MediaItem =
        MediaIds.request(id, id, null, null, if (parent != null) ItemKind.SERIES else ItemKind.LIVE, parentKey = parent, primary = primary)
            .buildUpon().setUri("https://example.invalid/stream").build()

    private fun id(source: Long, key: String) = MediaIds.of(source, key)

    private fun play(item: MediaItem) = player.setMediaItem(item)

    private val stopped get() = player.currentMediaItem == null && player.mediaItemCount == 0 && session.player.currentMediaItem == null

    private suspend fun recording(source: Long, key: String): Long = db.recordings().insert(
        RecordingEntity(sourceId = source, stableKey = key, channelName = key, title = "T", startAt = 1, endAt = 2, state = RecordingState.DONE, fileUri = "content://folder/$key.ts", bytes = 100),
    )

    @Test
    fun `an authorized recording that is playing stops when the app relocks, and leaves the media session empty`(): Unit = runBlocking {
        val rec = recording(a, "a-adult")
        assertThat(db.recordings().playable(rec, unlocked = true)).isNotNull() // opened while unlocked: allowed
        play(media(MediaIds.ofRecording(rec)))
        val watcher = guide().watch(this, lock.unlocked, player)
        assertThat(player.currentMediaItem).isNotNull()

        lock.lock() // timeout or "Lock now"
        withTimeout(10_000) { while (!stopped) delay(20) }

        assertThat(stopped).isTrue()
        watcher.cancel()
    }

    @Test
    fun `a recording of a permitted channel keeps playing through a relock`(): Unit = runBlocking {
        val rec = recording(a, "a-news")
        play(media(MediaIds.ofRecording(rec)))
        lock.lock()
        guide().stopIfRestricted(player)
        assertThat(player.currentMediaItem?.mediaId).isEqualTo(MediaIds.ofRecording(rec))
    }

    @Test
    fun `a locked channel playing live stops, a permitted one does not, and nothing is stopped while unlocked`(): Unit = runBlocking {
        play(media(id(a, "a-adult")))
        guide().stopIfRestricted(player)
        assertThat(player.currentMediaItem).isNotNull() // still unlocked

        lock.lock()
        play(media(id(a, "a-news")))
        guide().stopIfRestricted(player)
        assertThat(player.currentMediaItem).isNotNull()

        play(media(id(a, "a-adult")))
        guide().stopIfRestricted(player)
        assertThat(stopped).isTrue()
        assertThat(saved).containsExactly(id(a, "a-adult"))
    }

    @Test
    fun `an episode is judged by its series`(): Unit = runBlocking {
        lock.lock()
        play(media(id(a, "ep:12:mkv"), parent = "show")) // the episode itself is in no group; its series is in a locked one
        guide().stopIfRestricted(player)
        assertThat(stopped).isTrue()
        play(media(id(a, "ep:12:mkv"), parent = "other-show"))
        guide().stopIfRestricted(player)
        assertThat(player.currentMediaItem).isNotNull()
    }

    @Test
    fun `with failover the chosen channel and the server that plays both count`(): Unit = runBlocking {
        lock.lock()
        // chosen channel, server that plays it -> must the playback stop?
        val cases = listOf(
            Triple(id(a, "a-adult"), id(b, "b-news"), true), // the chosen channel is locked, the alternate is not
            Triple(id(a, "a-news"), id(b, "b-adult"), true), // the alternate is locked
            Triple(id(a, "a-adult"), id(b, "b-adult"), true), // both
            Triple(id(a, "a-news"), id(b, "b-news"), false), // both permitted
        )
        for ((chosen, server, stops) in cases) {
            play(media(server, primary = chosen))
            guide().stopIfRestricted(player)
            assertThat(stopped).isEqualTo(stops)
        }
    }

    @Test
    fun `a channel change while the check runs is not stopped by the old channel's verdict`(): Unit = runBlocking {
        lock.lock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val slow = PlaybackLockGuard(db, lock::unlockedNow, { saved += it.currentMediaItem!!.mediaId }) { source, key ->
            if (first) {
                first = false
                entered.complete(Unit)
                release.await() // the database answer is on its way
            }
            db.items().isLockedKey(source, key)
        }
        play(media(id(a, "a-adult")))
        val check = launch { slow.stopIfRestricted(player) }
        entered.await()
        play(media(id(b, "b-news"))) // the user switched to a permitted channel meanwhile
        release.complete(Unit)
        check.join()
        assertThat(player.currentMediaItem?.mediaId).isEqualTo(id(b, "b-news"))
        assertThat(saved).isEmpty()
    }

    @Test
    fun `a locked channel selected while the check runs is judged too`(): Unit = runBlocking {
        lock.lock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val slow = PlaybackLockGuard(db, lock::unlockedNow, { saved += it.currentMediaItem!!.mediaId }) { source, key ->
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
            db.items().isLockedKey(source, key)
        }
        play(media(id(a, "a-news")))
        val check = launch { slow.stopIfRestricted(player) }
        entered.await()
        play(media(id(b, "b-adult"))) // resolved while still unlocked, a moment before the lock closed
        release.complete(Unit)
        check.join()
        assertThat(stopped).isTrue()
    }

    @Test
    fun `unlocking again while a check runs keeps playing`(): Unit = runBlocking {
        lock.lock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = PlaybackLockGuard(db, lock::unlockedNow) { source, key ->
            entered.complete(Unit)
            release.await()
            db.items().isLockedKey(source, key)
        }
        play(media(id(a, "a-adult")))
        val watcher = slow.watch(this, lock.unlocked, player)
        entered.await()
        lock.verify("1234") // the PIN again: a newer value of the lock replaces the check
        release.complete(Unit)
        delay(300)
        assertThat(player.currentMediaItem).isNotNull()
        watcher.cancel()
    }

    private suspend fun untilStopped() = withTimeout(10_000) {
        while (!stopped) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() // player events are delivered on the main looper
            delay(20)
        }
    }

    @Test
    fun `protected content that is installed after the lock closed is stopped when it starts`(): Unit = runBlocking {
        lock.lock()
        play(media(id(a, "a-news")))
        val watcher = guide().watch(this, lock.unlocked, player)
        delay(200)
        assertThat(player.currentMediaItem).isNotNull()

        // A server change that was resolved while unlocked and installed after the check: no lock event follows it.
        play(media(id(b, "b-adult")))
        untilStopped()
        assertThat(stopped).isTrue()
        watcher.cancel()
    }

    @Test
    fun `playback of a playlist that was deleted stops, also what is installed after the deletion`(): Unit = runBlocking {
        play(media(id(a, "a-news")))
        val watcher = guide().watch(this, lock.unlocked, player)
        delay(200)
        assertThat(player.currentMediaItem).isNotNull()

        db.clearAllTables() // Delete all data
        untilStopped()
        assertThat(stopped).isTrue()

        play(media(id(a, "a-news"), primary = id(b, "b-news"))) // a failover that had resolved its stream before the reset
        untilStopped()
        assertThat(stopped).isTrue()
        watcher.cancel()
    }

    @Test
    fun `a recording whose row is gone stops, one that is there keeps playing`(): Unit = runBlocking {
        val rec = recording(a, "a-news")
        play(media(MediaIds.ofRecording(rec)))
        guide().stopIfRestricted(player)
        assertThat(player.currentMediaItem).isNotNull()
        db.recordings().delete(rec)
        guide().stopIfRestricted(player)
        assertThat(stopped).isTrue()
    }
}
