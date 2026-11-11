package com.muxlume.app.distribution

import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.core.net.Http.requireSuccess
import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Serializable
internal data class GithubRelease(@SerialName("tag_name") val tag: String, @SerialName("html_url") val url: String)

internal class GithubUpdates(
    private val http: OkHttpClient,
    private val endpoint: String = "https://api.github.com/repos/SimplyFahimOfficial/MUXLUME/releases/latest",
) : UpdateChecker {
    override suspend fun newerThan(current: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val call = request(endpoint.toHttpUrl(), HttpPolicy.HTTPS_ONLY).newBuilder().header("Accept", "application/vnd.github+json").build()
        val text = http.newCall(call).execute().requireSuccess().use { r ->
            LimitedInputStream(r.body.byteStream(), MAX_BYTES).readBytes().decodeToString()
        }
        val release = JSON.decodeFromString(GithubRelease.serializer(), text)
        // Only link to the project's own releases, whatever the response says.
        release.takeIf { isNewerVersion(it.tag, current) && it.url.startsWith(RELEASE_PREFIX) }?.let { ReleaseInfo(it.tag.removePrefix("v"), it.url) }
    }

    private companion object {
        const val MAX_BYTES = 256L * 1024
        const val RELEASE_PREFIX = "https://github.com/SimplyFahimOfficial/MUXLUME/releases/"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
