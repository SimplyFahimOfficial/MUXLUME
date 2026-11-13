package com.muxlume.app.distribution

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.AppUpdateOptions.defaultOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import com.muxlume.app.R
import java.io.IOException

/** What Google Play offers, as the app shows it: the version code, since Play does not give a version name. Null if there is nothing to do. */
internal fun playUpdate(availability: Int, immediateAllowed: Boolean, versionCode: Int): ReleaseInfo? {
    val actionable = availability == UpdateAvailability.UPDATE_AVAILABLE || availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
    return if (actionable && immediateAllowed) ReleaseInfo(versionCode.toString(), "") else null
}

/**
 * Play in-app update, immediate type (Play's own full-screen flow; there is no download state to track).
 * Google Play is contacted only when the user taps "Check for updates" and then "Update".
 */
internal class PlayUpdates(context: Context) : UpdateChecker {
    private val manager = AppUpdateManagerFactory.create(context)
    @Volatile private var info: AppUpdateInfo? = null

    override val actionLabel: Int get() = R.string.about_update_now

    override suspend fun newerThan(current: String): ReleaseInfo? {
        val found = try {
            manager.requestAppUpdateInfo()
        } catch (e: InstallException) {
            // Not installed from Play (a sideloaded play build), Play missing or busy: say so like any failed check.
            throw IOException("Google Play could not be reached", e)
        }
        info = found
        return playUpdate(found.updateAvailability(), found.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE), found.availableVersionCode())
    }

    override fun open(context: Context, release: ReleaseInfo): Boolean {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() ?: return false
        val found = info ?: return false
        manager.startUpdateFlow(found, activity, defaultOptions(AppUpdateType.IMMEDIATE))
        return true
    }
}
