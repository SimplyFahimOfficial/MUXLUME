package com.muxlume.app.data.xtream

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.core.net.CleartextGate
import com.muxlume.app.core.net.Http
import com.muxlume.app.core.net.HttpStatusException
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class XtreamApiTest {
    private val server = MockWebServer()
    private lateinit var api: XtreamApi

    @Before
    fun setUp() {
        CleartextGate.allowed = true // MockWebServer is plain HTTP on localhost
        server.start()
        api = XtreamApi(Http.client(), server.url("/"), "user name", "p@ss/word")
    }

    @After
    fun tearDown() {
        server.close()
        CleartextGate.allowed = false
    }

    private fun enqueue(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `active account with formats and numbers as strings`() {
        enqueue("""{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{}}""")
        val acc = api.account()
        assertThat(acc.expiresAtEpochSec).isEqualTo(1893456000L)
        assertThat(acc.maxConnections).isEqualTo(2)
        assertThat(acc.liveExtension).isEqualTo("m3u8")
        val req = server.takeRequest()
        assertThat(req.url.queryParameter("username")).isEqualTo("user name")
        assertThat(req.url.queryParameter("password")).isEqualTo("p@ss/word")
    }

    @Test
    fun `invalid credentials, expired and inactive accounts`() {
        enqueue("""{"user_info":{"auth":0}}""")
        assertThat(assertThrows(XtreamAuthException::class.java) { api.account() }.reason).isEqualTo(XtreamAuthException.Reason.INVALID_CREDENTIALS)
        enqueue("""{"user_info":{"auth":"1","status":"Expired"}}""")
        assertThat(assertThrows(XtreamAuthException::class.java) { api.account() }.reason).isEqualTo(XtreamAuthException.Reason.EXPIRED)
        enqueue("""{"user_info":{"auth":1,"status":"Banned"}}""")
        assertThat(assertThrows(XtreamAuthException::class.java) { api.account() }.reason).isEqualTo(XtreamAuthException.Reason.INACTIVE)
        enqueue("", code = 403)
        assertThat(assertThrows(XtreamAuthException::class.java) { api.account() }.reason).isEqualTo(XtreamAuthException.Reason.INVALID_CREDENTIALS)
    }

    @Test
    fun `unsupported responses`() {
        enqueue("<html>not xtream</html>")
        assertThrows(XtreamFormatException::class.java) { api.account() }
        enqueue("""{"something":"else"}""")
        assertThrows(XtreamFormatException::class.java) { api.account() }
        enqueue("", code = 502)
        assertThat(assertThrows(HttpStatusException::class.java) { api.account() }.code).isEqualTo(502)
    }

    @Test
    fun `lists stream lazily and tolerate empty-object answers`() {
        enqueue("""[{"stream_id":1,"name":"A"}, 5, {"stream_id":"2","name":"B"}]""")
        val got = mutableListOf<JsonObject>()
        api.streamArray("get_live_streams") { got += it }
        assertThat(got.map { it.str("stream_id") }).containsExactly("1", "2").inOrder()

        enqueue("{}")
        var n = 0
        api.streamArray("get_series") { n++ }
        assertThat(n).isEqualTo(0)
    }

    @Test
    fun `truncated list response fails as a format error`() {
        enqueue("""[{"stream_id":1,"name":"A"},{"stream_id":2,"na""")
        assertThrows(XtreamFormatException::class.java) { api.streamArray("get_live_streams") { } }
    }

    @Test
    fun `categories map`() {
        enqueue("""[{"category_id":"7","category_name":"News"},{"category_id":8,"category_name":"Sport"}]""")
        assertThat(api.categories("get_live_categories")).containsExactly("7", "News", "8", "Sport")
    }

    @Test
    fun `series info in both episode shapes`() {
        val keyed = XtreamApi.parseSeriesInfo(
            XtreamApi.JSON.parseToJsonElement(
                """{"info":{"plot":"P","cover":"https://img/c.jpg"},"episodes":{"2":[{"id":"21","episode_num":1,"season":2,"title":"S2E1","container_extension":"mkv"}],
                   "1":[{"id":"12","episode_num":"2","season":"1","title":"S1E2"},{"id":"11","episode_num":1,"season":1,"title":"S1E1","info":{"duration_secs":"1500","movie_image":"https://img/e.jpg"}}]}}""",
            ) as JsonObject,
        )
        assertThat(keyed.episodes.map { it.id }).containsExactly("11", "12", "21").inOrder()
        assertThat(keyed.episodes[0].durationSec).isEqualTo(1500)
        assertThat(keyed.episodes[1].extension).isEqualTo("mp4")
        assertThat(keyed.cover).isEqualTo("https://img/c.jpg")

        val flat = XtreamApi.parseSeriesInfo(XtreamApi.JSON.parseToJsonElement("""{"episodes":[{"id":"1","title":"X"}]}""") as JsonObject)
        assertThat(flat.episodes.single().season).isEqualTo(1)
        assertThat(XtreamApi.parseSeriesInfo(XtreamApi.JSON.parseToJsonElement("""{"episodes":null}""") as JsonObject).episodes).isEmpty()
    }

    @Test
    fun `play urls encode credentials as path segments`() {
        val url = api.liveUrl("42", "m3u8")
        assertThat(url).endsWith("/live/user%20name/p@ss%2Fword/42.m3u8")
        assertThat(api.movieUrl("7", "../mkv")).endsWith("/7.mkv")
    }

    @Test
    fun `server normalization`() {
        assertThat(XtreamApi.normalizeServer("provider.example:8080")!!.toString()).isEqualTo("http://provider.example:8080/")
        assertThat(XtreamApi.normalizeServer("https://p.example/player_api.php?username=a&password=b")!!.toString()).isEqualTo("https://p.example/")
        assertThat(XtreamApi.normalizeServer("https://p.example/sub/get.php")!!.toString()).isEqualTo("https://p.example/sub")
        assertThat(XtreamApi.normalizeServer("")).isNull()
        assertThat(XtreamApi.normalizeServer("ftp://x")).isNull()
    }

    @Test
    fun `plain http is refused unless allowed`() {
        CleartextGate.allowed = false
        assertThrows(CleartextBlockedException::class.java) { api.account() }
        assertThat(server.requestCount).isEqualTo(0)
    }
}
