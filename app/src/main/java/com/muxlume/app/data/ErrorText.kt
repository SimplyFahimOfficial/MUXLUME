package com.muxlume.app.data

import android.content.res.Resources
import com.muxlume.app.R
import com.muxlume.app.data.backup.BackupException
import com.muxlume.app.data.epg.EpgFormatException
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.core.net.HttpStatusException
import com.muxlume.app.core.net.TooLargeException
import com.muxlume.app.data.xtream.XtreamAuthException
import com.muxlume.app.data.xtream.XtreamFormatException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A failure the app words itself, for the user to read: its message is shown as it is. Nothing else's is: the text of an exception
 * from the network stack, the system or a provider can hold an address, a login or whatever a server chose to send.
 */
class UserFacingException(message: String) : IOException(message)

/** User-facing explanations of import and connection failures. Never includes URLs, credentials or the text of another exception. */
object ErrorText {
    fun of(res: Resources, e: Throwable): String = when (e) {
        is CleartextBlockedException -> res.getString(R.string.error_cleartext)
        is XtreamAuthException -> when (e.reason) {
            XtreamAuthException.Reason.INVALID_CREDENTIALS -> res.getString(R.string.error_xtream_credentials)
            XtreamAuthException.Reason.EXPIRED -> res.getString(R.string.error_xtream_expired)
            XtreamAuthException.Reason.INACTIVE -> res.getString(R.string.error_xtream_inactive, shown(e.status))
        }
        is XtreamFormatException -> res.getString(R.string.error_xtream_format)
        is HttpStatusException -> when (e.code) {
            401, 403 -> res.getString(R.string.error_http_refused, e.code)
            404 -> res.getString(R.string.error_http_not_found)
            in 500..599 -> res.getString(R.string.error_http_server, e.code)
            else -> res.getString(R.string.error_http_other, e.code)
        }
        is BackupException -> res.getString(
            when (e.reason) {
                BackupException.Reason.NOT_A_BACKUP -> R.string.error_backup_not_a_backup
                BackupException.Reason.WRONG_PASSPHRASE -> R.string.error_backup_wrong_passphrase
                BackupException.Reason.TOO_NEW -> R.string.error_backup_too_new
                BackupException.Reason.TOO_LARGE -> R.string.error_backup_too_large
                BackupException.Reason.WEAK_PASSPHRASE -> R.string.error_backup_weak_passphrase
            },
        )
        is EpgFormatException -> res.getString(R.string.error_guide_format)
        is TooLargeException -> res.getString(R.string.error_too_large)
        is UnknownHostException -> res.getString(R.string.error_unknown_host)
        is SSLException -> res.getString(R.string.error_tls)
        is InterruptedIOException -> res.getString(R.string.error_timeout)
        is FileNotFoundException, is SecurityException -> res.getString(R.string.error_file)
        is UserFacingException -> e.message ?: res.getString(R.string.error_other)
        is IOException -> res.getString(R.string.error_network)
        else -> res.getString(R.string.error_other)
    }

    /** The account status a provider sent, for display: a short word or two, or nothing. It is the provider's text, not ours. */
    fun shown(status: String?): String = status?.trim()?.takeIf { it.length <= MAX_STATUS && it.all { c -> c.isLetter() || c == ' ' } } ?: "?"

    private const val MAX_STATUS = 24
}
