package com.muxlume.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.muxlume.app.container
import com.muxlume.app.data.db.SourceEntity
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Re-downloads the playlists whose last import is about [PlaylistRefresh] old. A playlist that fails
 * keeps its previous channels and shows the error on its source; it never stops the others.
 */
class PlaylistRefresher(private val sources: SourceRepository, private val now: () -> Long = System::currentTimeMillis) {
    /** Returns how many playlists were refreshed successfully. */
    suspend fun run(every: PlaylistRefresh): Int {
        if (every == PlaylistRefresh.OFF) return 0
        var done = 0
        for (source in sources.all().filter { isDue(it, every, now()) }) {
            try {
                sources.refresh(source.id)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") ignored: Exception) {
                // refresh() already stored the error on the source.
            }
        }
        return done
    }

    companion object {
        private const val DUE_PERCENT = 90
        private const val PERCENT = 100

        /** Due once 90% of the interval has passed, so a worker that runs a bit early doesn't skip a whole round. */
        fun isDue(source: SourceEntity, every: PlaylistRefresh, nowMillis: Long): Boolean {
            if (every == PlaylistRefresh.OFF) return false
            val last = source.refreshedAt ?: return true
            return nowMillis - last >= TimeUnit.HOURS.toMillis(every.hours) * DUE_PERCENT / PERCENT
        }
    }
}

class PlaylistRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        c.playlistRefresher.run(c.settings.current().playlistRefresh)
        return Result.success()
    }
}

object PlaylistWork {
    private const val NAME = "playlist-refresh"
    private const val CHECK_HOURS = 12L // how often to look; PlaylistRefresh decides what is due

    fun schedule(context: Context, unmeteredOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(CHECK_HOURS, TimeUnit.HOURS)
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
