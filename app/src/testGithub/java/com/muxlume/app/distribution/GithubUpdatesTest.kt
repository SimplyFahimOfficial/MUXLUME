package com.muxlume.app.distribution

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class GithubUpdatesTest {
    private fun check(body: String, current: String): ReleaseInfo? = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(body))
        server.start()
        runBlocking { GithubUpdates(OkHttpClient(), server.url("/latest").toString()).newerThan(current) }
    }

    private fun json(tag: String, url: String) = """{"tag_name":"$tag","html_url":"$url","draft":false,"assets":[]}"""
    private val own = "https://github.com/SimplyFahimOfficial/MUXLUME/releases/tag/v0.2.0"

    @Test
    fun reportsANewerRelease() {
        assertThat(check(json("v0.2.0", own), "0.1.0")).isEqualTo(ReleaseInfo("0.2.0", own))
    }

    @Test
    fun isQuietWhenUpToDate() {
        assertThat(check(json("v0.1.0", own), "0.1.0")).isNull()
    }

    @Test
    fun neverLinksOutsideTheProjectsReleases() {
        assertThat(check(json("v9.9.9", "https://evil.example/download"), "0.1.0")).isNull()
    }
}
