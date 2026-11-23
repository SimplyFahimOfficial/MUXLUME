package com.muxlume.app.data.epg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.Xmltv
import com.muxlume.app.Xmltv.NOW
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.security.SecretBox
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.MuxlumeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Guide refreshes that overlap: the screen and the background worker for one source, a guide turned off or a feed changed while a
 * download runs. A guide response is held mid-body, after rows are staged, with latches; nothing here waits on the clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class EpgRefreshCoordinationTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var sources: SourceRepository
    private lateinit var epg: EpgRepository
    private val box = ArrivalBox()

    /** What the server answers per path: the head of the list is served once and removed, the last one is served from then on. */
    private val routes = ConcurrentHashMap<String, MutableList<String>>()
    private val requested = CopyOnWriteArrayList<String>()
    private val gates = ConcurrentHashMap<String, Gate>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requested += path
                val list = routes[path] ?: return MockResponse.Builder().code(404).build()
                val body = synchronized(list) { if (list.size > 1) list.removeAt(0) else list[0] }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        val linker = EpgLinker(db)
        sources = SourceRepository(db, Http.client(), box, context.contentResolver, context.resources, linker)
        epg = EpgRepository(db, sources, linker, Http.client().newBuilder().addInterceptor(holdBody()).build(), context.resources) { NOW }
    }

    @After
    fun tearDown() {
        gates.values.forEach { it.open.countDown() }
        db.close()
        server.close()
    }

    /** Holds a response body after [after] bytes until [open] is released; gives way when the call is cancelled. */
    private class Gate(val after: Long) {
        val reached = CountDownLatch(1)
        val open = CountDownLatch(1)
    }

    private fun holdBody() = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val gate = gates.remove(chain.request().url.encodedPath) ?: return@Interceptor response
        val body = response.body
        val held = object : ForwardingSource(body.source()) {
            var seen = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (seen >= gate.after) {
                    gate.reached.countDown()
                    // Bounded, so a test that fails before it opens the gate ends instead of waiting for a download nobody can cancel.
                    val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_SECONDS)
                    while (!gate.open.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                        if (chain.call().isCanceled() || System.nanoTime() > giveUp) throw IOException("Canceled")
                    }
                }
                val room = if (seen < gate.after) minOf(byteCount, gate.after - seen) else byteCount
                return super.read(sink, room).also { if (it > 0) seen += it }
            }
        }
        response.newBuilder().body(held.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }

    /**
     * Opens source secrets like a plain box. A refresh opens its source's secret to find the guide link, so a test can wait for that
     * ([nextOpen]) or run something at an exact point of a refresh ([atOpen]: the 1st open is before the refresh takes its turn,
     * the 2nd after it took its turn and read the source, before it starts the download).
     */
    private class ArrivalBox : SecretBox() {
        private val next = AtomicReference<CountDownLatch?>(null)
        private val opens = AtomicInteger()
        @Volatile private var hook: Pair<Int, () -> Unit>? = null

        override fun seal(plain: String): String = Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun open(sealed: String): String {
            next.getAndSet(null)?.countDown()
            val n = opens.incrementAndGet()
            hook?.takeIf { it.first == n }?.let { hook = null; it.second() }
            return String(Base64.getDecoder().decode(sealed))
        }

        fun nextOpen(): CountDownLatch = CountDownLatch(1).also { next.set(it) }

        fun atOpen(n: Int, action: () -> Unit) {
            opens.set(0)
            hook = n to action
        }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue() }

    // ---- Fixtures: a guide of SIZE programmes on one channel, each titled "<tag> <n>", so a mixture of two downloads is visible.

    private fun programmes(tag: String, n: Int) = List(n) { Xmltv.programme("bbc1.uk", NOW + it * STEP, NOW + (it + 1) * STEP, "$tag $it") }

    private fun feed(parts: List<String>) = Xmltv.feed(Xmltv.channel("bbc1.uk", "BBC One HD"), *parts.toTypedArray())

    private fun guide(tag: String, n: Int = SIZE) = feed(programmes(tag, n))

    /** Where [guide] is after its first [count] programmes: enough read for the first staging batch to be written. */
    private fun bytesAfter(tag: String, count: Int) = (feed(programmes(tag, count)).length - "</tv>".length).toLong()

    private fun hold(path: String, tag: String): Gate = Gate(bytesAfter(tag, STAGED_BY)).also { gates[path] = it }

    /** A source with a playlist of two channels and the guide on, ready for [path] to be refreshed. */
    private suspend fun source(name: String, path: String): Long {
        routes["/$name.m3u"] = mutableListOf("#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc1.uk\",BBC One\nhttps://cdn.example/$name-1.m3u8\n#EXTINF:-1,Local Cable\nhttps://cdn.example/$name-2.m3u8\n")
        val id = sources.save(SourceInput.M3uUrl(name, server.url("/$name.m3u").toString(), allowHttp = true, guideUrl = server.url(path).toString()))
        sources.refresh(id)
        epg.setEnabled(id, true)
        return id
    }

    private fun titles(id: Long) = db.openHelper.readableDatabase.query("SELECT title FROM programs WHERE sourceId = ? AND pending = 0", arrayOf<Any>(id))
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun count(sql: String, id: Long) = db.openHelper.readableDatabase.query(sql, arrayOf<Any>(id)).use { it.moveToFirst(); it.getInt(0) }

    private fun staged(id: Long) = count("SELECT (SELECT COUNT(*) FROM programs WHERE sourceId = ?1 AND pending = 1) + (SELECT COUNT(*) FROM epg_channels WHERE sourceId = ?1 AND pending = 1)", id)

    private fun key(id: Long, name: String) = db.openHelper.readableDatabase.query("SELECT stableKey FROM items WHERE sourceId = ? AND name = ?", arrayOf<Any>(id, name))
        .use { it.moveToFirst(); it.getString(0) }

    /** Everything visible is from one download: all programmes of [tag], all of them, and nothing staged. */
    private fun assertWholeGuide(id: Long, tag: String, n: Int = SIZE) {
        assertThat(titles(id)).containsExactlyElementsIn(List(n) { "$tag $it" })
        assertThat(staged(id)).isEqualTo(0)
    }

    @Test
    fun `a manual and a worker refresh of one source leave one whole guide`(): Unit = runBlocking {
        val id = source("s", "/guide.xml")
        // A long tail after the held point: a second refresh that is not kept out has plenty of time to act before the first one ends.
        routes["/guide.xml"] = mutableListOf(guide("A", BIG), guide("B", BIG))
        val gate = hold("/guide.xml", "A")

        val worker = async(Dispatchers.IO) { epg.refresh(id) }
        await(gate.reached)
        assertThat(staged(id)).isGreaterThan(0) // staging is under way
        val arrived = box.nextOpen()
        val manual = async(Dispatchers.IO) { epg.refresh(id) } // the user taps Refresh now
        await(arrived)
        gate.open.countDown()
        worker.await()
        manual.await()

        assertThat(requested.count { it == "/guide.xml" }).isEqualTo(1) // the manual refresh used the worker's guide
        val tag = titles(id).first().substringBefore(' ')
        assertWholeGuide(id, tag, BIG)
        assertThat(sources.get(id)!!.epgPrograms).isEqualTo(BIG)
        assertThat(sources.get(id)!!.epgError).isNull()
    }

    @Test
    fun `turning the guide off while it downloads leaves it empty when the old response ends`(): Unit = runBlocking {
        val id = source("s", "/guide.xml")
        routes["/guide.xml"] = mutableListOf(guide("A"))
        val gate = hold("/guide.xml", "A")
        val running = async(Dispatchers.IO) { epg.refresh(id) }
        await(gate.reached)

        epg.setEnabled(id, false)
        gate.open.countDown()
        running.await()

        assertThat(titles(id)).isEmpty()
        assertThat(staged(id)).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM epg_channels WHERE sourceId = ?", id)).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM epg_links WHERE sourceId = ?", id)).isEqualTo(0)
        val s = sources.get(id)!!
        assertThat(s.guideEnabled).isFalse()
        assertThat(s.epgRefreshedAt).isNull()
        assertThat(s.epgPrograms).isEqualTo(0)
        assertThat(s.epgError).isNull()
    }

    @Test
    fun `a guide turned off and on again is not filled by the download that was running`(): Unit = runBlocking {
        val id = source("s", "/guide.xml")
        routes["/guide.xml"] = mutableListOf(guide("A"), guide("B"))
        val gate = hold("/guide.xml", "A")
        val running = async(Dispatchers.IO) { epg.refresh(id) }
        await(gate.reached)

        epg.setEnabled(id, false)
        epg.setEnabled(id, true)
        gate.open.countDown()
        running.await()
        assertThat(titles(id)).isEmpty() // the old download belongs to the guide the user turned off

        epg.refresh(id)
        assertWholeGuide(id, "B")
    }

    @Test
    fun `a download that fails after the guide was turned off stores no error`(): Unit = runBlocking {
        val id = source("s", "/guide.xml")
        routes["/guide.xml"] = mutableListOf(guide("A").dropLast(40)) // cut off: fails once it is read to the end
        val gate = hold("/guide.xml", "A")
        val running = async(Dispatchers.IO) { epg.refresh(id) }
        await(gate.reached)

        epg.setEnabled(id, false)
        gate.open.countDown()
        running.await()

        assertThat(sources.get(id)!!.epgError).isNull()
        assertThat(titles(id)).isEmpty()
        assertThat(staged(id)).isEqualTo(0)
    }

    @Test
    fun `a feed changed during a download is not replaced by the old one, and the stalled download is released`(): Unit = runBlocking {
        val id = source("s", "/a.xml")
        routes["/a.xml"] = mutableListOf(guide("A"))
        routes["/b.xml"] = mutableListOf(guide("B"))
        val gate = hold("/a.xml", "A")
        val old = async(Dispatchers.IO) { epg.refresh(id) }
        await(gate.reached)

        sources.save(SourceInput.M3uUrl("s", server.url("/s.m3u").toString(), allowHttp = true, guideUrl = server.url("/b.xml").toString()), id)
        val current = async(Dispatchers.IO) { epg.refresh(id) }
        withTimeout(10_000) { current.await() } // not held up until the old feed's response is let go
        assertWholeGuide(id, "B")

        gate.open.countDown()
        old.await()
        assertWholeGuide(id, "B")
        val s = sources.get(id)!!
        assertThat(s.epgError).isNull()
        assertThat(s.epgPrograms).isEqualTo(SIZE)
    }

    @Test
    fun `a feed saved during a download, with no new refresh asked for, is not filled by the old feed`(): Unit = runBlocking {
        val id = source("s", "/a.xml")
        routes["/a.xml"] = mutableListOf(guide("A"))
        val gate = hold("/a.xml", "A")
        val old = async(Dispatchers.IO) { epg.refresh(id) }
        await(gate.reached)

        sources.save(SourceInput.M3uUrl("s", server.url("/s.m3u").toString(), allowHttp = true, guideUrl = server.url("/b.xml").toString()), id)
        gate.open.countDown()
        old.await()

        assertThat(titles(id)).isEmpty()
        assertThat(staged(id)).isEqualTo(0)
        val s = sources.get(id)!!
        assertThat(s.epgRefreshedAt).isNull()
        assertThat(s.epgError).isNull()
    }

    @Test
    fun `a guide turned off just after a refresh took its turn downloads nothing`(): Unit = runBlocking {
        val id = source("s", "/guide.xml")
        routes["/guide.xml"] = mutableListOf(guide("A"))
        box.atOpen(2) { runBlocking(Dispatchers.IO) { epg.setEnabled(id, false) } } // after the refresh read the source as on, before its call exists
        epg.refresh(id)

        assertThat(requested).doesNotContain("/guide.xml")
        assertThat(titles(id)).isEmpty()
        assertThat(staged(id)).isEqualTo(0)
    }

    @Test
    fun `a feed saved just after a refresh took its turn downloads nothing from the old feed`(): Unit = runBlocking {
        val id = source("s", "/a.xml")
        routes["/a.xml"] = mutableListOf(guide("A"))
        routes["/b.xml"] = mutableListOf(guide("B"))
        // The user saves the new feed after the refresh read the source, before it has a call that could be cancelled.
        box.atOpen(2) { runBlocking(Dispatchers.IO) { sources.save(SourceInput.M3uUrl("s", server.url("/s.m3u").toString(), allowHttp = true, guideUrl = server.url("/b.xml").toString()), id) } }
        epg.refresh(id)
        assertThat(requested).doesNotContain("/a.xml")
        assertThat(titles(id)).isEmpty()

        epg.refresh(id)
        assertWholeGuide(id, "B")
    }

    @Test
    fun `a refresh that waited is not taken for the one the new feed needs`(): Unit = runBlocking {
        val id = source("s", "/a.xml")
        routes["/a.xml"] = mutableListOf(guide("A"))
        routes["/b.xml"] = mutableListOf(guide("B"))
        // Asked for while the feed is A. Before it takes its turn another refresh publishes A and the feed becomes B.
        box.atOpen(1) {
            runBlocking(Dispatchers.IO) {
                epg.refresh(id)
                sources.save(SourceInput.M3uUrl("s", server.url("/s.m3u").toString(), allowHttp = true, guideUrl = server.url("/b.xml").toString()), id)
            }
        }
        epg.refresh(id)
        assertWholeGuide(id, "B")
    }

    @Test
    fun `a refresh of a guide that is off downloads nothing`(): Unit = runBlocking {
        val id = source("s", "/guide.xml")
        routes["/guide.xml"] = mutableListOf(guide("A"))
        epg.setEnabled(id, false) // the worker listed the source before the user got here
        epg.refresh(id)

        assertThat(requested).doesNotContain("/guide.xml")
        assertThat(titles(id)).isEmpty()
    }

    @Test
    fun `a failed or cancelled refresh keeps the previous guide and the channels the user mapped`(): Unit = runBlocking {
        val id = source("s", "/guide.xml")
        routes["/guide.xml"] = mutableListOf(guide("A"), guide("B"), "")
        epg.refresh(id)
        epg.setManualLink(id, key(id, "Local Cable"), "bbc1.uk")
        val refreshedAt = sources.get(id)!!.epgRefreshedAt
        assertWholeGuide(id, "A")

        val gate = hold("/guide.xml", "B")
        val cancelled = launch(Dispatchers.IO) { epg.refresh(id) }
        await(gate.reached)
        assertThat(staged(id)).isGreaterThan(0)
        withTimeout(10_000) { cancelled.cancelAndJoin() } // the blocked download is cancelled, not waited for

        assertWholeGuide(id, "A")
        assertThat(db.epg().link(id, key(id, "Local Cable"))!!.manual).isTrue()
        assertThat(sources.get(id)!!.epgRefreshedAt).isEqualTo(refreshedAt)
        assertThat(sources.get(id)!!.epgError).isNull() // giving up is not an error

        kotlin.runCatching { epg.refresh(id) } // an empty body is not a guide
        assertWholeGuide(id, "A")
        assertThat(db.epg().link(id, key(id, "Local Cable"))!!.manual).isTrue()
        assertThat(sources.get(id)!!.epgError).isNotNull()
    }

    @Test
    fun `a source whose download is held does not hold back another source`(): Unit = runBlocking {
        val first = source("one", "/one.xml")
        val second = source("two", "/two.xml")
        routes["/one.xml"] = mutableListOf(guide("A"))
        routes["/two.xml"] = mutableListOf(guide("B"))
        val gate = hold("/one.xml", "A")
        val held = async(Dispatchers.IO) { epg.refresh(first) }
        await(gate.reached)

        withTimeout(10_000) { epg.refresh(second) }
        assertWholeGuide(second, "B")
        assertThat(titles(first)).isEmpty() // the held one has published nothing

        gate.open.countDown()
        held.await()
        assertWholeGuide(first, "A")
    }

    private companion object {
        const val SIZE = 4_000
        const val BIG = 15_000
        const val STAGED_BY = 2_600 // well above the 2,000 rows of a staging batch: the readers in front of the parser hold some bytes unparsed
        const val STEP = 10L
        const val POLL_MS = 20L
        const val GATE_SECONDS = 20L
    }
}
