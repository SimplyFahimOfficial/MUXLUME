package com.muxlume.app.data.epg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.PlainBox
import com.muxlume.app.Xmltv
import com.muxlume.app.Xmltv.NOW
import com.muxlume.app.core.net.Http
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.EpgDao
import com.muxlume.app.data.db.MuxlumeDatabase
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class EpgRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var sources: SourceRepository
    private lateinit var epg: EpgRepository
    private val hour = 3600L

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        val linker = EpgLinker(db)
        sources = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver, context.resources, linker)
        epg = EpgRepository(db, sources, linker, Http.client(), context.resources) { NOW }
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun serve(body: String, gzip: Boolean = false) {
        val bytes = if (gzip) ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(body.toByteArray()) } }.toByteArray() else body.toByteArray()
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
    }

    private fun playlist(vararg lines: String) = "#EXTM3U\n" + lines.joinToString("") { "$it\nhttps://cdn.example/${it.hashCode()}.m3u8\n" }

    private val guide = Xmltv.feed(
        Xmltv.channel("bbc1.uk", "BBC One HD"),
        Xmltv.channel("news24", "News 24"),
        Xmltv.programme("bbc1.uk", NOW - hour, NOW, "Earlier"),
        Xmltv.programme("bbc1.uk", NOW, NOW + hour, "Now Showing", desc = "Live", category = "Drama"),
        Xmltv.programme("bbc1.uk", NOW + hour, NOW + 2 * hour, "Up Next"),
        Xmltv.programme("bbc1.uk", NOW + hour, NOW + 2 * hour, "Duplicate start"), // same channel and start: stored once
        Xmltv.programme("news24", NOW, NOW + hour, "Headlines"),
    )

    /** A source with three live channels: one matched by tvg-id, one by name, one unknown. */
    private suspend fun addSource(guideUrl: String? = server.url("/guide.xml").toString()): Long {
        val id = sources.save(SourceInput.M3uUrl("S", server.url("/list.m3u").toString(), allowHttp = true, guideUrl = guideUrl))
        serve(playlist("#EXTINF:-1 tvg-id=\"BBC1.UK\",BBC One", "#EXTINF:-1,News 24 FHD", "#EXTINF:-1,Local Cable"))
        sources.refresh(id)
        return id
    }

    private suspend fun key(id: Long, name: String) = db.openHelper.readableDatabase
        .query("SELECT stableKey FROM items WHERE sourceId = ? AND name = ?", arrayOf<Any>(id, name)).use { it.moveToFirst(); it.getString(0) }

    @Test
    fun `refresh stores programmes, links channels by id and by name, and answers now and next`(): Unit = runBlocking {
        val id = addSource()
        epg.setEnabled(id, true)
        serve(guide, gzip = true)
        assertThat(epg.refresh(id)).isEqualTo(5) // "Duplicate start" is kept by the parser, dropped by the unique key

        val bbc = epg.nowNext(id, key(id, "BBC One"))
        assertThat(bbc.now?.title).isEqualTo("Now Showing")
        assertThat(bbc.now?.description).isEqualTo("Live")
        assertThat(bbc.now?.category).isEqualTo("Drama")
        assertThat(bbc.next?.title).isEqualTo("Up Next")
        assertThat(epg.nowNext(id, key(id, "News 24 FHD")).now?.title).isEqualTo("Headlines")
        assertThat(epg.nowNext(id, key(id, "Local Cable"))).isEqualTo(NowNext(null, null))

        val day = epg.programs(id, key(id, "BBC One"), NOW - 2 * hour, NOW + 3 * hour)
        assertThat(day.map { it.title }).containsExactly("Earlier", "Now Showing", "Up Next").inOrder()
        val source = sources.get(id)!!
        assertThat(source.epgPrograms).isEqualTo(5)
        assertThat(source.epgError).isNull()
        assertThat(source.epgUrl).isNull() // links live in the sealed secret
    }

    @Test
    fun `programme search finds upcoming titles on visible channels and takes wildcards literally`(): Unit = runBlocking {
        val id = addSource()
        serve(
            Xmltv.feed(
                Xmltv.channel("bbc1.uk", "BBC One HD"),
                Xmltv.channel("news24", "News 24"),
                Xmltv.programme("bbc1.uk", NOW + hour, NOW + 2 * hour, "100% Wolf"),
                Xmltv.programme("news24", NOW, NOW + hour, "Wolfpack Live"),
                Xmltv.programme("news24", NOW + hour, NOW + 2 * hour, "Snow_White"),
                Xmltv.programme("news24", NOW - 3 * hour, NOW - 2 * hour, "Old Wolf"), // already over
            ),
        )
        epg.refresh(id)
        fun search(q: String) = runBlocking { db.epg().searchPrograms(id, EpgDao.likeEscape(q), NOW, 50, true) }

        val wolves = search("wolf")
        assertThat(wolves.map { it.program.title }).containsExactly("Wolfpack Live", "100% Wolf").inOrder() // by start time; the finished one is gone
        assertThat(wolves.map { it.channelName }).containsExactly("News 24 FHD", "BBC One").inOrder()
        assertThat(wolves.last().stableKey).isEqualTo(key(id, "BBC One"))
        assertThat(search("%").map { it.program.title }).containsExactly("100% Wolf") // not "anything"
        assertThat(search("_").map { it.program.title }).containsExactly("Snow_White")
        assertThat(search("w_lf")).isEmpty()
        assertThat(search("nothing like it")).isEmpty()

        db.channelPrefs().edit(id, key(id, "BBC One")) { it.copy(hidden = true) }
        assertThat(search("wolf").map { it.program.title }).containsExactly("Wolfpack Live") // hidden channel: not offered
        db.channelPrefs().edit(id, key(id, "News 24 FHD")) { it.copy(customName = "News Plus") }
        assertThat(search("wolf").map { it.channelName }).containsExactly("News Plus")
    }

    @Test
    fun `before a programme starts it is next, not now, and after it ends it is gone`(): Unit = runBlocking {
        val id = addSource()
        serve(guide)
        epg.refresh(id)
        val k = key(id, "BBC One")
        assertThat(epg.nowNext(id, k, NOW - 30 * 60).let { it.now?.title to it.next?.title }).isEqualTo("Earlier" to "Now Showing")
        assertThat(epg.nowNext(id, k, NOW + 3 * hour)).isEqualTo(NowNext(null, null))
    }

    @Test
    fun `a failed refresh keeps the old guide, records the error and leaves nothing staged`(): Unit = runBlocking {
        val id = addSource()
        serve(guide)
        epg.refresh(id)

        serve(guide.dropLast(40)) // truncated
        assertThrows(EpgFormatException::class.java) { runBlocking { epg.refresh(id) } }
        server.enqueue(MockResponse.Builder().code(500).build())
        assertThrows(Exception::class.java) { runBlocking { epg.refresh(id) } }

        assertThat(epg.nowNext(id, key(id, "BBC One")).now?.title).isEqualTo("Now Showing")
        assertThat(sources.get(id)!!.epgError).isNotNull()
        val staged = db.openHelper.readableDatabase.query("SELECT (SELECT COUNT(*) FROM programs WHERE pending = 1) + (SELECT COUNT(*) FROM epg_channels WHERE pending = 1)").use { it.moveToFirst(); it.getInt(0) }
        assertThat(staged).isEqualTo(0)
    }

    @Test
    fun `an entity declaration is rejected and nothing is fetched for it`(): Unit = runBlocking {
        val id = addSource()
        val evil = Xmltv.feed(
            Xmltv.programme("bbc1.uk", NOW, NOW + hour, "&xxe;"),
            doctype = """<!DOCTYPE tv [<!ENTITY xxe SYSTEM "${server.url("/secret")}">]>""",
        )
        serve(evil)
        val before = server.requestCount
        assertThrows(EpgFormatException::class.java) { runBlocking { epg.refresh(id) } }
        assertThat(server.requestCount).isEqualTo(before + 1) // the guide itself, never /secret
    }

    @Test
    fun `links the user chose survive refreshes, automatic ones are recomputed`(): Unit = runBlocking {
        val id = addSource()
        serve(guide)
        epg.refresh(id)
        val local = key(id, "Local Cable")
        epg.setManualLink(id, local, "news24")
        assertThat(epg.nowNext(id, local).now?.title).isEqualTo("Headlines")

        // The guide drops "News 24"'s programmes next time: the automatic link has nothing to show, the manual one stays.
        serve(Xmltv.feed(Xmltv.channel("bbc1.uk", "BBC One HD"), Xmltv.channel("news24", "News 24"), Xmltv.programme("news24", NOW, NOW + hour, "Headlines v2")))
        epg.refresh(id)
        assertThat(epg.nowNext(id, local).now?.title).isEqualTo("Headlines v2")
        assertThat(db.epg().link(id, local)!!.manual).isTrue()

        epg.removeLink(id, local)
        assertThat(epg.nowNext(id, local)).isEqualTo(NowNext(null, null))
    }

    @Test
    fun `new playlist channels get linked after a playlist refresh`(): Unit = runBlocking {
        val id = addSource()
        epg.setEnabled(id, true)
        serve(guide)
        epg.refresh(id)

        serve(playlist("#EXTINF:-1 tvg-id=\"bbc1.uk\",BBC One (backup)", "#EXTINF:-1,News 24"))
        sources.refresh(id)
        assertThat(epg.nowNext(id, key(id, "BBC One (backup)")).now?.title).isEqualTo("Now Showing")
        assertThat(epg.nowNext(id, key(id, "News 24")).now?.title).isEqualTo("Headlines")
    }

    @Test
    fun `turning the guide off deletes it, and the playlist's own guide link is found`(): Unit = runBlocking {
        val id = sources.save(SourceInput.M3uUrl("S", server.url("/list.m3u").toString(), allowHttp = true))
        serve("#EXTM3U url-tvg=\"${server.url("/announced.xml")}\"\n#EXTINF:-1,A\nhttps://cdn.example/a.m3u8\n")
        val result = sources.refresh(id)
        assertThat(result.guideAvailable).isTrue()
        assertThat(sources.guideUrl(sources.get(id)!!)!!.encodedPath).isEqualTo("/announced.xml")

        serve(Xmltv.feed(Xmltv.channel("a", "A"), Xmltv.programme("a", NOW, NOW + hour, "Show")))
        epg.setEnabled(id, true)
        epg.refresh(id)
        assertThat(epg.nowNext(id, key(id, "A")).now?.title).isEqualTo("Show")

        epg.setEnabled(id, false)
        assertThat(epg.nowNext(id, key(id, "A"))).isEqualTo(NowNext(null, null))
        val s = sources.get(id)!!
        assertThat(s.guideEnabled).isFalse()
        assertThat(s.epgPrograms).isEqualTo(0)
        assertThat(s.epgRefreshedAt).isNull()
    }

    @Test
    fun `a source without a guide link says so`(): Unit = runBlocking {
        val id = addSource(guideUrl = null)
        val e = assertThrows(java.io.IOException::class.java) { runBlocking { epg.refresh(id) } }
        assertThat(e.message).contains("no TV guide link")
    }

    @Test
    fun `reminders are scheduled five minutes ahead, never for a programme that has started`(): Unit = runBlocking {
        val id = addSource()
        serve(guide)
        epg.refresh(id)
        val k = key(id, "BBC One")
        val next = epg.nowNext(id, k).next!!

        val r = epg.addReminder(id, k, "BBC One", next)!!
        assertThat(r.notifyAt).isEqualTo(next.start - 5 * 60)
        assertThat(epg.addReminder(id, k, "BBC One", next)!!.id).isEqualTo(r.id) // again: the same reminder, not a duplicate
        assertThat(db.reminders().remindersAfter(0)).hasSize(1)

        assertThat(epg.addReminder(id, k, "BBC One", epg.nowNext(id, k).now!!)).isNull()
        epg.removeReminder(r.id)
        assertThat(db.reminders().remindersAfter(0)).isEmpty()
    }

    @Test
    fun `reminder timing`() {
        assertThat(ReminderPlanner.notifyAt(start = 1000, now = 100)).isEqualTo(700)
        assertThat(ReminderPlanner.notifyAt(start = 1000, now = 800)).isEqualTo(800) // less than 5 minutes left: now
        assertThat(ReminderPlanner.notifyAt(start = 1000, now = 1000)).isNull()
        assertThat(ReminderPlanner.notifyAt(start = 1000, now = 2000)).isNull()
    }
}
