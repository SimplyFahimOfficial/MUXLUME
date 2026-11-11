package com.muxlume.app.data

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
 * User-facing explanations of import and connection failures. Never includes URLs or credentials.
 * shortcut: English only; move to string resources when the app is translated.
 */
object ErrorText {
    fun of(e: Throwable): String = when (e) {
        is CleartextBlockedException -> "This server uses unencrypted HTTP. Allow HTTP for this playlist to continue."
        is XtreamAuthException -> when (e.reason) {
            XtreamAuthException.Reason.INVALID_CREDENTIALS -> "The provider rejected the username or password."
            XtreamAuthException.Reason.EXPIRED -> "This provider account has expired."
            XtreamAuthException.Reason.INACTIVE -> "This provider account is not active (${e.status})."
        }
        is XtreamFormatException -> "The server didn't answer like an Xtream Codes-compatible service."
        is HttpStatusException -> when (e.code) {
            401, 403 -> "The server refused access (HTTP ${e.code}). Check the link or your subscription."
            404 -> "Nothing was found at this address (HTTP 404)."
            in 500..599 -> "The server had a problem (HTTP ${e.code}). Try again later."
            else -> "The server answered with HTTP ${e.code}."
        }
        is TooLargeException -> "The playlist is too large to import."
        is UnknownHostException -> "The server address can't be found. Check it and your connection."
        is SSLException -> "A secure connection couldn't be established. The server's certificate may be invalid."
        is InterruptedIOException -> "The server took too long to answer."
        is FileNotFoundException, is SecurityException -> "The file can't be read anymore. Pick it again."
        is IOException -> e.message?.takeIf { it.length < 120 && "://" !in it } ?: "A network error occurred."
        else -> "Something went wrong while reading the playlist."
    }
}
