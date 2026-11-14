package com.muxlume.app.distribution

import android.content.Context
import com.muxlume.app.R
import com.muxlume.app.support.SupportIntents

/** A published release newer than the running app. */
data class ReleaseInfo(val version: String, val url: String)

/** Looks up a newer version. The `github` build reads GitHub releases, the `play` build asks Google Play; `fdroid` has none (F-Droid updates it). */
interface UpdateChecker {
    /** The newer release, or null if there is none. Throws IOException on network or format errors. */
    suspend fun newerThan(current: String): ReleaseInfo?

    /** Text of the button that acts on a result of [newerThan]. */
    val actionLabel: Int get() = R.string.about_view_release

    /** Acts on a result of [newerThan] (by default, opens its page). False if nothing could be opened. */
    fun open(context: Context, release: ReleaseInfo): Boolean = SupportIntents.openUrl(context, release.url)
}

/** "v1.2.10" is newer than "1.2.9": compares the dotted numbers, ignoring a leading v and any suffix. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
