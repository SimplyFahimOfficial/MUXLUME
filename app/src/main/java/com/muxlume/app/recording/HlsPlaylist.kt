package com.muxlume.app.recording

/** Why a stream cannot be recorded (or how a recording went wrong). The name is stored as the recording's `error`. */
class RecordingException(val reason: Reason) : java.io.IOException(reason.name) {
    enum class Reason { DASH, RTSP, DRM, ENCRYPTED, FMP4, NETWORK, STORAGE_FULL, WRITE_FAILED, LOCKED, GONE, NO_FOLDER, TIME_LIMIT, MISSED, INTERRUPTED }
}

/** Ends a recording attempt with [reason]. */
internal fun refuse(reason: RecordingException.Reason): Nothing = throw RecordingException(reason)

/** The parts of an HLS playlist a recorder needs. */
sealed interface HlsPlaylist {
    class Variant(val uri: String, val bandwidth: Long)

    /** Lists other playlists, one per quality. */
    class Master(val variants: List<Variant>) : HlsPlaylist

    /**
     * Lists segments. [problem] is set when they cannot be copied as they are: AES-128 or other encryption, DRM,
     * or fragmented MP4 (a separate init segment).
     */
    class Media(
        val targetDurationSeconds: Int,
        val firstSequence: Long,
        val segments: List<String>,
        val ended: Boolean,
        val problem: RecordingException.Reason?,
    ) : HlsPlaylist
}

object HlsParser {
    private val FMP4_EXTENSIONS = listOf(".m4s", ".mp4", ".cmfv", ".cmfa")

    /** Null if [text] is not an HLS playlist. */
    fun parse(text: String): HlsPlaylist? {
        val lines = text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") return null
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) master(lines) else media(lines)
    }

    private fun master(lines: List<String>): HlsPlaylist {
        val variants = ArrayList<HlsPlaylist.Variant>()
        var bandwidth: Long? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                !line.startsWith("#") && bandwidth != null -> {
                    variants += HlsPlaylist.Variant(line, bandwidth)
                    bandwidth = null
                }
            }
        }
        return HlsPlaylist.Master(variants)
    }

    private fun media(lines: List<String>): HlsPlaylist {
        var target = 0
        var sequence = 0L
        var ended = false
        var problem: RecordingException.Reason? = null
        val segments = ArrayList<String>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-KEY:") -> problem = problem ?: keyProblem(line)
                line.startsWith("#EXT-X-MAP:") -> problem = problem ?: RecordingException.Reason.FMP4
                !line.startsWith("#") -> {
                    segments += line
                    if (FMP4_EXTENSIONS.any { line.substringBefore('?').lowercase().endsWith(it) }) problem = problem ?: RecordingException.Reason.FMP4
                }
            }
        }
        return HlsPlaylist.Media(target, sequence, segments, ended, problem)
    }

    /** METHOD=NONE is fine; AES-128 would need the key (not done); anything else is DRM. DRM wins over plain encryption. */
    private fun keyProblem(line: String): RecordingException.Reason? = when (Regex("METHOD=([A-Za-z0-9-]+)").find(line)?.groupValues?.get(1)) {
        "NONE" -> null
        "AES-128" -> RecordingException.Reason.ENCRYPTED
        else -> RecordingException.Reason.DRM
    }
}
