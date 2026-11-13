package com.muxlume.app.recording

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Recording files live in the folder the user picked (a Storage Access Framework tree), so the app needs no storage permission. */
object RecordingFiles {
    private const val MAX_NAME = 120
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+")

    /** The new document's URI, or null if the folder is gone or refuses. */
    fun create(resolver: ContentResolver, tree: Uri, name: String): Uri? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.createDocument(resolver, parent, "video/mp2t", name)
    }.getOrNull()

    fun delete(resolver: ContentResolver, file: Uri) = runCatching { DocumentsContract.deleteDocument(resolver, file) }.getOrDefault(false)

    /** "Channel - Title - 2026-11-13 2005", safe as a file name on any provider. */
    fun fileName(channel: String, title: String, startAtSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = STAMP.format(Instant.ofEpochSecond(startAtSeconds).atZone(zone))
        val base = listOf(channel, title).map { it.replace(FORBIDDEN, "_").trim().trim('.') }.filter { it.isNotEmpty() }.joinToString(" - ").take(MAX_NAME)
        return "${base.ifEmpty { "Recording" }} - $stamp"
    }
}
