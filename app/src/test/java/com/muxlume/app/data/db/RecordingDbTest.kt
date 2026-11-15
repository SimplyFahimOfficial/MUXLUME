package com.muxlume.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecordingDbTest {
    private lateinit var db: MuxlumeDatabase
    private var source = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        source = db.sources().insert(SourceEntity(name = "S", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0))
        db.items().insertAll(
            listOf(
                ItemEntity(sourceId = source, kind = ItemKind.LIVE, stableKey = "kids", name = "Kids", groupTitle = "Kids", logo = null, position = 0),
                ItemEntity(sourceId = source, kind = ItemKind.LIVE, stableKey = "adult", name = "Adult", groupTitle = "Adult", logo = null, position = 1),
            ),
        )
        db.lockedGroups().add(LockedGroupEntity(source, "Adult"))
    }

    @After fun tearDown() = db.close()

    private fun row(key: String, state: RecordingState = RecordingState.DONE, bytes: Long = 100, file: String? = "content://tree/$key") =
        RecordingEntity(sourceId = source, stableKey = key, channelName = key, title = "T $key", startAt = 100, endAt = 200, state = state, fileUri = file, bytes = bytes)

    @Test
    fun `the same programme is saved once`(): Unit = runBlocking {
        assertThat(db.recordings().insert(row("kids"))).isGreaterThan(0)
        assertThat(db.recordings().insert(row("kids"))).isEqualTo(-1)
    }

    @Test
    fun `the list and playback hide recordings of a locked channel while locked`(): Unit = runBlocking {
        val kids = db.recordings().insert(row("kids"))
        val adult = db.recordings().insert(row("adult"))
        assertThat(db.recordings().all(false).first().map { it.id }).containsExactly(kids)
        assertThat(db.recordings().all(true).first().map { it.id }).containsExactly(adult, kids)
        assertThat(db.recordings().playable(adult, false)).isNull()
        assertThat(db.recordings().playable(adult, true)).isNotNull()
    }

    @Test
    fun `only a recording with a file and some bytes can be played`(): Unit = runBlocking {
        val noFile = db.recordings().insert(row("a", file = null).copy(stableKey = "kids", startAt = 1))
        val empty = db.recordings().insert(row("b", bytes = 0).copy(stableKey = "kids", startAt = 2))
        val good = db.recordings().insert(row("c").copy(stableKey = "kids", startAt = 3))
        assertThat(db.recordings().playable(noFile, true)).isNull()
        assertThat(db.recordings().playable(empty, true)).isNull()
        assertThat(db.recordings().playable(good, true)).isNotNull()
    }

    @Test
    fun `progress is stored and finishing records how it went`(): Unit = runBlocking {
        val id = db.recordings().insert(row("kids", RecordingState.SCHEDULED, 0, null))
        db.recordings().start(id, "content://tree/f")
        db.recordings().setBytes(id, 500)
        assertThat(db.recordings().get(id)!!.let { it.state to it.bytes }).isEqualTo(RecordingState.RECORDING to 500L)
        db.recordings().finish(id, RecordingState.FAILED, 800, "NETWORK")
        val done = db.recordings().get(id)!!
        assertThat(done.state).isEqualTo(RecordingState.FAILED)
        assertThat(done.bytes).isEqualTo(800)
        assertThat(done.error).isEqualTo("NETWORK")
        assertThat(db.recordings().totalBytes().first()).isEqualTo(800)
    }

    @Test
    fun `recordings go with their playlist`(): Unit = runBlocking {
        db.recordings().insert(row("kids"))
        db.sources().delete(source)
        assertThat(db.recordings().all(true).first()).isEmpty()
    }

    @Test
    fun `the next padded start on the same channel limits the end of a recording`(): Unit = runBlocking {
        fun scheduled(key: String, start: Long, lead: Long = 0, state: RecordingState = RecordingState.SCHEDULED) =
            row(key, state, 0, null).copy(startAt = start, endAt = start + 1000, leadSeconds = lead)
        db.recordings().insert(scheduled("kids", 5_000, lead = 60)) // padded start 4940
        db.recordings().insert(scheduled("kids", 9_000))
        db.recordings().insert(scheduled("adult", 3_000)) // another channel
        db.recordings().insert(scheduled("kids", 2_000, state = RecordingState.DONE)) // not scheduled
        assertThat(db.recordings().nextStartOnChannel(source, "kids", 1_000)).isEqualTo(4_940)
        assertThat(db.recordings().nextStartOnChannel(source, "kids", 4_940)).isEqualTo(9_000) // strictly after
        assertThat(db.recordings().nextStartOnChannel(source, "kids", 9_000)).isNull()
    }

    @Test
    fun `a channel shows as being recorded only while its recording runs`(): Unit = runBlocking {
        val id = db.recordings().insert(row("kids", RecordingState.SCHEDULED, 0, null))
        assertThat(db.recordings().runningFor(source, "kids").first()).isNull()
        db.recordings().start(id, "content://tree/f")
        assertThat(db.recordings().runningFor(source, "kids").first()).isEqualTo(id)
        assertThat(db.recordings().runningFor(source, "adult").first()).isNull() // another channel
        db.recordings().finish(id, RecordingState.DONE, 10, null)
        assertThat(db.recordings().runningFor(source, "kids").first()).isNull()
    }
}
