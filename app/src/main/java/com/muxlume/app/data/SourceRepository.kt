package com.muxlume.app.data

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.muxlume.app.core.net.Http.requireSuccess
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.core.net.request
import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.Urls
import com.muxlume.app.core.security.SecretBox
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.m3u.M3uParser
import com.muxlume.app.data.xtream.XtreamAccount
import com.muxlume.app.data.xtream.XtreamApi
import com.muxlume.app.data.xtream.long
import com.muxlume.app.data.xtream.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Everything secret about a source. Sealed with [SecretBox] before it is stored. */
@Serializable
data class SourceSecret(val url: String? = null, val username: String? = null, val password: String? = null)

/** What the user entered on the "add playlist" screen. */
sealed interface SourceInput {
    val name: String
    val allowHttp: Boolean

    data class M3uUrl(override val name: String, val url: String, override val allowHttp: Boolean) : SourceInput
    data class M3uFile(override val name: String, val uri: Uri, val fileName: String, override val allowHttp: Boolean) : SourceInput
    data class Xtream(override val name: String, val server: String, val username: String, val password: String, override val allowHttp: Boolean) : SourceInput
}

data class ImportResult(val live: Int, val movies: Int, val series: Int, val skipped: Int, val duplicates: Int)

class SourceRepository(
    private val db: MuxlumeDatabase,
    private val http: OkHttpClient,
    private val secrets: SecretBox,
    private val resolver: ContentResolver,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val sources: Flow<List<SourceEntity>> = db.sources().all()

    suspend fun get(id: Long): SourceEntity? = db.sources().get(id)

    fun secretOf(source: SourceEntity): SourceSecret = Json.decodeFromString(secrets.open(source.secret))

    fun xtreamApi(source: SourceEntity): XtreamApi {
        val s = secretOf(source)
        val server = XtreamApi.normalizeServer(s.url.orEmpty()) ?: throw IOException("Invalid server address")
        return XtreamApi(http, server, s.username.orEmpty(), s.password.orEmpty(), HttpPolicy.of(source.allowHttp))
    }

    /** Checks Xtream credentials without saving anything. */
    suspend fun testXtream(input: SourceInput.Xtream): XtreamAccount = withContext(Dispatchers.IO) {
        val server = XtreamApi.normalizeServer(input.server) ?: throw IOException("Invalid server address")
        XtreamApi(http, server, input.username.trim(), input.password, HttpPolicy.of(input.allowHttp)).account()
    }

    /** Saves a new source (or replaces the settings of [existingId]) and returns its id. Does not import. */
    suspend fun save(input: SourceInput, existingId: Long? = null): Long {
        val (type, secret, label) = when (input) {
            is SourceInput.M3uUrl -> Triple(SourceType.M3U_URL, SourceSecret(url = input.url.trim()), Urls.redact(input.url))
            is SourceInput.M3uFile -> Triple(SourceType.M3U_FILE, SourceSecret(url = input.uri.toString()), input.fileName)
            is SourceInput.Xtream -> {
                val server = XtreamApi.normalizeServer(input.server) ?: throw IOException("Invalid server address")
                Triple(SourceType.XTREAM, SourceSecret(server.toString(), input.username.trim(), input.password), Urls.redact(server.toString()))
            }
        }
        val sealed = secrets.seal(Json.encodeToString(SourceSecret.serializer(), secret))
        val name = input.name.trim().ifEmpty { label }.take(80)
        val existing = existingId?.let { db.sources().get(it) }
        return if (existing != null) {
            db.sources().update(existing.copy(name = name, type = type, secret = sealed, label = label, allowHttp = input.allowHttp))
            existing.id
        } else {
            db.sources().insert(SourceEntity(name = name, type = type, secret = sealed, label = label, allowHttp = input.allowHttp, createdAt = now()))
        }
    }

    suspend fun rename(id: Long, name: String) {
        val s = db.sources().get(id) ?: return
        db.sources().update(s.copy(name = name.trim().ifEmpty { s.label }.take(80)))
    }

    suspend fun delete(id: Long) {
        val s = db.sources().get(id)
        db.sources().delete(id) // items, favorites and history cascade
        if (s?.type == SourceType.M3U_FILE) {
            runCatching { resolver.releasePersistableUriPermission(secretOf(s).url.orEmpty().toUri(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /**
     * Downloads and replaces the source's items. New rows are written in short batches as hidden
     * `pending` rows, so the rest of the app keeps reading and writing during a long import; one short
     * transaction at the end swaps them in. If the import fails or is cancelled, the staged rows are
     * dropped and the previous items stay as they were. [onProgress] gets the item count.
     */
    suspend fun refresh(id: Long, onProgress: (Int) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val source = db.sources().get(id) ?: throw IOException("Source not found")
        db.items().deletePending(id) // left over if the app was killed during an earlier import
        try {
            when (source.type) {
                SourceType.M3U_URL, SourceType.M3U_FILE -> importM3u(source, onProgress)
                SourceType.XTREAM -> importXtream(source, onProgress)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { db.items().deletePending(id) }
            throw e
        } catch (e: Exception) {
            // IOException for network/format problems, SecurityException for a revoked file permission.
            withContext(NonCancellable) {
                db.items().deletePending(id)
                db.sources().setError(id, ErrorText.of(e))
            }
            throw e
        }
    }

    /** Makes the staged rows visible and records the import, re-reading the source so a rename made meanwhile is kept. */
    private suspend fun commit(id: Long, update: (SourceEntity) -> SourceEntity) = db.withTransaction {
        val current = db.sources().get(id) ?: throw IOException("Source not found")
        db.items().commitPending(id)
        db.sources().update(update(current))
    }

    private fun open(source: SourceEntity): InputStream {
        val location = secretOf(source).url ?: throw IOException("No playlist location")
        val raw = if (source.type == SourceType.M3U_FILE) {
            resolver.openInputStream(location.toUri()) ?: throw IOException("The file can't be opened")
        } else {
            val url = Urls.parseHttp(location) ?: throw IOException("Invalid playlist link")
            http.newCall(request(url, HttpPolicy.of(source.allowHttp))).execute().requireSuccess().body.byteStream()
        }
        return LimitedInputStream(raw, MAX_PLAYLIST_BYTES)
    }

    private suspend fun importM3u(source: SourceEntity, onProgress: (Int) -> Unit): ImportResult = open(source).use { stream ->
        val parser = M3uParser(stream.bufferedReader(Charsets.UTF_8))
        val seen = HashSet<String>()
        var live = 0
        var movies = 0
        var dupes = 0
        val batch = ArrayList<ItemEntity>(BATCH)
        var position = 0
        for (e in parser) {
            val key = stableKey(e.group + "\n" + e.url)
            if (!seen.add(key)) { dupes++; continue }
            val kind = if (e.isVod) ItemKind.MOVIE else ItemKind.LIVE
            if (kind == ItemKind.LIVE) live++ else movies++
            batch += ItemEntity(
                sourceId = source.id, kind = kind, stableKey = key, name = e.name, groupTitle = e.group, logo = e.logo,
                url = e.url, tvgId = e.tvgId, number = e.number, position = position++, pending = true,
                headers = e.headers.entries.joinToString("\n") { "${it.key}\t${it.value}" }.ifEmpty { null },
            )
            if (batch.size == BATCH) {
                currentCoroutineContext().ensureActive()
                db.items().insertAll(batch)
                batch.clear()
                onProgress(position)
            }
        }
        db.items().insertAll(batch)
        onProgress(position)
        commit(source.id) {
            it.copy(
                refreshedAt = now(), lastError = null, liveCount = live, movieCount = movies, seriesCount = 0,
                epgUrl = parser.epgUrl ?: it.epgUrl,
            )
        }
        ImportResult(live, movies, 0, parser.skipped, dupes)
    }

    private suspend fun importXtream(source: SourceEntity, onProgress: (Int) -> Unit): ImportResult {
        val api = xtreamApi(source)
        val account = api.account()
        var count = 0
        val counts = IntArray(3)
        for ((kind, cats, list) in listOf(
            Triple(ItemKind.LIVE, "get_live_categories", "get_live_streams"),
            Triple(ItemKind.MOVIE, "get_vod_categories", "get_vod_streams"),
            Triple(ItemKind.SERIES, "get_series_categories", "get_series"),
        )) {
            val categories = api.categories(cats)
            val batch = ArrayList<ItemEntity>(BATCH)
            var position = 0
            api.streamArray(list) { o ->
                val remoteId = (if (kind == ItemKind.SERIES) o.str("series_id") else o.str("stream_id")) ?: return@streamArray
                batch += ItemEntity(
                    sourceId = source.id, kind = kind, stableKey = "${kind.name.lowercase()}:$remoteId",
                    name = (o.str("name") ?: remoteId).take(512),
                    groupTitle = categories[o.str("category_id")].orEmpty(),
                    logo = (o.str("stream_icon") ?: o.str("cover"))?.takeIf { Urls.parseHttp(it) != null },
                    remoteId = remoteId,
                    extension = o.str("container_extension"),
                    tvgId = o.str("epg_channel_id"),
                    number = o.long("num")?.toInt(),
                    plot = o.str("plot"),
                    year = o.str("year") ?: o.str("releaseDate")?.take(4),
                    rating = o.str("rating")?.takeIf { it != "0" },
                    position = position++,
                    addedAt = o.long("added") ?: o.long("last_modified"),
                    pending = true,
                )
                if (batch.size == BATCH) {
                    currentCoroutineContext().ensureActive()
                    db.items().insertAll(batch)
                    batch.clear()
                    onProgress(count + position)
                }
            }
            db.items().insertAll(batch)
            counts[kind.ordinal] = position
            count += position
            onProgress(count)
        }
        // epgUrl stays null for Xtream: the guide URL contains the credentials and is built when needed.
        commit(source.id) {
            it.copy(
                refreshedAt = now(), lastError = null, liveCount = counts[0], movieCount = counts[1], seriesCount = counts[2],
                liveExtension = account.liveExtension, expiresAt = account.expiresAtEpochSec,
            )
        }
        return ImportResult(counts[0], counts[1], counts[2], 0, 0)
    }

    companion object {
        private const val BATCH = 1000
        private const val MAX_PLAYLIST_BYTES = 256L * 1024 * 1024

        fun stableKey(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
