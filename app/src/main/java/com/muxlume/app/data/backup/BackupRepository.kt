package com.muxlume.app.data.backup

import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.TooLargeException
import com.muxlume.app.core.net.Urls
import com.muxlume.app.data.Accent
import com.muxlume.app.data.AppSettings
import com.muxlume.app.data.SettingsRepository
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.SourceSecret
import com.muxlume.app.data.ThemeMode
import com.muxlume.app.data.db.ChannelPrefEntity
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.epg.EpgRepository
import com.muxlume.app.playback.MediaIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What a backup holds. Items, guide data and caches are not saved: a refresh brings them back. */
@Serializable
data class BackupData(
    val version: Int = VERSION,
    val sources: List<BackupSource> = emptyList(),
    val favorites: List<BackupFavorite> = emptyList(),
    val history: List<BackupHistory> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
    val channelPrefs: List<BackupChannelPref> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class BackupSource(val name: String, val type: SourceType, val secret: SourceSecret, val allowHttp: Boolean, val guideEnabled: Boolean)

/** [source] is an index into [BackupData.sources]. */
@Serializable
data class BackupFavorite(val source: Int, val stableKey: String, val addedAt: Long)

/** Hidden, renamed and pinned channels. [source] is an index into [BackupData.sources]. */
@Serializable
data class BackupChannelPref(val source: Int, val stableKey: String, val hidden: Boolean = false, val customName: String? = null, val pinned: Boolean = false)

@Serializable
data class BackupHistory(
    val source: Int,
    val stableKey: String,
    val kind: ItemKind,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val parentKey: String? = null,
)

@Serializable
data class BackupSettings(
    val theme: String? = null,
    val accent: String? = null,
    val amoled: Boolean? = null,
    val dynamicColor: Boolean? = null,
    val reduceMotion: Boolean? = null,
    val backgroundPlayback: Boolean? = null,
    val guideWifiOnly: Boolean? = null,
)

data class ExportSummary(val sources: Int, val skippedFileSources: Int, val favorites: Int, val history: Int)

/** [addedIds] are the new sources; they have no channels until refreshed. */
data class ImportSummary(val addedIds: List<Long>, val alreadyThere: Int, val skipped: Int, val favorites: Int, val history: Int)

/**
 * Local backup and restore: one passphrase-encrypted file the user saves wherever they like.
 * Playlists that point at a file on this device cannot move to another one and are left out.
 */
class BackupRepository(
    private val db: MuxlumeDatabase,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
) {
    suspend fun export(out: OutputStream, passphrase: CharArray): ExportSummary = withContext(Dispatchers.Default) {
        val all = db.sources().allNow()
        val (portable, files) = all.partition { it.type != SourceType.M3U_FILE }
        val index = portable.withIndex().associate { (i, s) -> s.id to i }
        val favorites = db.favorites().all().mapNotNull { f -> index[f.sourceId]?.let { BackupFavorite(it, f.stableKey, f.addedAt) } }
        val history = db.history().all().mapNotNull { h ->
            val i = index[h.sourceId] ?: return@mapNotNull null
            val key = MediaIds.parse(h.mediaId)?.second ?: return@mapNotNull null
            BackupHistory(i, key, h.kind, h.title, h.subtitle, h.artwork, h.positionMs, h.durationMs, h.updatedAt, h.parentKey)
        }
        val prefs = db.channelPrefs().all().mapNotNull { p ->
            index[p.sourceId]?.let { BackupChannelPref(it, p.stableKey, p.hidden, p.customName, p.pinned) }
        }
        val data = BackupData(
            sources = portable.map { BackupSource(it.name, it.type, sources.secretOf(it), it.allowHttp, it.guideEnabled) },
            favorites = favorites,
            history = history,
            settings = settings.current().toBackup(),
            channelPrefs = prefs,
        )
        out.write(BackupCrypto.encrypt(JSON.encodeToString(BackupData.serializer(), data).toByteArray(), passphrase))
        out.flush()
        ExportSummary(portable.size, files.size, favorites.size, history.size)
    }

    suspend fun import(input: InputStream, passphrase: CharArray): ImportSummary = withContext(Dispatchers.Default) {
        val bytes = try {
            LimitedInputStream(input, MAX_FILE).readBytes()
        } catch (e: TooLargeException) {
            throw BackupException(BackupException.Reason.TOO_LARGE).apply { initCause(e) }
        }
        val data = try {
            JSON.decodeFromString(BackupData.serializer(), BackupCrypto.decrypt(bytes, passphrase).decodeToString())
        } catch (e: SerializationException) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP).apply { initCause(e) }
        }
        if (data.version > BackupData.VERSION) throw BackupException(BackupException.Reason.TOO_NEW)
        val rows = maxOf(data.favorites.size, data.history.size, data.channelPrefs.size)
        if (data.sources.size > MAX_SOURCES || rows > MAX_ROWS) throw BackupException(BackupException.Reason.TOO_LARGE)
        restore(data)
    }

    private suspend fun restore(data: BackupData): ImportSummary {
        val existing = db.sources().allNow().associateBy { identity(it.type, sources.secretOf(it)) }
        val ids = arrayOfNulls<Long>(data.sources.size)
        val added = ArrayList<Long>()
        var already = 0
        var skipped = 0
        data.sources.forEachIndexed { i, s ->
            val same = existing[identity(s.type, s.secret)]
            if (same != null) {
                ids[i] = same.id
                already++
                return@forEachIndexed
            }
            val id = runCatching { add(s) }.getOrNull()
            if (id == null) {
                skipped++
            } else {
                ids[i] = id
                added += id
            }
        }
        var favorites = 0
        data.favorites.forEach { f ->
            val id = ids.getOrNull(f.source) ?: return@forEach
            db.favorites().add(FavoriteEntity(id, f.stableKey, f.addedAt))
            favorites++
        }
        data.channelPrefs.forEach { p ->
            val id = ids.getOrNull(p.source) ?: return@forEach
            val name = p.customName?.trim()?.take(ChannelPrefEntity.MAX_NAME)?.ifEmpty { null }
            val pref = ChannelPrefEntity(id, p.stableKey, p.hidden, name, p.pinned)
            if (p.stableKey.isBlank() || p.stableKey.length > MAX_KEY || pref.isDefault()) return@forEach
            db.channelPrefs().upsert(pref)
        }
        var history = 0
        data.history.forEach { h ->
            val id = ids.getOrNull(h.source) ?: return@forEach
            val mediaId = MediaIds.of(id, h.stableKey)
            if ((db.history().get(mediaId)?.updatedAt ?: Long.MIN_VALUE) >= h.updatedAt) return@forEach // keep the newer one
            db.history().upsert(HistoryEntity(mediaId, id, h.kind, h.title, h.subtitle, h.artwork, h.positionMs, h.durationMs, h.updatedAt, h.parentKey))
            history++
        }
        apply(data.settings)
        return ImportSummary(added, already, skipped, favorites, history)
    }

    /** Goes through the same validation and sealing as a playlist added by hand. */
    private suspend fun add(s: BackupSource): Long {
        val secret = s.secret
        val input = when (s.type) {
            SourceType.XTREAM -> SourceInput.Xtream(s.name, secret.url.orEmpty(), secret.username.orEmpty(), secret.password.orEmpty(), s.allowHttp)
            SourceType.M3U_URL -> {
                if (Urls.parseHttp(secret.url) == null) throw IOException("Invalid link")
                SourceInput.M3uUrl(s.name, secret.url.orEmpty(), s.allowHttp, secret.epgUrl)
            }
            SourceType.M3U_FILE -> throw IOException("Files cannot be restored")
        }
        val id = sources.save(input)
        if (s.guideEnabled) epg.setEnabled(id, true)
        return id
    }

    private suspend fun apply(s: BackupSettings) {
        s.theme?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } }?.let { settings.setTheme(it) }
        s.accent?.let { n -> Accent.entries.firstOrNull { it.name == n } }?.let { settings.setAccent(it) }
        s.amoled?.let { settings.setAmoled(it) }
        s.dynamicColor?.let { settings.setDynamicColor(it) }
        s.reduceMotion?.let { settings.setReduceMotion(it) }
        s.backgroundPlayback?.let { settings.setBackgroundPlayback(it) }
        s.guideWifiOnly?.let { settings.setGuideWifiOnly(it) }
    }

    private fun AppSettings.toBackup() = BackupSettings(theme.name, accent.name, amoled, dynamicColor, reduceMotion, backgroundPlayback, guideWifiOnly)

    /** Two entries are the same playlist when they point at the same place with the same login. */
    private fun identity(type: SourceType, s: SourceSecret) = listOf(type, s.url.orEmpty().trim().trimEnd('/').lowercase(), s.username.orEmpty())

    private companion object {
        const val MAX_FILE = 64L * 1024 * 1024
        const val MAX_SOURCES = 500
        const val MAX_ROWS = 500_000
        const val MAX_KEY = 512
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
