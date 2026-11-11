package com.muxlume.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.PlainBox
import com.muxlume.app.core.net.Http
import com.muxlume.app.data.Accent
import com.muxlume.app.data.SettingsRepository
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.ThemeMode
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.epg.EpgLinker
import com.muxlume.app.data.epg.EpgRepository
import com.muxlume.app.playback.MediaIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pass = "correct horse".toCharArray()

    /** One installation of the app: its own database and settings. */
    private inner class Device(name: String) {
        val db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("$name.preferences_pb") })
        val sources = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver, context.resources, EpgLinker(db))
        val backup = BackupRepository(db, sources, settings, EpgRepository(db, sources, EpgLinker(db), Http.client(), context.resources))

        fun export(): ByteArray = ByteArrayOutputStream().also { runBlocking { backup.export(it, pass) } }.toByteArray()
        fun import(file: ByteArray, passphrase: CharArray = pass) = runBlocking { backup.import(ByteArrayInputStream(file), passphrase) }
    }

    private val devices = mutableListOf<Device>()
    private fun device(name: String) = Device(name).also { devices += it }

    @After
    fun tearDown() {
        devices.forEach { it.db.close() }
        scope.cancel()
    }

    private suspend fun Device.seed(): List<Long> {
        val m3u = sources.save(SourceInput.M3uUrl("Home", "https://example.com/list.m3u", allowHttp = false, guideUrl = "https://example.com/guide.xml"))
        val xtream = sources.save(SourceInput.Xtream("Provider", "https://p.example:8443", "me", "secret", allowHttp = false))
        val file = sources.save(SourceInput.M3uFile("Local", Uri.parse("content://docs/local.m3u"), "local.m3u", allowHttp = false))
        db.guide().setGuideEnabled(m3u, true)
        db.favorites().add(FavoriteEntity(m3u, "chan-1", 5))
        db.favorites().add(FavoriteEntity(file, "chan-9", 6)) // belongs to a file playlist: not saved
        db.history().upsert(HistoryEntity(MediaIds.of(xtream, "movie-1"), xtream, ItemKind.MOVIE, "A Movie", "2020", null, 60_000, 600_000, 1_000))
        settings.setTheme(ThemeMode.LIGHT)
        settings.setAccent(Accent.TEAL)
        return listOf(m3u, xtream, file)
    }

    @Test
    fun `a backup restores playlists, favorites, history and settings on another device`() = runBlocking {
        val a = device("a")
        a.seed()
        val file = a.export()
        assertThat(String(file, Charsets.ISO_8859_1)).doesNotContain("example.com")

        val b = device("b")
        val r = b.import(file)

        assertThat(r.addedIds).hasSize(2)
        assertThat(r.favorites).isEqualTo(1)
        assertThat(r.history).isEqualTo(1)
        val restored = b.db.sources().allNow().associateBy { it.name }
        assertThat(restored.keys).containsExactly("Home", "Provider") // the file playlist stays behind
        assertThat(restored.getValue("Home").guideEnabled).isTrue()
        assertThat(b.sources.secretOf(restored.getValue("Home")).epgUrl).isEqualTo("https://example.com/guide.xml")
        assertThat(b.sources.secretOf(restored.getValue("Provider")).password).isEqualTo("secret")
        assertThat(restored.getValue("Provider").type).isEqualTo(SourceType.XTREAM)
        val home = restored.getValue("Home").id
        assertThat(b.db.favorites().get(home, "chan-1")).isNotNull()
        val provider = restored.getValue("Provider").id
        assertThat(b.db.history().get(MediaIds.of(provider, "movie-1"))!!.positionMs).isEqualTo(60_000)
        assertThat(b.settings.current().theme).isEqualTo(ThemeMode.LIGHT)
        assertThat(b.settings.current().accent).isEqualTo(Accent.TEAL)
    }

    @Test
    fun `restoring twice adds nothing and keeps newer history`() = runBlocking {
        val a = device("a")
        a.seed()
        val file = a.export()
        val b = device("b")
        b.import(file)
        val provider = b.db.sources().allNow().first { it.name == "Provider" }.id
        b.db.history().upsert(HistoryEntity(MediaIds.of(provider, "movie-1"), provider, ItemKind.MOVIE, "A Movie", "2020", null, 300_000, 600_000, 9_000))

        val again = b.import(file)

        assertThat(again.addedIds).isEmpty()
        assertThat(again.alreadyThere).isEqualTo(2)
        assertThat(b.db.sources().allNow()).hasSize(2)
        assertThat(b.db.history().get(MediaIds.of(provider, "movie-1"))!!.positionMs).isEqualTo(300_000)
    }

    @Test
    fun `a wrong passphrase changes nothing`() {
        val a = device("a")
        runBlocking { a.seed() }
        val b = device("b")

        val e = assertThrows(BackupException::class.java) { b.import(a.export(), "wrong horse".toCharArray()) }

        assertThat(e.reason).isEqualTo(BackupException.Reason.WRONG_PASSPHRASE)
        assertThat(runBlocking { b.db.sources().allNow() }).isEmpty()
    }
}
