package com.muxlume.app.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown when a plain-HTTP request is made but the source it belongs to hasn't allowed HTTP (or it belongs to none). */
class CleartextBlockedException(host: String) : IOException("Unencrypted HTTP to $host is not allowed")

/** A server answered with a non-2xx status. The URL is deliberately not part of the message. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** The response is larger than the limit for this kind of download. */
class TooLargeException(limit: Long) : IOException("Response larger than ${limit / (1024 * 1024)} MB")

/**
 * Whether a request may use plain HTTP: the setting of the source it belongs to, attached as an
 * OkHttp request tag (`Request.Builder.tag(HttpPolicy::class.java, …)`). A tag survives redirects.
 *
 * [origin] is where a stream's playlist headers are meant for (see [narrowReferer]); null when the request has no such home. It holds
 * the scheme, host and port only: a stream's path and query can carry its login, and a policy is passed around and may be printed.
 */
data class HttpPolicy(val allowHttp: Boolean, val origin: HttpUrl? = null) {
    /** The same setting for the requests of the stream at [stream]: other origins get its Referer without path and query. */
    fun scopedTo(stream: HttpUrl) = copy(origin = stream.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build())

    companion object {
        val HTTPS_ONLY = HttpPolicy(false)
        val ALLOW_HTTP = HttpPolicy(true)
        fun of(allowHttp: Boolean) = if (allowHttp) ALLOW_HTTP else HTTPS_ONLY
    }
}

/**
 * Android must permit cleartext at the platform level because IPTV hosts are user-defined at
 * runtime (no domain list is possible). This gate is the real control: a plain-HTTP request is
 * refused unless the source it was made for allows HTTP ([HttpPolicy] tag). A request without a
 * tag belongs to no source and is HTTPS-only; artwork of a source that allows HTTP asks for it
 * with [ALLOW_HTTP_HEADER].
 */
object CleartextGate {
    fun permits(request: Request): Boolean = request.url.isHttps || request.tag(HttpPolicy::class.java)?.allowHttp == true

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!permits(request)) throw CleartextBlockedException(request.url.host)
        chain.proceed(request)
    }
}

/**
 * Coil cannot tag a request, so artwork of a source that allows HTTP carries this header instead; [artworkPolicy] turns it into the
 * [HttpPolicy] tag before the gate looks, and it never reaches the network. Only the app's own image requests set it.
 */
const val ALLOW_HTTP_HEADER = "X-Muxlume-Allow-Http"

private val artworkPolicy = Interceptor { chain ->
    val request = chain.request()
    if (request.header(ALLOW_HTTP_HEADER) == null) return@Interceptor chain.proceed(request)
    val stripped = request.newBuilder().removeHeader(ALLOW_HTTP_HEADER)
    // Only a request with no setting of its own is given one: the marker never overrides a source's tag.
    if (request.tag(HttpPolicy::class.java) == null) stripped.tag(HttpPolicy::class.java, HttpPolicy.ALLOW_HTTP)
    chain.proceed(stripped.build())
}

private fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port

/**
 * A playlist's Referer is meant for its stream's own server. Every hop of a request (each redirect, and each playlist and segment the
 * request's [HttpPolicy.origin] points to) that goes to another origin gets only the origin of that Referer, as a browser's cross-origin
 * rule does (without its omission on an https to http hop), so a path or token in it is not handed to a CDN. The same origin keeps it whole; a Referer that is no URL is not sent.
 * Unscoped requests count the call's first URL as their own origin.
 */
internal fun narrowReferer(request: Request, firstUrl: HttpUrl): Request {
    val referer = request.header("Referer") ?: return request
    if (request.url.sameOrigin(request.tag(HttpPolicy::class.java)?.origin ?: firstUrl)) return request
    val origin = Urls.parseHttp(referer)?.newBuilder()?.username("")?.password("")?.encodedPath("/")?.query(null)?.fragment(null)?.build()
    return request.newBuilder().apply { if (origin == null) removeHeader("Referer") else header("Referer", origin.toString()) }.build()
}

/** A request for [url] that may only use plain HTTP if [policy] allows it. */
fun request(url: HttpUrl, policy: HttpPolicy): Request = Request.Builder().url(url).tag(HttpPolicy::class.java, policy).build()

/**
 * Runs [block], cancelling the blocked [Call] if the coroutine is cancelled (OkHttp's blocking calls ignore coroutine cancellation).
 * Call it from a dispatcher with a spare thread (`Dispatchers.IO`): the watcher that cancels the call runs while [block] is blocked.
 */
suspend fun <T> Call.cancelWithCoroutine(block: suspend () -> T): T = coroutineScope {
    // Undispatched, so it is waiting for the cancellation before the blocking call starts; and a scope that is already cancelled never starts one.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            this@cancelWithCoroutine.cancel() // the call, not the scope; harmless once the call is done
            throw e
        }
    }
    try {
        ensureActive()
        block()
    } finally {
        watcher.cancel()
    }
}

/**
 * Runs [block] with a call factory whose calls are all cancelled if the coroutine is cancelled, however many [block] makes
 * (blocking reads of a response ignore coroutine cancellation). The same rule as [cancelWithCoroutine], for code that makes its own calls.
 * Call it from a dispatcher with a spare thread (`Dispatchers.IO`).
 */
suspend fun <T> Call.Factory.withCancellableCalls(block: suspend (Call.Factory) -> T): T = coroutineScope {
    val calls = ConcurrentLinkedQueue<Call>()
    val cancelled = AtomicBoolean()
    val tracked = Call.Factory { request -> newCall(request).also { calls += it; if (cancelled.get()) it.cancel() } }
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelled.set(true)
            calls.forEach(Call::cancel)
            throw e
        }
    }
    try {
        ensureActive()
        block(tracked)
    } finally {
        watcher.cancel()
    }
}

object Http {
    const val USER_AGENT = "MUXLUME/1.0 (Android)"

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // large playlists stream for minutes; read timeout bounds stalls
        // Never follow a redirect that switches between https and http (blocks silent downgrades).
        .followSslRedirects(false)
        .addInterceptor(artworkPolicy)
        .addInterceptor(CleartextGate.interceptor)
        // A network interceptor sees every hop, redirects included.
        .addNetworkInterceptor { chain ->
            val req = narrowReferer(chain.request(), chain.call().request().url)
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
