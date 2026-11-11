package com.muxlume.app.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Thrown when a plain-HTTP request is made but the user hasn't allowed HTTP for any source. */
class CleartextBlockedException(host: String) : IOException("Unencrypted HTTP to $host is not allowed")

/** A server answered with a non-2xx status. The URL is deliberately not part of the message. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** The response is larger than the limit for this kind of download. */
class TooLargeException(limit: Long) : IOException("Response larger than ${limit / (1024 * 1024)} MB")

/**
 * Whether a request may use plain HTTP: the setting of the source it belongs to, attached as an
 * OkHttp request tag (`Request.Builder.tag(HttpPolicy::class.java, …)`).
 */
data class HttpPolicy(val allowHttp: Boolean) {
    companion object {
        val HTTPS_ONLY = HttpPolicy(false)
        val ALLOW_HTTP = HttpPolicy(true)
        fun of(allowHttp: Boolean) = if (allowHttp) ALLOW_HTTP else HTTPS_ONLY
    }
}

/**
 * Android must permit cleartext at the platform level because IPTV hosts are user-defined at
 * runtime (no domain list is possible). This gate is the real control: a plain-HTTP request is
 * refused unless the source it was made for allows HTTP ([HttpPolicy] tag). Requests without a tag
 * are artwork, which isn't tied to one source and carries no credentials: those may use HTTP when
 * any source allows it ([artworkAllowed]).
 */
object CleartextGate {
    @Volatile var artworkAllowed: Boolean = false

    fun permits(request: Request): Boolean =
        request.url.isHttps || (request.tag(HttpPolicy::class.java)?.allowHttp ?: artworkAllowed)

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!permits(request)) throw CleartextBlockedException(request.url.host)
        chain.proceed(request)
    }
}

/** A request for [url] that may only use plain HTTP if [policy] allows it. */
fun request(url: HttpUrl, policy: HttpPolicy): Request = Request.Builder().url(url).tag(HttpPolicy::class.java, policy).build()

object Http {
    const val USER_AGENT = "MUXLUME/1.0 (Android)"

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // large playlists stream for minutes; read timeout bounds stalls
        // Never follow a redirect that switches between https and http (blocks silent downgrades).
        .followSslRedirects(false)
        .addInterceptor(CleartextGate.interceptor)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", USER_AGENT).build() else req)
        }
        .build()

    /** Fails with [HttpStatusException] on non-2xx responses (closing the response). */
    fun Response.requireSuccess(): Response {
        if (!isSuccessful) {
            close()
            throw HttpStatusException(code)
        }
        return this
    }
}

/** Caps the number of bytes read from an untrusted stream. */
class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

    private fun add(n: Long) {
        count += n
        if (count > limit) throw TooLargeException(limit)
    }
}

object Urls {
    private const val MAX_URL_LENGTH = 4096

    /** A user-entered or provider-supplied http(s) URL, or null if it is not one. */
    fun parseHttp(raw: String?): HttpUrl? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s.length > MAX_URL_LENGTH) return null
        return s.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() }
    }

    /** Stream URLs from untrusted playlists: only network schemes, never file:, content: or others. */
    fun isPlayable(raw: String): Boolean {
        if (raw.length > MAX_URL_LENGTH) return false
        val scheme = raw.substringBefore("://", "").lowercase()
        return when (scheme) {
            "http", "https" -> parseHttp(raw) != null
            "rtsp" -> raw.length > "rtsp://".length && raw.none { it.isWhitespace() }
            else -> false
        }
    }

    /** For display and diagnostics: scheme and host only, never path, query or credentials. */
    fun redact(raw: String?): String {
        val url = parseHttp(raw) ?: return if (raw.isNullOrBlank()) "" else "${raw.substringBefore("://", "?")}://…"
        val port = if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
        return "${url.scheme}://${url.host}$port"
    }
}
