package com.muxlume.app.data

import android.content.res.Resources
import com.muxlume.app.R
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

/** User-facing explanations of import and connection failures. Never includes URLs or credentials. */
object ErrorText {
    fun of(res: Resources, e: Throwable): String = when (e) {
        is CleartextBlockedException -> res.getString(R.string.error_cleartext)
        is XtreamAuthException -> when (e.reason) {
            XtreamAuthException.Reason.INVALID_CREDENTIALS -> res.getString(R.string.error_xtream_credentials)
            XtreamAuthException.Reason.EXPIRED -> res.getString(R.string.error_xtream_expired)
            XtreamAuthException.Reason.INACTIVE -> res.getString(R.string.error_xtream_inactive, e.status)
        }
        is XtreamFormatException -> res.getString(R.string.error_xtream_format)
        is HttpStatusException -> when (e.code) {
            401, 403 -> res.getString(R.string.error_http_refused, e.code)
            404 -> res.getString(R.string.error_http_not_found)
            in 500..599 -> res.getString(R.string.error_http_server, e.code)
            else -> res.getString(R.string.error_http_other, e.code)
        }
        is EpgFormatException -> res.getString(R.string.error_guide_format)
        is TooLargeException -> res.getString(R.string.error_too_large)
        is UnknownHostException -> res.getString(R.string.error_unknown_host)
        is SSLException -> res.getString(R.string.error_tls)
        is InterruptedIOException -> res.getString(R.string.error_timeout)
        is FileNotFoundException, is SecurityException -> res.getString(R.string.error_file)
        // Our own messages are short and URL-free; anything else gets the generic text.
        is IOException -> e.message?.takeIf { it.length < 120 && "://" !in it } ?: res.getString(R.string.error_network)
        else -> res.getString(R.string.error_other)
    }
}
