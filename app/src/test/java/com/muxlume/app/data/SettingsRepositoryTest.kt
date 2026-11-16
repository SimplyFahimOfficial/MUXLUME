package com.muxlume.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun repo(name: String) = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("$name.preferences_pb") },
    )

    @Test
    fun `default languages are stored, and Automatic removes them`(): Unit = runBlocking {
        val r = repo("languages")
        assertThat(r.current().audioLanguage).isNull()
        r.setAudioLanguage("hi")
        r.setSubtitleLanguage("off")
        assertThat(r.current().audioLanguage).isEqualTo("hi")
        assertThat(r.current().subtitleLanguage).isEqualTo("off")
        r.setAudioLanguage(null)
        assertThat(r.current().audioLanguage).isNull()
        assertThat(r.current().subtitleLanguage).isEqualTo("off")
    }

    @Test
    fun `playlist refresh is off by default and can be changed`(): Unit = runBlocking {
        val r = repo("refresh")
        assertThat(r.current().playlistRefresh).isEqualTo(PlaylistRefresh.OFF)
        r.setPlaylistRefresh(PlaylistRefresh.EVERY_3_DAYS)
        assertThat(r.current().playlistRefresh).isEqualTo(PlaylistRefresh.EVERY_3_DAYS)
    }

    @Test
    fun `recording padding defaults to 1 minute early and 5 late, and a value that is not offered reads as the default`(): Unit = runBlocking {
        val r = repo("padding")
        assertThat(r.current().recordLeadMinutes).isEqualTo(1)
        assertThat(r.current().recordTrailMinutes).isEqualTo(5)
        r.setRecordLeadMinutes(0)
        r.setRecordTrailMinutes(15)
        assertThat(r.current().recordLeadMinutes).isEqualTo(0)
        assertThat(r.current().recordTrailMinutes).isEqualTo(15)
        r.setRecordTrailMinutes(7)
        assertThat(r.current().recordTrailMinutes).isEqualTo(5)
    }

    @Test
    fun `trying other servers is on by default and can be turned off`(): Unit = runBlocking {
        val r = repo("failover")
        assertThat(r.current().failover).isTrue()
        r.setFailover(false)
        assertThat(r.current().failover).isFalse()
    }

    @Test
    fun `appearance choices round trip`(): Unit = runBlocking {
        val r = repo("ok")
        assertThat(r.current()).isEqualTo(AppSettings())
        r.setTheme(ThemeMode.LIGHT)
        r.setAccent(Accent.TEAL)
        r.setAmoled(true)
        r.setDynamicColor(true)
        r.setBuffer(BufferMode.LARGE)
        val s = r.current()
        assertThat(s.theme).isEqualTo(ThemeMode.LIGHT)
        assertThat(s.accent).isEqualTo(Accent.TEAL)
        assertThat(s.amoled).isTrue()
        assertThat(s.dynamicColor).isTrue()
        assertThat(s.buffer).isEqualTo(BufferMode.LARGE)
    }

    @Test
    fun `corrupt settings file falls back to defaults instead of crashing`(): Unit = runBlocking {
        folder.root.resolve("bad.preferences_pb").writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42))
        assertThat(repo("bad").current()).isEqualTo(AppSettings())
    }
}
