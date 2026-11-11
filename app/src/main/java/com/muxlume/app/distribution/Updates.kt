package com.muxlume.app.distribution

/** A published release newer than the running app. */
data class ReleaseInfo(val version: String, val url: String)

/** Looks up the newest release. Only the `github` build has one; the stores update the app themselves. */
fun interface UpdateChecker {
    /** The newest release, or null if it is not newer than [current]. Throws on network or format errors. */
    suspend fun newerThan(current: String): ReleaseInfo?
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
