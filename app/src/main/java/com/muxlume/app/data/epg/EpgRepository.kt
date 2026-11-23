package com.muxlume.app.data.epg

import android.content.res.Resources
import androidx.room.withTransaction
import com.muxlume.app.R
import com.muxlume.app.core.net.Http.requireSuccess
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.cancelWithCoroutine
import com.muxlume.app.core.net.request
import com.muxlume.app.data.ErrorText
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.EpgChannelEntity
import com.muxlume.app.data.db.EpgDao
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.ProgramEntity
import com.muxlume.app.data.db.ReminderEntity
import com.muxlume.app.data.db.SourceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What is on a channel now and what follows. */
data class NowNext(val now: ProgramEntity?, val next: ProgramEntity?)

/**
 * Downloads and stores TV guides. Like playlist imports, a refresh stages its rows as `pending`
 * (the old guide stays visible), then swaps them in with one short transaction; a failed or
 * cancelled refresh drops the staged rows and keeps the old guide.
 *
 * Refreshes of one source run one at a time (staged rows carry no owner, so two would mix and discard each other's), and a refresh
 * publishes only if it still applies when it finishes: the guide is still on and its feed is still the one that was downloaded.
 * Turning the guide off, or asking for another feed, releases a download that is running. Sources do not wait for each other.
 */
class EpgRepository(
    private val db: MuxlumeDatabase,
    private val sources: SourceRepository,
    private val linker: EpgLinker,
    private val http: OkHttpClient,
    private val res: Resources,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS },
) {
    private val dao: EpgDao get() = db.epg()

    /** What one source's refreshes share: their turn, and what is needed to release the download that is running. */
    private class Lane {
        val turn = Mutex()

        /** Counts the reasons a running download stops applying (see [supersede]); a refresh compares it with what it started with. */
        val epoch = AtomicLong()
        @Volatile var feed: HttpUrl? = null
        @Volatile var call: Call? = null

        /** What the running download would publish no longer applies: it ends now and publishes nothing. */
        fun supersede() {
            epoch.incrementAndGet()
            call?.cancel()
        }
    }

    private val lanes = ConcurrentHashMap<Long, Lane>()

    private fun lane(sourceId: Long) = lanes.getOrPut(sourceId) { Lane() }

    /** Turns the guide on (and downloads it in the caller's scope via [refresh]) or off (and deletes it, also from a download that is running). */
    suspend fun setEnabled(sourceId: Long, enabled: Boolean) {
        if (!enabled) lane(sourceId).supersede() // before the flag changes: a download that ends meanwhile must not bring the guide back
        db.guide().setGuideEnabled(sourceId, enabled)
        if (!enabled) {
            dao.clear(sourceId)
            db.guide().resetGuide(sourceId)
        }
    }

    /**
     * Downloads the guide of [sourceId] and replaces the stored one. Returns the number of programmes kept, or what is stored when this
     * refresh was not needed (another one finished while it waited) or no longer applies (the guide was turned off or its feed changed).
     */
    suspend fun refresh(sourceId: Long): Int = withContext(Dispatchers.IO) {
        val before = db.sources().get(sourceId) ?: throw IOException("Source not found")
        val lane = lane(sourceId)
        val wanted = sources.guideUrl(before) ?: throw IOException(res.getString(R.string.error_no_guide_link))
        // A download of another feed is of no use any more: release it rather than queue behind it.
        if (lane.feed?.let { it != wanted } == true) lane.supersede()
        lane.turn.withLock {
            val epoch = lane.epoch.get()
            val source = db.sources().get(sourceId) ?: throw IOException("Source not found")
            // Another refresh finished while this one waited for its turn: its guide is fresh enough.
            if (source.epgRefreshedAt != null && source.epgRefreshedAt != before.epgRefreshedAt) return@withLock source.epgPrograms
            // Turned off while it waited (or never on): nothing to download. The worker lists the sources it refreshes before it gets to them.
            if (!source.guideEnabled) return@withLock source.epgPrograms
            val url = sources.guideUrl(source) ?: throw IOException(res.getString(R.string.error_no_guide_link))
            refreshAndPublish(lane, epoch, source, url)
        }
    }

    private suspend fun refreshAndPublish(lane: Lane, epoch: Long, source: SourceEntity, url: HttpUrl): Int {
        val sourceId = source.id
        lane.feed = url
        dao.discardPending(sourceId) // left over if the app was killed during an earlier refresh; nobody else stages while this one has its turn
        try {
            return download(lane, epoch, sourceId, url, request(url, HttpPolicy.of(source.allowHttp)))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.discardPending(sourceId) }
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // any failure is stored on the source, then rethrown
            // A download released because the caller gave up fails like any other; that is a cancellation, not an error of the guide.
            val gaveUp = !currentCoroutineContext().isActive
            val stored = withContext(NonCancellable) {
                dao.discardPending(sourceId)
                !gaveUp && db.withTransaction { applies(lane, epoch, sourceId, url).also { if (it) db.guide().setGuideError(sourceId, ErrorText.of(res, e)) } }
            }
            currentCoroutineContext().ensureActive()
            // One that no longer applies has nothing to report: the guide it was for is off or comes from another feed now.
            if (stored) throw e
            return db.sources().get(sourceId)?.epgPrograms ?: 0
        } finally {
            lane.feed = null
            lane.call = null
        }
    }

    /** Whether what a refresh read from [url] still belongs where it would be published. Call inside the transaction that publishes it. */
    private suspend fun applies(lane: Lane, epoch: Long, sourceId: Long, url: HttpUrl): Boolean {
        if (lane.epoch.get() != epoch) return false
        val current = db.sources().get(sourceId) ?: return false
        return current.guideEnabled && runCatching { sources.guideUrl(current) }.getOrNull() == url
    }

    private suspend fun download(lane: Lane, epoch: Long, sourceId: Long, url: HttpUrl, request: okhttp3.Request): Int {
        val now = nowSeconds()
        val parser = XmltvParser(now)
        val channels = ArrayList<EpgChannelEntity>()
        val batch = ArrayList<ProgramEntity>(BATCH)
        val call = http.newCall(request)
        lane.call = call
        // Blocking reads ignore coroutine cancellation; this lets a cancelled refresh, or a superseded one, let go of a stalled download.
        call.cancelWithCoroutine {
            call.execute().requireSuccess().use { response ->
                val body = LimitedInputStream(response.body.byteStream(), MAX_DOWNLOAD)
                XmltvParser.open(body).use { stream -> ingest(sourceId, parser.parse(stream), channels, batch) }
            }
        }
        dao.insertPrograms(batch)
        channels.chunked(BATCH).forEach { dao.insertChannels(it) }
        val links = linker.links(sourceId, channels)
        val published = db.withTransaction {
            applies(lane, epoch, sourceId, url).also {
                if (it) {
                    dao.commit(sourceId, links)
                    db.guide().setGuideDone(sourceId, System.currentTimeMillis(), parser.kept)
                } else {
                    dao.discardPending(sourceId)
                }
            }
        }
        return if (published) parser.kept else db.sources().get(sourceId)?.epgPrograms ?: 0
    }

    /** Reads the parsed items, writing programmes in batches so memory stays flat. */
    private suspend fun ingest(sourceId: Long, items: Sequence<XmltvItem>, channels: MutableList<EpgChannelEntity>, batch: MutableList<ProgramEntity>) {
        for (item in items) {
            if (item is XmltvChannel && channels.size < MAX_CHANNEL_ROWS) {
                item.names.ifEmpty { listOf(item.id) }.mapTo(channels) { EpgChannelEntity(sourceId, item.id, it, pending = true) }
            }
            if (item !is XmltvProgramme) continue
            batch += ProgramEntity(
                sourceId = sourceId, channelId = item.channel, start = item.start, stop = item.stop,
                title = item.title, description = item.description, category = item.category, pending = true,
            )
            if (batch.size == BATCH) {
                currentCoroutineContext().ensureActive()
                dao.insertPrograms(batch)
                batch.clear()
            }
        }
    }

    suspend fun nowNext(sourceId: Long, stableKey: String, at: Long = nowSeconds()): NowNext {
        val list = dao.upcoming(sourceId, stableKey, at, 2)
        val first = list.firstOrNull() ?: return NowNext(null, null)
        return if (first.start <= at) NowNext(first, list.getOrNull(1)) else NowNext(null, first)
    }

    suspend fun programs(sourceId: Long, stableKey: String, from: Long, to: Long): List<ProgramEntity> =
        dao.programsFor(sourceId, stableKey, from, to)

    // ---- Manual channel matching

    suspend fun setManualLink(sourceId: Long, stableKey: String, channelId: String) = dao.setManualLink(sourceId, stableKey, channelId)

    suspend fun removeLink(sourceId: Long, stableKey: String) = dao.removeLink(sourceId, stableKey)

    suspend fun findGuideChannels(sourceId: Long, query: String): List<EpgChannelEntity> =
        dao.findChannels(sourceId, EpgDao.likeEscape(query.trim()), PICKER_LIMIT)

    // ---- Reminders

    /** Saves a reminder for [program] on a channel (the existing one if there is one), or returns null if the programme has started already. */
    suspend fun addReminder(sourceId: Long, stableKey: String, channelName: String, program: ProgramEntity): ReminderEntity? {
        db.reminders().findReminder(sourceId, stableKey, program.start)?.let { return it }
        val notifyAt = ReminderPlanner.notifyAt(program.start, nowSeconds()) ?: return null
        val draft = ReminderEntity(
            sourceId = sourceId, stableKey = stableKey, channelName = channelName.take(MAX_NAME),
            title = program.title, startAt = program.start, notifyAt = notifyAt,
        )
        return draft.copy(id = db.reminders().insertReminder(draft))
    }

    suspend fun removeReminder(id: Long) = db.reminders().deleteReminder(id)

    private companion object {
        const val BATCH = 2000
        const val MILLIS = 1000L
        const val MAX_DOWNLOAD = 256L * 1024 * 1024
        const val MAX_CHANNEL_ROWS = 200_000
        const val PICKER_LIMIT = 50
        const val MAX_NAME = 200
    }
}

/** When a reminder fires. */
object ReminderPlanner {
    const val LEAD_SECONDS = 5L * 60

    /** Five minutes before [start], or right away if that is already past; null if the programme has started. */
    fun notifyAt(start: Long, now: Long): Long? = if (start <= now) null else maxOf(start - LEAD_SECONDS, now)
}
