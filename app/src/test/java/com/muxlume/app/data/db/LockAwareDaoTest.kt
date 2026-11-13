package com.muxlume.app.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards against a new query leaking a locked group: every DAO method that returns items, history,
 * reminders or programme hits must take `:unlocked` in its query, or be listed here with the reason
 * it is safe. Reads the DAO sources, so adding a query without thinking about the lock fails here.
 */
class LockAwareDaoTest {
    private val guarded = listOf("ItemEntity", "HistoryEntity", "ReminderEntity", "ProgramHit")

    /** "Dao.method" to why it may ignore the lock. */
    private val exempt = mapOf(
        "HistoryDao.all" to "backup export; the file is encrypted and never shown",
        "HistoryDao.get" to "resume position of an item the playback service already resolved lock-aware",
        "HistoryDao.observe" to "ids come from episodes of a series shown through ItemDao.observe",
        "ReminderDao.remindersAfter" to "re-arms alarms only; the notification itself asks ReminderDao.reminder",
        "ReminderDao.findReminder" to "keyed by a channel and programme the user is looking at",
        "ReminderDao.reminderFor" to "keyed by a channel and programme the user is looking at",
    )

    private data class Query(val dao: String, val method: String, val returns: String, val sql: String)

    private fun queries(): List<Query> {
        val dir = File("src/main/java/com/muxlume/app/data/db")
        return dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.flatMap { file ->
            val text = file.readText()
            Regex("@Query\\(").findAll(text).map { match ->
                val dao = Regex("(?:interface|abstract class) (\\w+Dao)").findAll(text.substring(0, match.range.first)).last().groupValues[1]
                val rest = text.substring(match.range.first)
                val decl = Regex("fun (\\w+)\\(([^\\n]*)").find(rest)!!
                val sql = rest.substring(0, decl.range.first)
                Query(dao, decl.groupValues[1], decl.groupValues[2].substringAfterLast("):", ""), sql)
            }.toList()
        }
    }

    @Test
    fun `every query that returns items, history, reminders or programmes is lock-aware`() {
        val all = queries()
        val shown = all.filter { q -> guarded.any { it in q.returns } }
        assertThat(shown.size).isAtLeast(25) // the scan found the DAOs

        val leaks = shown.filter { ":unlocked" !in it.sql && "${it.dao}.${it.method}" !in exempt }.map { "${it.dao}.${it.method}" }
        assertThat(leaks).isEmpty()
    }

    @Test
    fun `no exemption is stale`() {
        val names = queries().filter { q -> guarded.any { it in q.returns } }.map { "${it.dao}.${it.method}" }.toSet()
        assertThat(names).containsAtLeastElementsIn(exempt.keys)
        // An exempt method must not quietly become lock-aware without the list noticing.
        val aware = queries().filter { ":unlocked" in it.sql }.map { "${it.dao}.${it.method}" }.toSet()
        assertThat(aware.intersect(exempt.keys)).isEmpty()
    }
}
