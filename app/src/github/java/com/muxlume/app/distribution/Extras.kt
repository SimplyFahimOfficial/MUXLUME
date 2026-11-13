package com.muxlume.app.distribution

import android.content.Context
import okhttp3.OkHttpClient

/** Features that exist only in some builds (see app/build.gradle.kts). */
object Extras {
    /** Self-update check: the GitHub build is not updated by a store. Runs only when the user taps the button. */
    @Suppress("UNUSED_PARAMETER") // same signature as the other builds
    fun updates(context: Context, http: OkHttpClient): UpdateChecker? = GithubUpdates(http)
}
