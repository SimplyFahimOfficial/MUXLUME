package com.muxlume.app.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.recording.Recorder
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What each server receives of a playlist's headers (synthetic values), through the request builders the app uses: the player's
 * [StreamRequests] and the [Recorder]. Two local servers are two origins (the port differs).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class StreamHeadersTest {
    private class Origin(handler: (RecordedRequest) -> MockResponse) {
        val server = MockWebServer()
        val seen = CopyOnWriteArrayList<RecordedRequest>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    return handler(request)
                }
            }
            server.start()
        }

        fun url(path: String) = server.url(path)
        fun header(path: String, name: String): String? = seen.last { it.url.encodedPath == path }.headers[name]
    }

    private val ua = "synthetic-ua/1.0"
    private val full = "https://portal.test/app/page?token=SYNTHETIC-TOKEN"
    private val originOnly = "https://portal.test/"
    private val headers = mapOf("User-Agent" to ua, "Referer" to full)

    private val origins = mutableListOf<Origin>()
    private fun origin(handler: (RecordedRequest) -> MockResponse = { ok() }) = Origin(handler).also { origins += it }

    @After fun tearDown() = origins.forEach { it.server.close() }

    private fun ok() = MockResponse.Builder().body(Buffer().write(ByteArray(100))).build()
    private fun redirect(to: String) = MockResponse.Builder().code(302).addHeader("Location", to).build()
    private fun text(body: String) = MockResponse.Builder().body(body).build()

    private val client = Http.client()

    /** The player's request builder for an item of a source with [policy] (http allowed, as the local servers are plain http). */
    private fun player(headers: Map<String, String>, policy: HttpPolicy, stillAllowed: () -> Boolean = { true }): DataSource.Factory =
        StreamRequests(client).factoryFor(headers, policy, stillAllowed)

    private fun DataSource.fetch(url: okhttp3.HttpUrl) {
        try {
            open(DataSpec(Uri.parse(url.toString())))
            read(ByteArray(200), 0, 200)
        } finally {
            close()
        }
    }

    private fun open(factory: DataSource.Factory, url: okhttp3.HttpUrl) = factory.createDataSource().fetch(url)

    // ---- Playback

    @Test
    fun `playback sends the playlist headers whole to the stream's own origin`() {
        val a = origin()
        open(player(headers, HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/live.ts"))), a.url("/live.ts"))
        assertThat(a.header("/live.ts", "User-Agent")).isEqualTo(ua)
        assertThat(a.header("/live.ts", "Referer")).isEqualTo(full)
    }

    @Test
    fun `playback to another origin keeps the user agent and sends the referer without its path and token`() {
        val b = origin()
        val a = origin { redirect(b.url("/live.ts").toString()) }
        open(player(headers, HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/redir.ts"))), a.url("/redir.ts")) // a redirect to a CDN
        assertThat(a.header("/redir.ts", "Referer")).isEqualTo(full)
        assertThat(b.header("/live.ts", "User-Agent")).isEqualTo(ua)
        assertThat(b.header("/live.ts", "Referer")).isEqualTo(originOnly)
    }

    @Test
    fun `an HLS variant or segment on another origin gets the narrowed referer, one on the stream's origin the whole one`() {
        val a = origin()
        val b = origin()
        val factory = player(headers, HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/master.m3u8")))
        open(factory, a.url("/master.m3u8"))
        open(factory, a.url("/seg-same-origin.ts"))
        open(factory, b.url("/variant.m3u8"))
        open(factory, b.url("/seg-cdn.ts"))
        assertThat(a.header("/master.m3u8", "Referer")).isEqualTo(full)
        assertThat(a.header("/seg-same-origin.ts", "Referer")).isEqualTo(full)
        assertThat(b.header("/variant.m3u8", "Referer")).isEqualTo(originOnly)
        assertThat(b.header("/seg-cdn.ts", "Referer")).isEqualTo(originOnly)
        assertThat(b.header("/seg-cdn.ts", "User-Agent")).isEqualTo(ua)
    }

    @Test
    fun `a referer with a login in it, or one that is no url, is not sent across origins as it is`() {
        val a = origin()
        val b = origin()
        val login = mapOf("Referer" to "http://user:pass@portal.test:8080/panel?u=1#frag")
        open(player(login, HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/x"))), b.url("/login.ts"))
        assertThat(b.header("/login.ts", "Referer")).isEqualTo("http://portal.test:8080/")
        open(player(mapOf("Referer" to "not a url, secret"), HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/x"))), b.url("/junk.ts"))
        assertThat(b.seen.last { it.url.encodedPath == "/junk.ts" }.headers["Referer"]).isNull()
        open(player(mapOf("Referer" to "not a url, secret"), HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/x"))), a.url("/junk.ts")) // its own origin: as given
        assertThat(a.header("/junk.ts", "Referer")).isEqualTo("not a url, secret")
    }

    @Test
    fun `each item's requests keep that item's headers and setting however many items were resolved after it`() {
        val a = origin()
        val b = origin()
        val c = origin()
        val requests = StreamRequests(client)
        // Rapid switching: A plays, the user zaps to B (a source that does not allow HTTP), then to C (another playlist, via failover).
        val itemA = requests.factoryFor(mapOf("User-Agent" to "ua-of-a", "Referer" to "https://a.test/"), HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/")))
        val itemB = requests.factoryFor(mapOf("User-Agent" to "ua-of-b"), HttpPolicy.HTTPS_ONLY.scopedTo(b.url("/")))
        val itemC = requests.factoryFor(mapOf("User-Agent" to "ua-of-c", "Referer" to "https://c.test/"), HttpPolicy.ALLOW_HTTP.scopedTo(c.url("/")))

        // Requests that were still to be made for A and B when C was resolved (a playlist reload, the next segment) are theirs, not C's.
        open(itemA, a.url("/late-a.ts"))
        assertThrows(HttpDataSource.HttpDataSourceException::class.java) { open(itemB, b.url("/late-b.ts")) }
        open(itemC, c.url("/c.ts"))
        open(itemA, a.url("/late-a2.ts"))

        assertThat(a.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly("ua-of-a")
        assertThat(a.seen.map { it.headers["Referer"] }.toSet()).containsExactly("https://a.test/")
        assertThat(b.seen).isEmpty() // B's setting stands: C's allowance did not reach it
        assertThat(c.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly("ua-of-c")
        assertThat(c.header("/c.ts", "Referer")).isEqualTo("https://c.test/")
    }

    @Test
    fun `the player's media source for an item is made with that item's own request builder`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = origin()
        val b = origin()
        val requests = StreamRequests(client)
        val made = mutableListOf<String>()
        val factory = ItemMediaSourceFactory(context) { item ->
            made += item.mediaId
            requests.factoryFor(mapOf("User-Agent" to "ua-${item.mediaId}"), HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/")))
        }
        factory.createMediaSource(androidx.media3.common.MediaItem.Builder().setMediaId("1|a").setUri(a.url("/a.ts").toString()).build())
        factory.createMediaSource(androidx.media3.common.MediaItem.Builder().setMediaId("2|b").setUri(b.url("/b.ts").toString()).build())
        assertThat(made).containsExactly("1|a", "2|b").inOrder()
        assertThat(factory.supportedTypes).asList().containsAtLeast(androidx.media3.common.C.CONTENT_TYPE_HLS, androidx.media3.common.C.CONTENT_TYPE_OTHER)
    }

    @Test
    fun `allowing HTTP is withdrawn from a stream that is playing when its source stops allowing it`() {
        val a = origin()
        var allowed = true
        val factory = player(headers, HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/")), stillAllowed = { allowed })
        open(factory, a.url("/seg1.ts"))
        allowed = false // the user turned "allow unencrypted HTTP" off for this playlist
        val e = assertThrows(HttpDataSource.HttpDataSourceException::class.java) { open(factory, a.url("/seg2.ts")) }
        assertThat(generateSequence<Throwable>(e) { it.cause }.any { it is CleartextBlockedException }).isTrue() // the gate refused it
        assertThat(a.seen.map { it.url.encodedPath }).containsExactly("/seg1.ts")
    }

    // ---- Recording

    private fun recordHls(master: okhttp3.HttpUrl, policy: HttpPolicy) = runBlocking {
        Recorder(client, minPollMs = 10, giveUpAfterMs = 400).record(master.toString(), headers, policy, System.currentTimeMillis() + 5_000, ByteArrayOutputStream())
    }

    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1,\ns0.ts\n#EXT-X-ENDLIST\n"

    @Test
    fun `recording an HLS stream on one origin sends the headers whole to every request`() {
        val a = origin { r -> if (r.url.encodedPath.endsWith(".m3u8")) text(if (r.url.encodedPath == "/master.m3u8") "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n" else media) else ok() }
        recordHls(a.url("/master.m3u8"), HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/master.m3u8")))
        assertThat(a.seen.map { it.url.encodedPath }).containsAtLeast("/master.m3u8", "/media.m3u8", "/s0.ts")
        assertThat(a.seen.map { it.headers["Referer"] }.toSet()).containsExactly(full)
        assertThat(a.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly(ua)
    }

    @Test
    fun `recording an HLS stream whose variant and segments are on a CDN sends that origin the narrowed referer`() {
        val b = origin { r -> if (r.url.encodedPath == "/media.m3u8") text(media) else ok() }
        val a = origin { text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n${b.url("/media.m3u8")}\n") }
        recordHls(a.url("/master.m3u8"), HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/master.m3u8")))
        assertThat(a.header("/master.m3u8", "Referer")).isEqualTo(full)
        assertThat(b.seen.map { it.url.encodedPath }).containsAtLeast("/media.m3u8", "/s0.ts")
        assertThat(b.seen.map { it.headers["Referer"] }.toSet()).containsExactly(originOnly)
        assertThat(b.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly(ua)
    }

    @Test
    fun `recording a direct stream follows a redirect to a CDN with the narrowed referer`() {
        val b = origin()
        val a = origin { redirect(b.url("/live.ts").toString()) }
        recordHls(a.url("/redir.ts"), HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/redir.ts")))
        assertThat(a.header("/redir.ts", "Referer")).isEqualTo(full)
        assertThat(b.header("/live.ts", "Referer")).isEqualTo(originOnly)
        assertThat(b.header("/live.ts", "User-Agent")).isEqualTo(ua)
    }

    @Test
    fun `two recordings at once, of two playlists, each send only their own headers and setting`() {
        val a = origin { r -> if (r.url.encodedPath.endsWith(".m3u8")) text(media) else ok() }
        val b = origin { r -> if (r.url.encodedPath.endsWith(".m3u8")) text(media) else ok() }
        val blocked = origin()
        val headersB = mapOf("User-Agent" to "ua-of-b", "Referer" to "https://b.test/page")
        fun record(url: okhttp3.HttpUrl, headers: Map<String, String>, policy: HttpPolicy) = runCatching {
            runBlocking { Recorder(client, minPollMs = 10, giveUpAfterMs = 400).record(url.toString(), headers, policy, System.currentTimeMillis() + 5_000, ByteArrayOutputStream()) }
        }
        val jobs = listOf(
            Thread { record(a.url("/live.m3u8"), headers, HttpPolicy.ALLOW_HTTP.scopedTo(a.url("/"))) },
            Thread { record(b.url("/live.m3u8"), headersB, HttpPolicy.ALLOW_HTTP.scopedTo(b.url("/"))) },
            Thread { record(blocked.url("/live.m3u8"), headersB, HttpPolicy.HTTPS_ONLY.scopedTo(blocked.url("/"))) },
        )
        jobs.forEach(Thread::start)
        jobs.forEach(Thread::join)
        assertThat(a.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly(ua)
        assertThat(a.seen.map { it.headers["Referer"] }.toSet()).containsExactly(full)
        assertThat(b.seen.map { it.headers["User-Agent"] }.toSet()).containsExactly("ua-of-b")
        assertThat(b.seen.map { it.headers["Referer"] }.toSet()).containsExactly("https://b.test/page")
        assertThat(blocked.seen).isEmpty()
    }

    @Test
    fun `a recording of a source that does not allow HTTP sends nothing`() {
        val a = origin()
        assertThrows(com.muxlume.app.recording.RecordingException::class.java) { recordHls(a.url("/live.ts"), HttpPolicy.HTTPS_ONLY.scopedTo(a.url("/live.ts"))) }
        assertThat(a.seen).isEmpty()
    }

    // ---- The https downgrade protection

    private val tls = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val certs = HandshakeCertificates.Builder().heldCertificate(tls).addTrustedCertificate(tls.certificate).build()

    /** The app's client, trusting the test certificate. */
    private val tlsClient get() = client.newBuilder().sslSocketFactory(certs.sslSocketFactory(), certs.trustManager).build()

    private val extraServers = mutableListOf<MockWebServer>()

    @After fun closeExtra() = extraServers.forEach { it.close() }

    private fun https(handler: (RecordedRequest) -> MockResponse) = MockWebServer().also { server ->
        server.useHttps(certs.sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
        server.start()
        extraServers += server
    }

    private fun redirectCode(url: okhttp3.HttpUrl, policy: HttpPolicy): Int {
        val e = assertThrows(HttpDataSource.HttpDataSourceException::class.java) {
            open(StreamRequests(tlsClient).factoryFor(headers, policy), url)
        }
        return (e as HttpDataSource.InvalidResponseCodeException).responseCode
    }

    @Test
    fun `an https stream that redirects to http is not followed`() {
        val plain = origin()
        val secure = https { redirect(plain.url("/live.ts").toString()) }
        assertThat(redirectCode(secure.url("/redir.ts"), HttpPolicy.ALLOW_HTTP.scopedTo(secure.url("/")))).isEqualTo(302)
        assertThat(plain.seen).isEmpty() // the plain server never saw a request, with headers or without
    }

    @Test
    fun `an http stream that redirects to https is not followed either`() {
        val secure = https { ok() }
        val plain = origin { redirect(secure.url("/live.ts").toString()) }
        assertThat(redirectCode(plain.url("/redir.ts"), HttpPolicy.ALLOW_HTTP.scopedTo(plain.url("/")))).isEqualTo(302)
        assertThat(secure.requestCount).isEqualTo(0)
    }

    @Test
    fun `https keeps working with the headers whole`() {
        val secure = https { ok() }
        val factory = StreamRequests(tlsClient).factoryFor(headers, HttpPolicy.HTTPS_ONLY.scopedTo(secure.url("/")))
        open(factory, secure.url("/live.ts"))
        assertThat(secure.takeRequest().headers["Referer"]).isEqualTo(full)
    }
}
