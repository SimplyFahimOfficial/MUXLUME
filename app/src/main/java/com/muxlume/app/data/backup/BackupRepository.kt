package com.muxlume.app.data.backup

import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.TooLargeException
import com.muxlume.app.core.net.Urls
import com.muxlume.app.data.Accent
import com.muxlume.app.data.AppSettings
import com.muxlume.app.data.PlaylistRefresh
import com.muxlume.app.data.SettingsRepository
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.SourceSecret
import com.muxlume.app.data.ThemeMode
import com.muxlume.app.data.db.ChannelPrefEntity
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.KeyAliasEntity
import com.muxlume.app.data.db.LockedGroupEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.epg.EpgRepository
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.playback.TrackLanguages
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
    /** Which groups the parental PIN covers. The PIN itself is never saved. */
    val lockedGroups: List<BackupLockedGroup> = emptyList(),
    /** Saved servers of channels. Absent in older backups. */
    val servers: List<BackupServer> = emptyList(),
    /** Which older key a channel kept through a rename. Absent in older backups. */
    val keyAliases: List<BackupKeyAlias> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class BackupSource(val name: String, val type: SourceType, val secret: SourceSecret, val allowHttp: Boolean, val guideEnabled: Boolean)

/** [source] is an index into [BackupData.sources]. */
@Serializable
data class BackupFavorite(
    val source: Int,
    val stableKey: String,
    val addedAt: Long,
    /** Place in the order the user arranged; absent in older backups and for favorites never placed. */
    val order: Int? = null,
)

/** [source] is an index into [BackupData.sources]. See [com.muxlume.app.data.db.KeyAliasEntity]. */
@Serializable
data class BackupKeyAlias(val source: Int, val linkKey: String, val stableKey: String)

/** A saved server: [serverSource] and [source] are indexes into [BackupData.sources]. */
@Serializable
data class BackupServer(val source: Int, val stableKey: String, val serverSource: Int, val serverStableKey: String, val position: Int)

/** Hidden, renamed and pinned channels. [source] is an index into [BackupData.sources]. */
@Serializable
data class BackupChannelPref(val source: Int, val stableKey: String, val hidden: Boolean = false, val customName: String? = null, val pinned: Boolean = false)

@Serializable
data class BackupLockedGroup(val source: Int, val groupTitle: String)

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
    /** "auto" = Automatic; absent in backups made before these existed (then the setting is left alone). */
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val playlistRefresh: String? = null,
    val recordLeadMinutes: Int? = null,
    val recordTrailMinutes: Int? = null,
    val failover: Boolean? = null,
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
        val favorites = db.favorites().all().mapNotNull { f -> index[f.sourceId]?.let { BackupFavorite(it, f.stableKey, f.addedAt, f.sortOrder) } }
        val history = db.history().all().mapNotNull { h ->
            val i = index[h.sourceId] ?: return@mapNotNull null
            val key = MediaIds.parse(h.mediaId)?.second ?: return@mapNotNull null
            BackupHistory(i, key, h.kind, h.title, h.subtitle, h.artwork, h.positionMs, h.durationMs, h.updatedAt, h.parentKey)
        }
        val prefs = db.channelPrefs().all().mapNotNull { p ->
            index[p.sourceId]?.let { BackupChannelPref(it, p.stableKey, p.hidden, p.customName, p.pinned) }
        }
        val servers = db.channelServers().all().mapNotNull { s ->
            val from = index[s.sourceId] ?: return@mapNotNull null
            index[s.serverSourceId]?.let { BackupServer(from, s.stableKey, it, s.serverStableKey, s.position) }
        }
        val data = BackupData(
            sources = portable.map { BackupSource(it.name, it.type, sources.secretOf(it), it.allowHttp, it.guideEnabled) },
            favorites = favorites,
            history = history,
            settings = settings.current().toBackup(),
            channelPrefs = prefs,
            lockedGroups = db.lockedGroups().all().mapNotNull { g -> index[g.sourceId]?.let { BackupLockedGroup(it, g.groupTitle) } },
            servers = servers,
            keyAliases = aliasesFor(index, favorites, history, prefs, servers),
        )
        out.write(BackupCrypto.encrypt(JSON.encodeToString(BackupData.serializer(), data).toByteArray(), passphrase))
        out.flush()
        ExportSummary(portable.size, files.size, favorites.size, history.size)
    }

    /** Only the remembered keys that a saved favorite, preference, history entry or server refers to: the rest cannot reconnect anything. */
    private suspend fun aliasesFor(
        index: Map<Long, Int>,
        favorites: List<BackupFavorite>,
        history: List<BackupHistory>,
        prefs: List<BackupChannelPref>,
        servers: List<BackupServer>,
    ): List<BackupKeyAlias> {
        val used = HashSet<Pair<Int, String>>()
        favorites.forEach { used += it.source to it.stableKey }
        history.forEach { used += it.source to it.stableKey }
        prefs.forEach { used += it.source to it.stableKey }
        servers.forEach { used += it.source to it.stableKey; used += it.serverSource to it.serverStableKey }
        return db.keyAliases().all().mapNotNull { a -> index[a.sourceId]?.takeIf { (it to a.stableKey) in used }?.let { BackupKeyAlias(it, a.linkKey, a.stableKey) } }
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
        val rows = maxOf(data.favorites.size, data.history.size, data.channelPrefs.size, data.lockedGroups.size, data.servers.size, data.keyAliases.size)
        if (data.sources.size > MAX_SOURCES || rows > MAX_ROWS) throw BackupException(BackupException.Reason.TOO_LARGE)
        restore(data)
    }

    private suspend fun restore(backup: BackupData): ImportSummary {
        val existing = db.sources().allNow().associateBy { identity(it.type, sources.secretOf(it)) }
        val ids = arrayOfNulls<Long>(backup.sources.size)
        val known = HashSet<Int>() // playlists this device already had
        val added = ArrayList<Long>()
        var already = 0
        var skipped = 0
        backup.sources.forEachIndexed { i, s ->
            val same = existing[identity(s.type, s.secret)]
            if (same != null) {
                ids[i] = same.id
                known += i
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
        val renamed = deviceKeys(backup, ids, known)
        val data = backup.rekeyed(renamed)
        var favorites = 0
        data.favorites.forEach { f ->
            val id = ids.getOrNull(f.source) ?: return@forEach
            db.favorites().add(FavoriteEntity(id, f.stableKey, f.addedAt, f.order?.takeIf { it in 1..MAX_ROWS }))
            favorites++
        }
        restoreChoices(data, ids)
        restoreKeyAliases(data, ids, renamed.keys)
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

    /** Channel preferences and locked groups; [ids] maps backup source indexes to the ids they got here. */
    private suspend fun restoreChoices(data: BackupData, ids: Array<Long?>) {
        data.channelPrefs.forEach { p ->
            val id = ids.getOrNull(p.source) ?: return@forEach
            val name = p.customName?.trim()?.take(ChannelPrefEntity.MAX_NAME)?.ifEmpty { null }
            val pref = ChannelPrefEntity(id, p.stableKey, p.hidden, name, p.pinned)
            if (p.stableKey.isBlank() || p.stableKey.length > MAX_KEY || pref.isDefault()) return@forEach
            db.channelPrefs().upsert(pref)
        }
        // In the order they were added, so the numbering here keeps it.
        data.servers.sortedBy { it.position }.forEach { s ->
            val from = ids.getOrNull(s.source) ?: return@forEach
            val to = ids.getOrNull(s.serverSource) ?: return@forEach
            if (s.stableKey.length <= MAX_KEY && s.serverStableKey.length <= MAX_KEY) db.channelServers().add(from, s.stableKey, to, s.serverStableKey)
        }
        data.lockedGroups.forEach { g ->
            val id = ids.getOrNull(g.source) ?: return@forEach
            if (g.groupTitle.length <= MAX_KEY) db.lockedGroups().add(LockedGroupEntity(id, g.groupTitle))
        }
    }

    /** [renamed] are the channels stored under another key here already: nothing is left to remember for them. */
    private suspend fun restoreKeyAliases(data: BackupData, ids: Array<Long?>, renamed: Set<Pair<Int, String>>) {
        data.keyAliases.forEach { a ->
            val id = ids.getOrNull(a.source) ?: return@forEach
            if ((a.source to a.stableKey) !in renamed && isKey(a.linkKey) && isKey(a.stableKey)) db.keyAliases().add(KeyAliasEntity(id, a.linkKey, a.stableKey))
        }
    }

    /**
     * For a playlist this device already imported, a channel the backup keeps under an older key A may be stored here under the hash B
     * the backup remembers as A's link: B is the key this device's own data refers to, so the backup's data is restored under B.
     * Maps (backup playlist index, A) to B.
     */
    private suspend fun deviceKeys(backup: BackupData, ids: Array<Long?>, known: Set<Int>): Map<Pair<Int, String>, String> {
        val renamed = HashMap<Pair<Int, String>, String>()
        for (a in backup.keyAliases) {
            val id = ids.getOrNull(a.source)?.takeIf { a.source in known } ?: continue
            if (db.items().hasKey(id, a.linkKey) && !db.items().hasKey(id, a.stableKey)) renamed[a.source to a.stableKey] = a.linkKey
        }
        return renamed
    }

    private fun BackupData.rekeyed(renamed: Map<Pair<Int, String>, String>): BackupData {
        if (renamed.isEmpty()) return this
        fun key(source: Int, key: String) = renamed[source to key] ?: key
        return copy(
            favorites = favorites.map { it.copy(stableKey = key(it.source, it.stableKey)) },
            history = history.map { it.copy(stableKey = key(it.source, it.stableKey)) },
            channelPrefs = channelPrefs.map { it.copy(stableKey = key(it.source, it.stableKey)) },
            servers = servers.map { it.copy(stableKey = key(it.source, it.stableKey), serverStableKey = key(it.serverSource, it.serverStableKey)) },
        )
    }

    private fun isKey(key: String) = key.isNotBlank() && key.length <= MAX_KEY

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
        language(s.audioLanguage, subtitles = false)?.let { settings.setAudioLanguage(it.value) }
        language(s.subtitleLanguage, subtitles = true)?.let { settings.setSubtitleLanguage(it.value) }
        applyLibrary(s)
    }

    private suspend fun applyLibrary(s: BackupSettings) {
        s.recordLeadMinutes?.takeIf { it in AppSettings.LEAD_CHOICES }?.let { settings.setRecordLeadMinutes(it) }
        s.recordTrailMinutes?.takeIf { it in AppSettings.TRAIL_CHOICES }?.let { settings.setRecordTrailMinutes(it) }
        s.failover?.let { settings.setFailover(it) }
        s.playlistRefresh?.let { n -> PlaylistRefresh.entries.firstOrNull { it.name == n } }?.let { settings.setPlaylistRefresh(it) }
    }

    /** A language from a backup, checked like any input: null means leave the setting alone, [Choice] with a null value means Automatic. */
    private class Choice(val value: String?)

    private fun language(raw: String?, subtitles: Boolean): Choice? = when {
        raw == null -> null
        raw == TrackLanguages.AUTOMATIC_IN_BACKUP -> Choice(null)
        TrackLanguages.isValid(raw, subtitles) -> Choice(raw)
        else -> null
    }

    private fun AppSettings.toBackup() = BackupSettings(
        theme.name, accent.name, amoled, dynamicColor, reduceMotion, backgroundPlayback, guideWifiOnly,
        audioLanguage ?: TrackLanguages.AUTOMATIC_IN_BACKUP, subtitleLanguage ?: TrackLanguages.AUTOMATIC_IN_BACKUP, playlistRefresh.name,
        recordLeadMinutes, recordTrailMinutes, failover,
    )

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
