package com.muxlume.app.recording

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.SettingsRepository
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.ProgramEntity
import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.recording.RecordingPlan.Start
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneOffset

class RecordingPlanTest {
    @Test
    fun `a programme that has started records now, a future one by alarm or, without exact alarms, from a notification`() {
        assertThat(RecordingPlan.start(startAt = 1000, now = 1000, canScheduleExact = true)).isEqualTo(Start.NOW)
        assertThat(RecordingPlan.start(startAt = 1000, now = 5000, canScheduleExact = false)).isEqualTo(Start.NOW)
        assertThat(RecordingPlan.start(startAt = 1000, now = 999, canScheduleExact = true)).isEqualTo(Start.EXACT_ALARM)
        assertThat(RecordingPlan.start(startAt = 1000, now = 999, canScheduleExact = false)).isEqualTo(Start.NOTIFICATION)
    }

    @Test
    fun `file names are safe on any provider and say when`() {
        val at = 1_794_600_000L // 2026-11-13 19:20 UTC
        assertThat(RecordingFiles.fileName("BBC One", "News at Ten", at, ZoneOffset.UTC)).isEqualTo("BBC One - News at Ten - 2026-11-13 2000")
        assertThat(RecordingFiles.fileName("A/B", "What? \"Now\": <live>|", at, ZoneOffset.UTC)).doesNotContainMatch("[/:*?\"<>|\\\\]")
        assertThat(RecordingFiles.fileName("", "", at, ZoneOffset.UTC)).startsWith("Recording - ")
        assertThat(RecordingFiles.fileName("x".repeat(500), "y", at, ZoneOffset.UTC).length).isAtMost(140)
        assertThat(RecordingFiles.fileName("..", "..", at, ZoneOffset.UTC)).doesNotContain("..")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecordingsAddTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: MuxlumeDatabase
    private lateinit var settings: SettingsRepository
    private var source = 0L
    private val now = 10_000L

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("s.preferences_pb") })
        source = db.sources().insert(SourceEntity(name = "S", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0))
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true) // the flag is static; every test starts from "allowed"
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun recordings() = Recordings(context, db, settings) { now * 1000 }
    private fun program(start: Long, stop: Long) = ProgramEntity(sourceId = source, channelId = "c", start = start, stop = stop, title = "Show")
    private suspend fun chooseFolder() = settings.setRecordingFolder("content://tree/rec")
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun `without a folder nothing is saved`(): Unit = runBlocking {
        assertThat(recordings().add(source, "k", "Chan", program(now + 100, now + 200))).isEqualTo(Recordings.Result.NoFolder)
        assertThat(db.recordings().find(source, "k", now + 100)).isNull()
    }

    @Test
    fun `a programme that is over is refused`(): Unit = runBlocking {
        chooseFolder()
        assertThat(recordings().add(source, "k", "Chan", program(now - 200, now))).isEqualTo(Recordings.Result.Ended)
    }

    @Test
    fun `a programme that has started records right away through the service`(): Unit = runBlocking {
        chooseFolder()
        assertThat(recordings().add(source, "k", "Chan", program(now - 100, now + 3600))).isEqualTo(Recordings.Result.Started)
        val saved = db.recordings().find(source, "k", now - 100)!!
        assertThat(saved.state).isEqualTo(RecordingState.SCHEDULED)
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertThat(started.component!!.className).isEqualTo(RecordingService::class.java.name)
        assertThat(started.getLongExtra(RecordingService.EXTRA_ID, -1)).isEqualTo(saved.id)
    }

    @Test
    fun `a future programme gets an exact alarm that starts the service itself`(): Unit = runBlocking {
        chooseFolder()
        val r = recordings().add(source, "k", "Chan", program(now + 600, now + 4200))
        assertThat((r as Recordings.Result.Scheduled).unattended).isTrue()
        val alarm = alarms.peekNextScheduledAlarm()!!
        assertThat(alarm.triggerAtMs).isEqualTo((now + 600 - 60) * 1000) // 1 minute early by default
        assertThat(shadowOf(alarm.operation).isForegroundService).isTrue()
    }

    @Test
    fun `padding is copied from the settings, and the alarm and the immediate start use the padded start`(): Unit = runBlocking {
        chooseFolder()
        settings.setRecordLeadMinutes(2)
        settings.setRecordTrailMinutes(10)
        recordings().add(source, "k", "Chan", program(now + 600, now + 4200))
        val saved = db.recordings().find(source, "k", now + 600)!!
        assertThat(saved.leadSeconds).isEqualTo(120)
        assertThat(saved.trailSeconds).isEqualTo(600)
        assertThat(saved.startAt).isEqualTo(now + 600) // the programme's own times stay as they are
        assertThat(alarms.peekNextScheduledAlarm()!!.triggerAtMs).isEqualTo((now + 600 - 120) * 1000)

        // 60 s before the programme with a 2 minute lead: already inside the recording window, so it starts now.
        assertThat(recordings().add(source, "j", "Chan", program(now + 60, now + 4200))).isEqualTo(Recordings.Result.Started)
    }

    @Test
    fun `a recording is given up only after its padded end`(): Unit = runBlocking {
        fun row(key: String, end: Long, trail: Long) =
            RecordingEntity(sourceId = source, stableKey = key, channelName = "C", title = key, startAt = now - 3000, endAt = end, state = RecordingState.SCHEDULED, trailSeconds = trail)
        val padded = db.recordings().insert(row("padded", now - 100, 300)) // ended 100 s ago, but 300 s of trail left
        val over = db.recordings().insert(row("over", now - 400, 300))
        recordings().rearm()
        assertThat(db.recordings().get(padded)!!.state).isEqualTo(RecordingState.SCHEDULED)
        assertThat(db.recordings().get(over)!!.error).isEqualTo("MISSED")
    }

    @Test
    fun `recording from the player with a fixed length starts now, takes no padding and is titled with the channel and time`(): Unit = runBlocking {
        chooseFolder()
        assertThat(recordings().addNow(source, "k", "Chan", 1800)).isEqualTo(Recordings.Result.Started)
        val saved = db.recordings().find(source, "k", now)!!
        assertThat(saved.endAt).isEqualTo(now + 1800)
        assertThat(saved.leadSeconds).isEqualTo(0)
        assertThat(saved.trailSeconds).isEqualTo(0)
        assertThat(saved.title).startsWith("Chan ")
        assertThat(shadowOf(context as android.app.Application).nextStartedService.getLongExtra(RecordingService.EXTRA_ID, -1)).isEqualTo(saved.id)
    }

    @Test
    fun `recording from the player without a folder saves nothing, and the guide programme path can skip padding`(): Unit = runBlocking {
        assertThat(recordings().addNow(source, "k", "Chan", 1800)).isEqualTo(Recordings.Result.NoFolder)
        assertThat(db.recordings().find(source, "k", now)).isNull()

        chooseFolder()
        assertThat(recordings().add(source, "k", "Chan", program(now - 100, now + 3600), pad = false)).isEqualTo(Recordings.Result.Started)
        assertThat(db.recordings().find(source, "k", now - 100)!!.trailSeconds).isEqualTo(0)
    }

    @Test
    fun `without exact alarms the alarm only posts a notification to tap, and the user is told`(): Unit = runBlocking {
        chooseFolder()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val r = recordings().add(source, "k", "Chan", program(now + 600, now + 4200))
        assertThat((r as Recordings.Result.Scheduled).unattended).isFalse()
        assertThat(shadowOf(alarms.peekNextScheduledAlarm()!!.operation).isBroadcast).isTrue()
    }

    @Test
    fun `the same programme is not recorded twice`(): Unit = runBlocking {
        chooseFolder()
        recordings().add(source, "k", "Chan", program(now + 600, now + 4200))
        assertThat(recordings().add(source, "k", "Chan", program(now + 600, now + 4200))).isEqualTo(Recordings.Result.Already)
        assertThat(db.recordings().scheduledAfter(0)).hasSize(1)
    }

    @Test
    fun `after a restart future alarms are armed again and finished-with times are given up`(): Unit = runBlocking {
        fun row(key: String, start: Long, end: Long, state: RecordingState = RecordingState.SCHEDULED) =
            RecordingEntity(sourceId = source, stableKey = key, channelName = "C", title = key, startAt = start, endAt = end, state = state)
        val future = db.recordings().insert(row("future", now + 600, now + 900))
        val missed = db.recordings().insert(row("missed", now - 900, now - 600))
        val stale = db.recordings().insert(row("stale", now - 300, now + 300, RecordingState.RECORDING))

        recordings().cleanUpAfterCrash()
        recordings().rearm()

        assertThat(db.recordings().get(missed)!!.error).isEqualTo("MISSED")
        assertThat(db.recordings().get(stale)!!.error).isEqualTo("INTERRUPTED")
        assertThat(db.recordings().get(future)!!.state).isEqualTo(RecordingState.SCHEDULED)
        assertThat(alarms.scheduledAlarms).hasSize(1)
    }

    private suspend fun limitTo(connections: Int?) = db.sources().update(db.sources().get(source)!!.copy(maxConnections = connections))

    private suspend fun waiting(key: String, start: Long, end: Long) =
        db.recordings().insert(RecordingEntity(sourceId = source, stableKey = key, channelName = "C", title = key, startAt = start, endAt = end, state = RecordingState.SCHEDULED))

    @Test
    fun `declining the connection warning leaves no recording and no alarm`(): Unit = runBlocking {
        chooseFolder()
        limitTo(1)
        waiting("other", now + 500, now + 4000)
        var asked: RecordingWarning? = null
        val r = recordings().add(source, "k", "Chan", program(now + 600, now + 4200)) { asked = it; false }
        assertThat(r).isEqualTo(Recordings.Result.Cancelled)
        assertThat(asked!!.overLimit).isEqualTo(1)
        assertThat(db.recordings().find(source, "k", now + 600)).isNull()
        assertThat(alarms.scheduledAlarms).isEmpty()
        assertThat(shadowOf(context as android.app.Application).nextStartedService).isNull()
    }

    @Test
    fun `continuing past the warning schedules as usual, and no question is asked without a clash`(): Unit = runBlocking {
        chooseFolder()
        limitTo(1)
        waiting("other", now + 500, now + 4000)
        assertThat(recordings().add(source, "k", "Chan", program(now + 600, now + 4200)) { true }).isInstanceOf(Recordings.Result.Scheduled::class.java)
        assertThat(alarms.scheduledAlarms).hasSize(1)

        // After the last one ends there is room again: nothing to ask.
        val r = recordings().add(source, "later", "Chan", program(now + 9000, now + 9600)) { error("no warning expected") }
        assertThat(r).isInstanceOf(Recordings.Result.Scheduled::class.java)
    }

    @Test
    fun `an unknown limit never asks about connections`(): Unit = runBlocking {
        chooseFolder()
        waiting("other", now + 500, now + 4000)
        assertThat(recordings().add(source, "k", "Chan", program(now + 600, now + 4200)) { error("no warning expected") }).isInstanceOf(Recordings.Result.Scheduled::class.java)
    }

    @Test
    fun `a programme already in your recordings is reported before any warning`(): Unit = runBlocking {
        chooseFolder()
        limitTo(1)
        recordings().add(source, "k", "Chan", program(now + 600, now + 4200))
        assertThat(recordings().add(source, "k", "Chan", program(now + 600, now + 4200)) { error("no warning expected") }).isEqualTo(Recordings.Result.Already)
    }

    @Test
    fun `an answer that takes a while is acted on at the time it is given`(): Unit = runBlocking {
        chooseFolder()
        limitTo(1)
        waiting("other", now + 500, now + 4000)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        var clockMs = now * 1000
        val slow = Recordings(context, db, settings) { clockMs }

        // The padded start (now + 540) passes while the dialog is open: record at once, not by a notification the user must tap.
        assertThat(slow.add(source, "k", "Chan", program(now + 600, now + 4200)) { clockMs = (now + 700) * 1000; true }).isEqualTo(Recordings.Result.Started)

        // The programme ends while the dialog is open: nothing is saved.
        clockMs = now * 1000
        assertThat(slow.add(source, "j", "Chan", program(now + 600, now + 900)) { clockMs = (now + 1000) * 1000; true }).isEqualTo(Recordings.Result.Ended)
        assertThat(db.recordings().find(source, "j", now + 600)).isNull()
    }
}
