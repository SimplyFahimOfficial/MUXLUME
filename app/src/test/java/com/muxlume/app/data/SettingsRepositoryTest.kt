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
