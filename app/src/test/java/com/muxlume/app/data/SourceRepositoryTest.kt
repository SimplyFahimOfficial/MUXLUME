package com.muxlume.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.net.HttpStatusException
import com.muxlume.app.PlainBox
import com.muxlume.app.data.epg.EpgLinker
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.ItemDao
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.KeyAliasEntity
import com.muxlume.app.data.db.MuxlumeDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Above one staging batch (1000 rows), so an overflow has staged rows to discard. */
private const val LIMIT = 1_500

@RunWith(AndroidJUnit4::class)
// Plain Application: MuxlumeApp would start its own database and observers during the test.
@Config(sdk = [35], application = android.app.Application::class)
class SourceRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var repo: SourceRepository

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        repo = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver, context.resources, EpgLinker(db))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun playlist(vararg channels: String) = buildString {
        append("#EXTM3U\n")
        channels.forEach { append("#EXTINF:-1 group-title=\"G\",$it\nhttps://cdn.example/$it.m3u8\n") }
    }

    private fun serve(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    // MockWebServer is plain HTTP, so test sources allow HTTP unless a test says otherwise.
    private suspend fun addM3u(allowHttp: Boolean = true): Long =
        repo.save(SourceInput.M3uUrl("Test", server.url("/list.m3u").toString(), allowHttp))

    /** Visible live channel names of a source, in provider order. */
    private fun names(id: Long): List<String> =
        db.openHelper.readableDatabase.query("SELECT name FROM items WHERE sourceId = ? AND kind = 'LIVE' AND pending = 0 ORDER BY position", arrayOf<Any>(id))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun entry(name: String, url: String, group: String = "G", tvg: String? = null) =
        "#EXTINF:-1${tvg?.let { " tvg-id=\"$it\"" }.orEmpty()} group-title=\"$group\",$name\n$url\n"

    private fun m3u(vararg entries: String) = "#EXTM3U\n" + entries.joinToString("")

    /** Holds the answer of one path until [open] is released, and records every path asked for. */
    private inner class Gate(private val held: String, private val routes: Map<String, MockResponse>) {
        val reached = CountDownLatch(1)
        val open = CountDownLatch(1)
        val requested = CopyOnWriteArrayList<String>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requested += path
                    if (path == held) {
                        reached.countDown()
                        open.await(10, TimeUnit.SECONDS)
                    }
                    return routes[path] ?: MockResponse.Builder().code(404).build()
                }
            }
        }

        suspend fun awaitReached() = withContext(Dispatchers.IO) { assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue() }
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun key(id: Long, name: String): String =
        db.openHelper.readableDatabase.query("SELECT stableKey FROM items WHERE sourceId = ? AND name = ? AND pending = 0", arrayOf<Any>(id, name))
            .use { c -> c.moveToFirst(); c.getString(0) }

    @Test
    fun `import stores channels, merges duplicates and never stores the full url as label`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b", "a") + "#EXTINF:-1,Movie\nhttps://cdn.example/movie/u/p/9.mp4\n")
        val result = repo.refresh(id)
        assertThat(result.live).isEqualTo(2)
        assertThat(result.movies).isEqualTo(1)
        assertThat(result.duplicates).isEqualTo(1)
        val source = repo.get(id)!!
        assertThat(source.liveCount).isEqualTo(2)
        assertThat(source.label).doesNotContain("list.m3u")
        assertThat(source.secret).doesNotContain("cdn.example")
        assertThat(names(id)).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `favorites survive a refresh, failed refresh keeps previous items`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b"))
        repo.refresh(id)
        db.favorites().add(FavoriteEntity(id, key(id, "a"), 1))

        serve(playlist("b", "a", "c"))
        repo.refresh(id)
        assertThat(db.items().favorites(ItemKind.LIVE, id, true).first().map { it.name }).containsExactly("a")

        serve("", code = 500)
        assertThrows(HttpStatusException::class.java) { runBlocking { repo.refresh(id) } }
        assertThat(names(id)).hasSize(3)
        assertThat(repo.get(id)!!.lastError).contains("500")
    }

    @Test
    fun `an empty or HTML body with HTTP 200 keeps the previous channels and records an error`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b"))
        repo.refresh(id)
        val refreshedAt = repo.get(id)!!.refreshedAt

        for (body in listOf("<html><body><h1>Service unavailable</h1></body></html>", "")) {
            serve(body)
            assertThrows(IOException::class.java) { runBlocking { repo.refresh(id) } }
            assertThat(names(id)).containsExactly("a", "b").inOrder()
            val source = repo.get(id)!!
            assertThat(source.refreshedAt).isEqualTo(refreshedAt)
            assertThat(source.lastError).isNotEmpty()
        }
    }

    @Test
    fun `a body with no header and no entries is an error for a source with channels, but a first import just records it`(): Unit = runBlocking {
        val id = addM3u()
        serve("# a comment, no header\n")
        repo.refresh(id) // nothing to protect yet
        assertThat(names(id)).isEmpty()
        assertThat(repo.get(id)!!.lastError).isNull()

        serve(playlist("a", "b"))
        repo.refresh(id)
        serve("# a comment, no header\n")
        assertThrows(IOException::class.java) { runBlocking { repo.refresh(id) } }
        assertThat(names(id)).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a smaller valid headerless playlist still refreshes and clears the error`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b"))
        repo.refresh(id)
        serve("")
        assertThrows(IOException::class.java) { runBlocking { repo.refresh(id) } }
        assertThat(repo.get(id)!!.lastError).isNotNull()

        serve("https://cdn.example/only.m3u8\n")
        repo.refresh(id)
        assertThat(names(id)).hasSize(1)
        assertThat(repo.get(id)!!.lastError).isNull()
        assertThat(repo.get(id)!!.liveCount).isEqualTo(1)
    }

    @Test
    fun `a valid playlist emptied by the provider removes the previous channels`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b"))
        repo.refresh(id)
        serve("")
        assertThrows(IOException::class.java) { runBlocking { repo.refresh(id) } }

        serve(playlist())
        repo.refresh(id)
        assertThat(names(id)).isEmpty()
        val source = repo.get(id)!!
        assertThat(source.lastError).isNull()
        assertThat(source.liveCount).isEqualTo(0)
        assertThat(source.refreshedAt).isNotNull()
    }

    @Test
    fun `a playlist whose every entry is unplayable or cut off keeps the previous channels`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a", "b"))
        repo.refresh(id)

        for (body in listOf("#EXTM3U\n#EXTINF:-1,Local\nfile:///sdcard/x.ts\n", "#EXTM3U\n#EXTINF:-1 group-title=\"News\",News HD\nudp://@239.0.0.1:5000\n", "#EXTM3U\n#EXTINF:-1,Cut off\n")) {
            serve(body)
            assertThrows(IOException::class.java) { runBlocking { repo.refresh(id) } }
            assertThat(names(id)).containsExactly("a", "b").inOrder()
            assertThat(repo.get(id)!!.lastError).isNotEmpty()
        }
    }

    @Test
    fun `cancelled import rolls back and leaves no staged rows`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("old"))
        repo.refresh(id)
        serve(playlist(*Array(5_000) { "n$it" }))
        assertThrows(CancellationException::class.java) {
            runBlocking { repo.refresh(id) { n -> if (n >= 1_000) throw CancellationException("user cancelled") } }
        }
        assertThat(names(id)).containsExactly("old")
        val staged = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items WHERE pending = 1").use { it.moveToFirst(); it.getInt(0) }
        assertThat(staged).isEqualTo(0)
    }

    @Test
    fun `a running import is invisible and doesn't block other writes`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("old"))
        repo.refresh(id)
        serve(playlist(*Array(3_000) { "n$it" }))
        var checked = false
        repo.refresh(id) { n ->
            if (n in 1_000..1_999 && !checked) {
                checked = true
                assertThat(names(id)).containsExactly("old") // staged rows aren't shown yet
                // Another coroutine can write while the import runs (a long write transaction would block this).
                runBlocking(Dispatchers.IO) { withTimeout(5_000) { db.favorites().add(FavoriteEntity(id, key(id, "old"), 1)) } }
                runBlocking(Dispatchers.IO) { withTimeout(5_000) { repo.rename(id, "Renamed") } }
            }
        }
        assertThat(checked).isTrue()
        assertThat(names(id)).hasSize(3_000)
        assertThat(repo.get(id)!!.name).isEqualTo("Renamed") // the final update doesn't undo a rename made meanwhile
    }

    @Test
    fun `one source's HTTP opt-in doesn't apply to another source`(): Unit = runBlocking {
        addM3u(allowHttp = true)
        val strict = addM3u(allowHttp = false)
        assertThrows(CleartextBlockedException::class.java) { runBlocking { repo.refresh(strict) } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `deleting a source removes its items and favorites`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("a"))
        repo.refresh(id)
        db.favorites().add(FavoriteEntity(id, key(id, "a"), 1))
        repo.delete(id)
        assertThat(db.items().favorites(ItemKind.LIVE, null, true).first()).isEmpty()
        assertThat(db.items().search(ItemDao.ftsQuery("a")!!, "", 10, true).first()).isEmpty()
    }

    @Test
    fun `full-text search finds word prefixes and ignores staged rows`(): Unit = runBlocking {
        val id = addM3u()
        serve(playlist("BBC One HD", "Sky News", "Cartoon Network"))
        repo.refresh(id)
        fun find(q: String) = runBlocking { db.items().search(ItemDao.ftsQuery(q)!!, "", 10, true).first().map { it.name } }
        assertThat(find("bbc on")).containsExactly("BBC One HD")
        assertThat(find("NEW")).containsExactly("Sky News")
        assertThat(find("net")).containsExactly("Cartoon Network")
        assertThat(find("xyz")).isEmpty()

        serve(playlist(*Array(2_000) { "Staged $it" }))
        repo.refresh(id) { n -> if (n == 1_000) assertThat(find("staged")).isEmpty() }
        assertThat(find("sky")).isEmpty() // replaced by the new list
        assertThat(find("staged 1999")).containsExactly("Staged 1999")
    }

    @Test
    fun `xtream import uses stream ids as stable keys and keeps credentials out of items`(): Unit = runBlocking {
        val id = repo.save(SourceInput.Xtream("X", server.url("/").toString(), "u", "secret-pass", allowHttp = true))
        serve("""{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]}}""")
        serve("""[{"category_id":"1","category_name":"News"}]""")
        serve("""[{"stream_id":10,"name":"N1","category_id":"1","stream_icon":"https://img/x.png","epg_channel_id":"n1"}]""")
        serve("[]")
        serve("""[{"stream_id":"20","name":"M","container_extension":"mkv","added":"100"}]""")
        serve("[]")
        serve("""[{"series_id":30,"name":"S","cover":"https://img/s.png"}]""")
        val r = repo.refresh(id)
        assertThat(listOf(r.live, r.movies, r.series)).containsExactly(1, 1, 1).inOrder()
        val live = db.items().byKey(id, "live:10", true)!!
        assertThat(live.groupTitle).isEqualTo("News")
        assertThat(live.url).isNull()
        assertThat(repo.get(id)!!.liveExtension).isEqualTo("ts")
        assertThat(db.items().recentlyAdded(ItemKind.MOVIE, 5, true).first().single().extension).isEqualTo("mkv")
    }

    @Test
    fun `a link saved while another refresh runs publishes the new link's channels`(): Unit = runBlocking {
        val id = repo.save(SourceInput.M3uUrl("T", server.url("/a.m3u").toString(), true))
        val other = repo.save(SourceInput.M3uUrl("O", server.url("/o.m3u").toString(), true))
        val gate = Gate("/a.m3u", mapOf("/a.m3u" to ok(playlist("a1", "a2")), "/b.m3u" to ok(playlist("b1", "b2", "b3")), "/o.m3u" to ok(playlist("o1"))))

        val running = async(Dispatchers.IO) { repo.refresh(id) }
        gate.awaitReached()
        withTimeout(5_000) { repo.refresh(other) } // another source isn't held back by the paused one
        assertThat(names(other)).containsExactly("o1")

        repo.save(SourceInput.M3uUrl("T", server.url("/b.m3u").toString(), true), id)
        val edited = async(Dispatchers.IO) { repo.refresh(id) }
        gate.open.countDown()
        running.await()
        edited.await()

        assertThat(gate.requested).contains("/b.m3u")
        assertThat(repo.secretOf(repo.get(id)!!).url).endsWith("/b.m3u")
        assertThat(names(id)).containsExactly("b1", "b2", "b3").inOrder()
        assertThat(repo.get(id)!!.liveCount).isEqualTo(3)
    }

    @Test
    fun `a failed refresh of a link saved during another refresh keeps the last working channels`(): Unit = runBlocking {
        val id = repo.save(SourceInput.M3uUrl("T", server.url("/a.m3u").toString(), true))
        val gate = Gate("/a.m3u", mapOf("/a.m3u" to ok(playlist("a1")), "/b.m3u" to MockResponse.Builder().code(500).build()))
        gate.open.countDown()
        repo.refresh(id)
        val refreshedAt = repo.get(id)!!.refreshedAt
        assertThat(names(id)).containsExactly("a1")

        val slowGate = Gate("/a.m3u", mapOf("/a.m3u" to ok(playlist("a-new")), "/b.m3u" to MockResponse.Builder().code(500).build()))
        val running = async(Dispatchers.IO) { repo.refresh(id) }
        slowGate.awaitReached()
        repo.save(SourceInput.M3uUrl("T", server.url("/b.m3u").toString(), true), id)
        val edited = async(Dispatchers.IO) { runCatching { repo.refresh(id) } }
        slowGate.open.countDown()
        running.await()

        assertThat(edited.await().exceptionOrNull()).isInstanceOf(HttpStatusException::class.java)
        assertThat(names(id)).containsExactly("a1")
        assertThat(repo.get(id)!!.refreshedAt).isEqualTo(refreshedAt)
        assertThat(repo.get(id)!!.lastError).contains("500")
    }

    @Test
    fun `two refreshes of an unchanged source download it once`(): Unit = runBlocking {
        val id = addM3u()
        val gate = Gate("/list.m3u", mapOf("/list.m3u" to ok(playlist("a"))))
        val first = async(Dispatchers.IO) { repo.refresh(id) }
        gate.awaitReached()
        val second = async(Dispatchers.IO) { repo.refresh(id) }
        delay(300) // lets the second call queue behind the first
        gate.open.countDown()
        first.await()
        second.await()
        assertThat(gate.requested).containsExactly("/list.m3u")
    }

    /** A repository with a small M3U entry cap. */
    private fun capped(limit: Int): SourceRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SourceRepository(db, Http.client(), PlainBox(), context.contentResolver, context.resources, EpgLinker(db), maxM3uEntries = limit)
    }

    private fun channels(n: Int, prefix: String = "c") = Array(n) { entry("$prefix$it", "https://cdn.example/$prefix$it.m3u8") }

    @Test
    fun `a playlist with exactly the entry limit imports, even with comments and blank lines after the last entry`(): Unit = runBlocking {
        val small = capped(LIMIT)
        val id = addM3u()
        serve(m3u(*channels(LIMIT)) + "\n# trailing\n\n   \n#EXTINF:-1,Dangling\nfile:///not-playable\n")
        val result = small.refresh(id)

        assertThat(result.live).isEqualTo(LIMIT)
        assertThat(names(id)).hasSize(LIMIT)
        val source = repo.get(id)!!
        assertThat(source.lastError).isNull()
        assertThat(source.liveCount).isEqualTo(LIMIT)
    }

    @Test
    fun `one entry over the limit fails, drops the staged rows and keeps the channels, favorites and last success`(): Unit = runBlocking {
        val small = capped(LIMIT)
        val id = addM3u()
        serve(m3u(*channels(3, "old")))
        small.refresh(id)
        val oldKey = key(id, "old0")
        db.favorites().add(FavoriteEntity(id, oldKey, 1))
        val before = repo.get(id)!!

        serve(m3u(*channels(LIMIT + 1, "new")))
        val e = assertThrows(IOException::class.java) { runBlocking { small.refresh(id) } }

        assertThat(e.message).contains("$LIMIT") // says what the limit is
        assertThat(names(id)).containsExactly("old0", "old1", "old2").inOrder()
        assertThat(db.items().favorites(ItemKind.LIVE, id, true).first().map { it.stableKey }).containsExactly(oldKey)
        val after = repo.get(id)!!
        assertThat(after.refreshedAt).isEqualTo(before.refreshedAt)
        assertThat(after.liveCount).isEqualTo(3)
        assertThat(after.lastError).contains("$LIMIT")
        val staged = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items WHERE pending = 1").use { it.moveToFirst(); it.getInt(0) }
        assertThat(staged).isEqualTo(0)

        // The next refresh within the limit works and clears the error.
        serve(m3u(*channels(2, "ok")))
        small.refresh(id)
        assertThat(names(id)).containsExactly("ok0", "ok1").inOrder()
        assertThat(repo.get(id)!!.lastError).isNull()
    }

    @Test
    fun `the limit counts playlist entries, so a duplicate counts, and duplicates are still merged`(): Unit = runBlocking {
        val small = capped(LIMIT)
        val id = addM3u()
        serve(m3u(*channels(LIMIT - 1), entry("c0 again", "https://cdn.example/c0.m3u8"))) // LIMIT entries, one duplicate
        val result = small.refresh(id)
        assertThat(result.duplicates).isEqualTo(1)
        assertThat(names(id)).hasSize(LIMIT - 1)

        serve(m3u(*channels(LIMIT), entry("c0 again", "https://cdn.example/c0.m3u8"))) // LIMIT + 1 entries
        assertThrows(IOException::class.java) { runBlocking { small.refresh(id) } }
        assertThat(names(id)).hasSize(LIMIT - 1)
    }

    @Test
    fun `a valid playlist emptied on purpose is still applied with a small limit`(): Unit = runBlocking {
        val small = capped(LIMIT)
        val id = addM3u()
        serve(m3u(*channels(3)))
        small.refresh(id)
        serve(m3u())
        small.refresh(id)
        assertThat(names(id)).isEmpty()
        assertThat(repo.get(id)!!.lastError).isNull()
    }

    @Test
    fun `an unnamed entry gets a neutral name, keeps its key and its favorite, and no address part is displayed`(): Unit = runBlocking {
        val login = "https://SYNTH-USER:SYNTH-PASS-7731@synth-host.invalid:8443/live/SYNTH-USER/SYNTH-PASS-7731/12.ts?token=SYNTH-TOKEN-4410"
        val list = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",\n$login\n" + // an empty title
            "https://synth-host.invalid/SYNTH-PASS-7731\n" + // no #EXTINF at all
            "#EXTINF:-1 group-title=\"G\",Provider Name\nhttps://synth-host.invalid/named\n"
        val id = addM3u()
        serve(list)
        repo.refresh(id)

        assertThat(names(id)).containsExactly("Channel 1", "Channel 2", "Provider Name").inOrder()
        val key1 = SourceRepository.stableKey("G\n$login") // the key is still made of the group and the address, not of the name
        val key2 = SourceRepository.stableKey("\nhttps://synth-host.invalid/SYNTH-PASS-7731")
        assertThat(keys(id)).containsExactly(key1, key2, SourceRepository.stableKey("G\nhttps://synth-host.invalid/named")).inOrder()
        listOf("SYNTH-USER", "SYNTH-PASS-7731", "SYNTH-TOKEN-4410", "synth-host", "12.ts").forEach { marker ->
            assertThat(db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items WHERE name LIKE ? OR groupTitle LIKE ?", arrayOf<Any>("%$marker%", "%$marker%")).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(0)
            assertThat(db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items_fts WHERE items_fts MATCH ?", arrayOf<Any>("\"$marker\"")).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(0)
        }

        db.favorites().add(FavoriteEntity(id, key1, 1))
        serve(list)
        repo.refresh(id)
        assertThat(keys(id)[0]).isEqualTo(key1) // unchanged by a refresh
        assertThat(db.items().favorites(ItemKind.LIVE, id, true).first().map { it.name }).containsExactly("Channel 1")
    }

    private fun keys(id: Long): List<String> =
        db.openHelper.readableDatabase.query("SELECT stableKey FROM items WHERE sourceId = ? AND pending = 0 ORDER BY position", arrayOf<Any>(id))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private val one = "https://cdn.example/1.m3u8"
    private val two = "https://cdn.example/2.m3u8"

    /** Imports [first], favorites and pins channel "One", then refreshes with [second]. Returns the source and One's key before the refresh. */
    private suspend fun refreshAfterMarking(first: String, second: String): Pair<Long, String> {
        val id = addM3u()
        serve(first)
        repo.refresh(id)
        val old = key(id, "One")
        db.favorites().add(FavoriteEntity(id, old, 1))
        db.channelPrefs().edit(id, old) { it.copy(pinned = true) }
        serve(second)
        repo.refresh(id)
        return id to old
    }

    private suspend fun assertFavoriteOpens(id: Long, oldKey: String, url: String, group: String) {
        val fav = db.items().favorites(ItemKind.LIVE, id, true).first().single()
        assertThat(fav.stableKey).isEqualTo(oldKey)
        assertThat(fav.url).isEqualTo(url)
        assertThat(fav.groupTitle).isEqualTo(group)
        assertThat(db.items().byKey(id, oldKey, true)!!.url).isEqualTo(url)
        assertThat(db.channelPrefs().isPinned(id, oldKey)).isTrue()
    }

    @Test
    fun `a favorite follows a channel whose group was renamed`(): Unit = runBlocking {
        val (id, old) = refreshAfterMarking(
            m3u(entry("One", one, "News", "one.tv"), entry("Two", two, "News", "two.tv")),
            m3u(entry("One", one, "Sport", "one.tv"), entry("Two", two, "News", "two.tv")),
        )
        assertFavoriteOpens(id, old, one, "Sport")
        assertThat(names(id)).containsExactly("One", "Two").inOrder()
    }

    @Test
    fun `a favorite follows a channel whose stream link rotated`(): Unit = runBlocking {
        val rotated = "https://cdn2.example/1.m3u8?t=2"
        val (id, old) = refreshAfterMarking(
            m3u(entry("One", one, "News", "one.tv"), entry("Two", two, "News", "two.tv")),
            m3u(entry("One", rotated, "News", "one.tv"), entry("Two", two, "News", "two.tv")),
        )
        assertFavoriteOpens(id, old, rotated, "News")
    }

    @Test
    fun `channels without a tvg-id or with a shared one get new identities`(): Unit = runBlocking {
        val (plain, plainKey) = refreshAfterMarking(m3u(entry("One", one)), m3u(entry("One", "https://cdn2.example/1.m3u8")))
        assertThat(key(plain, "One")).isNotEqualTo(plainKey)

        // Two variants share a tvg-id, in the old list and in the new one: neither may inherit the favorite.
        val sd = "https://cdn.example/sd.m3u8"
        val (id, old) = refreshAfterMarking(
            m3u(entry("One", one, "G", "dup.tv"), entry("One SD", sd, "G", "dup.tv")),
            m3u(entry("One", "https://cdn2.example/1.m3u8", "G", "dup.tv"), entry("One SD", "https://cdn2.example/sd.m3u8", "G", "dup.tv")),
        )
        val keys = listOf(key(id, "One"), key(id, "One SD"))
        assertThat(keys).containsNoDuplicates()
        assertThat(keys).doesNotContain(old)
        assertThat(db.items().favorites(ItemKind.LIVE, id, true).first()).isEmpty()

        // One variant disappears: the lone remaining one is still ambiguous against the two old ones.
        val (lone, loneOld) = refreshAfterMarking(
            m3u(entry("One", one, "G", "dup.tv"), entry("One SD", sd, "G", "dup.tv")),
            m3u(entry("One", "https://cdn2.example/1.m3u8", "G", "dup.tv")),
        )
        assertThat(key(lone, "One")).isNotEqualTo(loneOld)
    }

    @Test
    fun `a tvg-id shared with another source doesn't move keys between sources`(): Unit = runBlocking {
        val a = addM3u()
        val b = addM3u()
        serve(m3u(entry("One", one, "G", "shared.tv")))
        repo.refresh(a)
        serve(m3u(entry("One", two, "G", "shared.tv")))
        repo.refresh(b)
        val keyA = key(a, "One")
        val keyB = key(b, "One")
        db.favorites().add(FavoriteEntity(a, keyA, 1))
        db.favorites().add(FavoriteEntity(b, keyB, 2))

        serve(m3u(entry("One", "https://cdn2.example/a.m3u8", "G", "shared.tv")))
        repo.refresh(a)
        serve(m3u(entry("One", "https://cdn2.example/b.m3u8", "G", "shared.tv")))
        repo.refresh(b)

        assertThat(key(a, "One")).isEqualTo(keyA)
        assertThat(key(b, "One")).isEqualTo(keyB)
        assertThat(db.items().byKey(a, keyA, true)!!.url).isEqualTo("https://cdn2.example/a.m3u8")
        assertThat(db.items().byKey(b, keyB, true)!!.url).isEqualTo("https://cdn2.example/b.m3u8")
    }

    @Test
    fun `reusing keys works across several batches`(): Unit = runBlocking {
        val id = addM3u()
        serve(m3u(*Array(2_500) { entry("c$it", "https://cdn.example/$it.m3u8", "G", "tv$it") }))
        repo.refresh(id)
        val old = key(id, "c2499")
        serve(m3u(*Array(2_500) { entry("c$it", "https://cdn2.example/$it.m3u8", "G", "tv$it") }))
        repo.refresh(id)
        assertThat(key(id, "c2499")).isEqualTo(old)
        assertThat(db.items().byKey(id, old, true)!!.url).isEqualTo("https://cdn2.example/2499.m3u8")
    }

    @Test
    fun `an xtream account's positive connection limit is kept, and a missing or zero one is not`(): Unit = runBlocking {
        suspend fun importWith(limit: String?): Int? {
            val id = repo.save(SourceInput.Xtream("X", server.url("/").toString(), "u", "p", allowHttp = true))
            serve("""{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]${limit?.let { ""","max_connections":"$it"""" }.orEmpty()}}}""")
            repeat(6) { serve("[]") }
            repo.refresh(id)
            return repo.get(id)!!.maxConnections
        }
        assertThat(importWith("2")).isEqualTo(2)
        assertThat(importWith("0")).isNull()
        assertThat(importWith(null)).isNull()
    }

    @Test
    fun `a channel that kept its key through a rename keeps it when its tvg-id later becomes shared or is dropped`(): Unit = runBlocking {
        val id = addM3u()
        serve(m3u(entry("One", one, "News", "one.tv")))
        repo.refresh(id)
        val old = key(id, "One")
        serve(m3u(entry("One", one, "Sport", "one.tv"))) // group renamed: the key is kept, though it is no longer the hash of group and link
        repo.refresh(id)
        assertThat(key(id, "One")).isEqualTo(old)

        // Unchanged group and link, but another channel now shares the tvg-id: still the same channel.
        serve(m3u(entry("One", one, "Sport", "one.tv"), entry("One HD", two, "Sport", "one.tv")))
        repo.refresh(id)
        assertThat(key(id, "One")).isEqualTo(old)
        assertThat(key(id, "One HD")).isNotEqualTo(old)

        // And when its own tvg-id is dropped.
        serve(m3u(entry("One", one, "Sport"), entry("One HD", two, "Sport", "one.tv")))
        repo.refresh(id)
        assertThat(key(id, "One")).isEqualTo(old)
    }

    @Test
    fun `key reuse can be done in steps`(): Unit = runBlocking {
        val id = addM3u()
        serve(m3u(*Array(2_500) { entry("c$it", "https://cdn.example/$it.m3u8", "G", "tv$it") }))
        repo.refresh(id)
        val old = key(id, "c2499")
        db.items().insertAll(
            List(2_500) { i ->
                ItemEntity(sourceId = id, kind = ItemKind.LIVE, stableKey = "new$i", name = "c$i", groupTitle = "G", logo = null, url = "https://cdn2.example/$i.m3u8", tvgId = "tv$i", position = i, pending = true)
            },
        )
        assertThat(db.items().hasUnmatchedStaged(id)).isTrue()
        assertThat(listOf(1_000, 1_000, 1_000).map { db.items().reuseKeysByTvgId(id, it) }).containsExactly(1_000, 1_000, 500).inOrder()
        assertThat(db.items().hasUnmatchedStaged(id)).isFalse()
        db.items().commitPending(id)
        assertThat(key(id, "c2499")).isEqualTo(old)
    }

    @Test
    fun `a renamed channel gets its old identity back after the playlist was emptied and restored`(): Unit = runBlocking {
        val id = addM3u()
        serve(m3u(entry("One", one, "News", "one.tv")))
        repo.refresh(id)
        val old = key(id, "One")
        db.favorites().add(FavoriteEntity(id, old, 1))
        serve(m3u(entry("One", one, "Sport", "one.tv"))) // group renamed: the key is kept and remembered
        repo.refresh(id)
        serve("#EXTM3U\n") // the provider empties the playlist on purpose
        repo.refresh(id)
        assertThat(names(id)).isEmpty()

        serve(m3u(entry("One", one, "Sport", "one.tv"))) // and brings it back: no earlier row is left to match against
        repo.refresh(id)
        assertThat(key(id, "One")).isEqualTo(old)
        assertThat(db.items().favorites(ItemKind.LIVE, id, true).first().map { it.name }).containsExactly("One")
    }

    @Test
    fun `two staged channels with remembered keys for one earlier key share it one at a time`(): Unit = runBlocking {
        val id = addM3u()
        db.items().insertAll(listOf(ItemEntity(sourceId = id, kind = ItemKind.LIVE, stableKey = "kept", name = "Old", groupTitle = "G", logo = null, url = "u", position = 0)))
        db.keyAliases().add(KeyAliasEntity(id, "h2", "kept"))
        db.keyAliases().add(KeyAliasEntity(id, "h3", "kept"))
        db.items().insertAll(listOf("h2", "h3").mapIndexed { i, k -> ItemEntity(sourceId = id, kind = ItemKind.LIVE, stableKey = k, name = "N$i", groupTitle = "G", logo = null, url = "u$i", position = i, pending = true) })
        // "kept" is an earlier row's key and a staged row may take it: the old row is replaced at commit.
        assertThat(db.items().reuseKeysByAlias(id, 10)).isEqualTo(1)
        assertThat(db.items().reuseKeysByAlias(id, 10)).isEqualTo(0) // the other one is left under its own key
        db.items().commitPending(id)
        assertThat(names(id)).containsExactly("N0", "N1").inOrder()
        assertThat(key(id, "N0")).isEqualTo("kept")
        assertThat(key(id, "N1")).isEqualTo("h3")
    }
}
