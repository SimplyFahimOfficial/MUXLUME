package com.muxlume.app.data.epg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.muxlume.app.container
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Refreshes the TV guide of every source that has it turned on. Runs about every 12 hours, only
 * while the device is online (and, by default, on an unmetered network) and the battery isn't low.
 * Failures are stored on the source and shown in TV guide settings; the work itself never retries
 * in a loop.
 */
class EpgRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val nowMillis = System.currentTimeMillis()
        for (source in c.db.sources().allNow().filter { it.guideEnabled }) {
            // A refresh a moment ago (the user's own, or the previous run) is fresh enough.
            if (source.epgRefreshedAt?.let { nowMillis - it < FRESH_MILLIS } == true) continue
            try {
                c.epg.refresh(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Already stored on the source by refresh(); continue with the next one.
            }
        }
        return Result.success()
    }

    private companion object {
        val FRESH_MILLIS = TimeUnit.HOURS.toMillis(11)
    }
}

object EpgWork {
    private const val NAME = "epg-refresh"

    fun schedule(context: Context, unmeteredOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
