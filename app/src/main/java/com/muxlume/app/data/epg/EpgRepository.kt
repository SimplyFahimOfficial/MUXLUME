package com.muxlume.app.data.epg

import android.content.res.Resources
import androidx.room.withTransaction
import com.muxlume.app.R
import com.muxlume.app.core.net.Http.requireSuccess
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.request
import com.muxlume.app.data.ErrorText
import com.muxlume.app.data.SourceRepository
import com.muxlume.app.data.db.EpgChannelEntity
import com.muxlume.app.data.db.EpgDao
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.ProgramEntity
import com.muxlume.app.data.db.ReminderEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/** What is on a channel now and what follows. */
data class NowNext(val now: ProgramEntity?, val next: ProgramEntity?)

/**
 * Downloads and stores TV guides. Like playlist imports, a refresh stages its rows as `pending`
 * (the old guide stays visible), then swaps them in with one short transaction; a failed or
 * cancelled refresh drops the staged rows and keeps the old guide.
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

    /** Turns the guide on (and downloads it in the caller's scope via [refresh]) or off (and deletes it). */
    suspend fun setEnabled(sourceId: Long, enabled: Boolean) {
        db.guide().setGuideEnabled(sourceId, enabled)
        if (!enabled) {
            dao.clear(sourceId)
            db.guide().resetGuide(sourceId)
        }
    }

    /** Downloads the guide of [sourceId] and replaces the stored one. Returns the number of programmes kept. */
    suspend fun refresh(sourceId: Long): Int = withContext(Dispatchers.IO) {
        val source = db.sources().get(sourceId) ?: throw IOException("Source not found")
        val url = sources.guideUrl(source) ?: throw IOException(res.getString(R.string.error_no_guide_link))
        dao.discardPending(sourceId) // left over if the app was killed during an earlier refresh
        try {
            download(sourceId, request(url, HttpPolicy.of(source.allowHttp)))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.discardPending(sourceId) }
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // any failure is stored on the source, then rethrown
            withContext(NonCancellable) {
                dao.discardPending(sourceId)
                db.guide().setGuideError(sourceId, ErrorText.of(res, e))
            }
            throw e
        }
    }

    private suspend fun download(sourceId: Long, request: okhttp3.Request): Int {
        val now = nowSeconds()
        val parser = XmltvParser(now)
        val channels = ArrayList<EpgChannelEntity>()
        val batch = ArrayList<ProgramEntity>(BATCH)
        http.newCall(request).execute().requireSuccess().use { response ->
            val body = LimitedInputStream(response.body.byteStream(), MAX_DOWNLOAD)
            XmltvParser.open(body).use { stream -> ingest(sourceId, parser.parse(stream), channels, batch) }
        }
        dao.insertPrograms(batch)
        channels.chunked(BATCH).forEach { dao.insertChannels(it) }
        val links = linker.links(sourceId, channels)
        db.withTransaction {
            dao.commit(sourceId, links)
            db.guide().setGuideDone(sourceId, System.currentTimeMillis(), parser.kept)
        }
        return parser.kept
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
