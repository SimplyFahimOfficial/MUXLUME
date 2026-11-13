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
        assertThat(alarm.triggerAtMs).isEqualTo((now + 600) * 1000)
        assertThat(shadowOf(alarm.operation).isForegroundService).isTrue()
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
}
