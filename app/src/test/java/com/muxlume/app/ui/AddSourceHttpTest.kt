package com.muxlume.app.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.PlainBox
import com.muxlume.app.core.net.Http
import com.muxlume.app.data.SourceInput
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.epg.EpgLinker
import com.muxlume.app.playback.StreamRequests
import com.muxlume.app.playback.StreamResolver
import com.muxlume.app.ui.onboarding.FormState
import com.muxlume.app.ui.onboarding.Mode
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

/**
 * The add and edit form's HTTP choice, followed through what it saves to the request that is made: an https playlist that lists
 * http:// channels, opt-in, reopening, renaming, revoking, and another playlist beside it.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddSourceHttpTest {
    private val server = MockWebServer()
    private lateinit var db: MuxlumeDatabase
    private lateinit var sources: SourceRepository
    private lateinit var resolver: StreamResolver

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuxlumeDatabase::class.java).allowMainThreadQueries().build()
        sources = SourceRepository(db, Http.client(), PlainBox(), context.contentResolver, context.resources, EpgLinker(db))
        resolver = StreamResolver(db, sources)
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private val https = FormState(Mode.LINK, "Playlist", url = "https://playlist.example/list.m3u")

    private suspend fun saved(form: FormState, existing: Long? = null): Long = sources.save(form.toInput(), existing)

    /** A channel of the playlist whose stream is plain http, then the request the player makes for it. */
    private suspend fun channel(source: Long) {
        db.items().insertAll(listOf(ItemEntity(sourceId = source, kind = ItemKind.LIVE, stableKey = "ch", name = "ch", groupTitle = "G", logo = null, url = server.url("/ch.ts").toString(), position = 0)))
    }

    private fun play(source: Long): Result<Unit> = runBlocking {
        val stream = resolver.resolve(source, "ch", null, unlocked = true)!!
        val requests = StreamRequests(Http.client()).also { it.use(stream.headers, stream.policy) }
        runCatching {
            val data = requests.factory.createDataSource()
            try { data.open(DataSpec(Uri.parse(stream.url))) } finally { data.close() }
            Unit
        }
    }

    @Test
    fun `an https playlist can allow http channels, the choice is off until ticked and not required`() {
        assertThat(https.canSubmit).isTrue() // the playlist itself is https: nothing to acknowledge
        assertThat(https.usesHttp).isFalse()
        assertThat((https.toInput() as SourceInput.M3uUrl).allowHttp).isFalse()
        assertThat((https.copy(httpAcknowledged = true).toInput() as SourceInput.M3uUrl).allowHttp).isTrue()
        val file = FormState(Mode.FILE, "F", fileUri = Uri.parse("content://x/y.m3u"), fileName = "y.m3u")
        assertThat((file.toInput() as SourceInput.M3uFile).allowHttp).isFalse()
        assertThat((file.copy(httpAcknowledged = true).toInput() as SourceInput.M3uFile).allowHttp).isTrue()
    }

    @Test
    fun `an http playlist or guide address still needs the acknowledgement`() {
        val http = https.copy(url = "http://playlist.example/list.m3u")
        assertThat(http.usesHttp).isTrue()
        assertThat(http.canSubmit).isFalse()
        assertThat(http.copy(httpAcknowledged = true).canSubmit).isTrue()
        val guide = https.copy(guideUrl = "http://guide.example/g.xml")
        assertThat(guide.usesHttp).isTrue()
        assertThat(guide.canSubmit).isFalse()
        val xtream = FormState(Mode.XTREAM, "X", server = "http://p.example:8080", username = "u", password = "p")
        assertThat(xtream.canSubmit).isFalse()
        assertThat((xtream.copy(httpAcknowledged = true).toInput() as SourceInput.Xtream).allowHttp).isTrue()
        val secure = xtream.copy(server = "https://p.example")
        assertThat(secure.canSubmit).isTrue() // an https account can still allow http artwork, off until ticked
        assertThat((secure.toInput() as SourceInput.Xtream).allowHttp).isFalse()
        assertThat((secure.copy(httpAcknowledged = true).toInput() as SourceInput.Xtream).allowHttp).isTrue()
    }

    @Test
    fun `an http channel of an https playlist is blocked by default and works after the opt-in`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(100))).build())
        val id = saved(https)
        channel(id)
        val blocked = play(id).exceptionOrNull()
        assertThat(blocked).isInstanceOf(HttpDataSource.HttpDataSourceException::class.java)
        assertThat(server.requestCount).isEqualTo(0)

        saved(https.copy(httpAcknowledged = true), id)
        assertThat(play(id).isSuccess).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `the choice survives reopening the edit screen and renaming, and revoking it blocks the next request`(): Unit = runBlocking {
        repeat(2) { server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(100))).build()) }
        val id = saved(https.copy(httpAcknowledged = true))
        channel(id)

        val reopened = FormState.of(sources.get(id)!!, sources.secretOf(sources.get(id)!!))
        assertThat(reopened.httpAcknowledged).isTrue()
        assertThat(reopened.url).isEqualTo("https://playlist.example/list.m3u")
        saved(reopened.copy(name = "Renamed"), id)
        assertThat(sources.get(id)!!.name).isEqualTo("Renamed")
        assertThat(sources.get(id)!!.allowHttp).isTrue()
        assertThat(play(id).isSuccess).isTrue()

        val again = FormState.of(sources.get(id)!!, sources.secretOf(sources.get(id)!!))
        saved(again.copy(httpAcknowledged = false), id) // revoked
        assertThat(sources.get(id)!!.allowHttp).isFalse()
        assertThat(play(id).isFailure).isTrue()
        assertThat(server.requestCount).isEqualTo(1) // nothing new was sent after the revocation
    }

    @Test
    fun `one playlist allowing http does not let another one's channel through`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(100))).build())
        val allowed = saved(https.copy(httpAcknowledged = true))
        val strict = saved(https.copy(name = "Other", url = "https://other.example/list.m3u"))
        channel(allowed)
        channel(strict)
        assertThat(play(strict).isFailure).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(play(allowed).isSuccess).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(play(strict).isFailure).isTrue() // and again after the other one played
        assertThat(server.requestCount).isEqualTo(1)
    }
}
