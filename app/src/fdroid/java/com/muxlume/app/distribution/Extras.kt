package com.muxlume.app.distribution

import android.content.Context
import okhttp3.OkHttpClient

/** Features that exist only in some builds (see app/build.gradle.kts). This build has none. */
object Extras {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant") // same signature as the github build
    fun updates(context: Context, http: OkHttpClient): UpdateChecker? = null
}
