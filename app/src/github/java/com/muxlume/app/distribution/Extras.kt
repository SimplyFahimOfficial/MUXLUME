package com.muxlume.app.distribution

import okhttp3.OkHttpClient

/** Features that exist only in some builds (see app/build.gradle.kts). */
object Extras {
    /** Self-update check: the GitHub build is not updated by a store. Runs only when the user taps the button. */
    fun updates(http: OkHttpClient): UpdateChecker? = GithubUpdates(http)
}
