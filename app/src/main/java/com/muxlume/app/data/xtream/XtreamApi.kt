package com.muxlume.app.data.xtream

import com.muxlume.app.core.net.Http.requireSuccess
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.core.net.request
import com.muxlume.app.core.net.HttpStatusException
import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.Urls
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream

/** The provider rejected the username/password, or the account is not active. */
class XtreamAuthException(val reason: Reason, val status: String? = null) : IOException("Xtream account: $reason") {
    enum class Reason { INVALID_CREDENTIALS, EXPIRED, INACTIVE }
}

/** The server answered, but not with the Xtream Codes-compatible JSON this app understands. */
class XtreamFormatException(message: String) : IOException(message)

data class XtreamAccount(
    val status: String,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val outputFormats: List<String>,
) {
    /** HLS when the provider offers it (better supported by the player), otherwise MPEG-TS. */
    val liveExtension: String get() = if ("m3u8" in outputFormats) "m3u8" else "ts"
}

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val number: Int?,
    val title: String,
    val extension: String,
    val plot: String?,
    val image: String?,
    val durationSec: Long?,
)

data class XtreamSeriesInfo(val plot: String?, val cover: String?, val episodes: List<XtreamEpisode>)

data class XtreamVodInfo(val plot: String?, val genre: String?, val durationSec: Long?, val releaseDate: String?, val backdrop: String?)

/**
 * Client for Xtream Codes-compatible `player_api.php` services the user is subscribed to.
 *
 * Responses are untrusted: fields are read leniently (providers send numbers as strings and vice
 * versa), big lists are streamed element by element, and every download is size-limited. The
 * request URL contains the credentials, so it never appears in exceptions or logs.
 */
class XtreamApi(
    @PublishedApi internal val client: OkHttpClient,
    val server: HttpUrl,
    private val username: String,
    private val password: String,
    /** The source's HTTP setting; every request to this server carries it. */
    @PublishedApi internal val policy: HttpPolicy = HttpPolicy.HTTPS_ONLY,
) {
    private fun url(action: String?, vararg params: Pair<String, String>): HttpUrl = server.newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .apply {
            if (action != null) addQueryParameter("action", action)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }
        .build()

    @PublishedApi
    internal inline fun <T> get(url: HttpUrl, limit: Long, read: (InputStream) -> T): T {
        val response = client.newCall(request(url, policy)).execute()
        try {
            response.requireSuccess()
        } catch (e: HttpStatusException) {
            if (e.code == 401 || e.code == 403) throw XtreamAuthException(XtreamAuthException.Reason.INVALID_CREDENTIALS)
            throw e
        }
        return response.use { LimitedInputStream(it.body.byteStream(), limit).use(read) }
    }

    private fun obj(url: HttpUrl): JsonObject = get(url, SMALL_LIMIT) { stream ->
        val element = try { JSON.parseToJsonElement(stream.readBytes().decodeToString()) } catch (e: SerializationException) {
            throw XtreamFormatException("Not a JSON response")
        }
        element as? JsonObject ?: throw XtreamFormatException("Unexpected JSON shape")
    }

    fun account(): XtreamAccount = parseAccount(obj(url(null)))

    /** category_id -> category_name */
    fun categories(action: String): Map<String, String> = buildMap {
        streamArray(action) { o -> o.str("category_id")?.let { id -> put(id, o.str("category_name") ?: "") } }
    }

    /** Streams the elements of a JSON array response (live/VOD/series lists can be tens of MB). */
    inline fun streamArray(action: String, onEach: (JsonObject) -> Unit) {
        get(listUrl(action), LIST_LIMIT) { stream -> jsonObjects(stream).forEach(onEach) }
    }

    @PublishedApi
    internal fun listUrl(action: String) = url(action)

    fun seriesInfo(seriesId: String): XtreamSeriesInfo = parseSeriesInfo(obj(url("get_series_info", "series_id" to seriesId)))

    fun vodInfo(vodId: String): XtreamVodInfo {
        val info = obj(url("get_vod_info", "vod_id" to vodId))["info"] as? JsonObject ?: return XtreamVodInfo(null, null, null, null, null)
        return XtreamVodInfo(
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            durationSec = info.long("duration_secs"),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            backdrop = (info["backdrop_path"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNullSafe() }?.takeIf { Urls.parseHttp(it) != null },
        )
    }

    fun liveUrl(streamId: String, ext: String) = playUrl("live", streamId, ext)
    fun movieUrl(streamId: String, ext: String) = playUrl("movie", streamId, ext)
    fun episodeUrl(episodeId: String, ext: String) = playUrl("series", episodeId, ext)

    private fun playUrl(kind: String, id: String, ext: String): String = server.newBuilder()
        .addPathSegment(kind).addPathSegment(username).addPathSegment(password)
        .addPathSegment("$id.${ext.filter { it.isLetterOrDigit() }.ifEmpty { "ts" }}")
        .build().toString()

    /** The provider's XMLTV guide for this account. */
    fun xmltvUrl(): String = server.newBuilder().addPathSegment("xmltv.php")
        .addQueryParameter("username", username).addQueryParameter("password", password).build().toString()

    companion object {
        private const val SMALL_LIMIT = 16L * 1024 * 1024
        const val LIST_LIMIT = 256L * 1024 * 1024

        /**
         * Lazily decodes the objects of a JSON array. Some providers answer an empty list with {} or
         * null instead of [], which yields nothing. Malformed JSON fails with [XtreamFormatException].
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun jsonObjects(stream: InputStream): Sequence<JsonObject> {
            val buffered = stream.buffered()
            buffered.mark(64)
            var first: Int
            do first = buffered.read() while (first >= 0 && Character.isWhitespace(first))
            buffered.reset()
            if (first != '['.code) return emptySequence()
            val elements = JSON.decodeToSequence<JsonElement>(buffered, DecodeSequenceMode.ARRAY_WRAPPED).iterator()
            return sequence {
                while (true) {
                    val next = try {
                        if (!elements.hasNext()) break
                        elements.next()
                    } catch (e: SerializationException) {
                        throw XtreamFormatException("Malformed list response")
                    } catch (e: IllegalArgumentException) {
                        throw XtreamFormatException("Malformed list response")
                    }
                    if (next is JsonObject) yield(next)
                }
            }
        }

        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Accepts `http://host:port`, `host:port`, or a URL ending in player_api.php / get.php. */
        fun normalizeServer(input: String): HttpUrl? {
            val trimmed = input.trim().let { if ("://" in it) it else "http://$it" }
            val url = Urls.parseHttp(trimmed) ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() && it != "player_api.php" && it != "get.php" }
            return url.newBuilder().query(null).fragment(null).encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build()
        }

        fun parseAccount(root: JsonObject): XtreamAccount {
            val user = root["user_info"] as? JsonObject ?: throw XtreamFormatException("No user_info in response")
            if (user.str("auth") != "1") throw XtreamAuthException(XtreamAuthException.Reason.INVALID_CREDENTIALS)
            val status = user.str("status") ?: "Unknown"
            val exp = user.long("exp_date")
            when {
                status.equals("Expired", true) -> throw XtreamAuthException(XtreamAuthException.Reason.EXPIRED, status)
                !status.equals("Active", true) -> throw XtreamAuthException(XtreamAuthException.Reason.INACTIVE, status)
            }
            val formats = (user["allowed_output_formats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() } ?: emptyList()
            return XtreamAccount(status, exp, user.long("max_connections")?.toInt(), formats)
        }

        fun parseSeriesInfo(root: JsonObject): XtreamSeriesInfo {
            val info = root["info"] as? JsonObject
            // "episodes" is {"1": [...], "2": [...]} on most servers and a flat array on some.
            val groups: List<JsonElement> = when (val e = root["episodes"]) {
                is JsonObject -> e.values.toList()
                is JsonArray -> listOf(e)
                else -> emptyList()
            }
            val episodes = groups.flatMap { g -> (g as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } }.mapNotNull { ep ->
                val id = ep.str("id") ?: return@mapNotNull null
                val epInfo = ep["info"] as? JsonObject
                XtreamEpisode(
                    id = id,
                    season = ep.long("season")?.toInt() ?: 1,
                    number = ep.long("episode_num")?.toInt(),
                    title = ep.str("title") ?: "Episode ${ep.str("episode_num") ?: ""}".trim(),
                    extension = ep.str("container_extension") ?: "mp4",
                    plot = epInfo?.str("plot"),
                    image = epInfo?.str("movie_image")?.takeIf { Urls.parseHttp(it) != null },
                    durationSec = epInfo?.long("duration_secs"),
                )
            }.sortedWith(compareBy({ it.season }, { it.number ?: Int.MAX_VALUE }))
            return XtreamSeriesInfo(info?.str("plot"), info?.str("cover")?.takeIf { Urls.parseHttp(it) != null }, episodes)
        }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content.takeIf { it.isNotBlank() }

/** Field as a string whatever its JSON type; null for missing, null or blank. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()?.trim()

internal fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
