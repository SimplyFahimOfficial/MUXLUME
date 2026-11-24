package com.muxlume.app

import com.muxlume.app.core.security.SecretBox
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Robolectric has no AndroidKeyStore; the storage format is what's under test, not the cipher. */
internal class PlainBox : SecretBox() {
    override fun seal(plain: String): String = Base64.getEncoder().encodeToString(plain.toByteArray())
    override fun open(sealed: String): String = String(Base64.getDecoder().decode(sealed))
}

/** Builds XMLTV text for tests. */
internal object Xmltv {
    val NOW: Long = Instant.parse("2026-11-10T12:00:00Z").epochSecond
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    fun time(epochSeconds: Long) = FORMAT.format(Instant.ofEpochSecond(epochSeconds)) + " +0000"

    fun programme(channel: String, start: Long, stop: Long, title: String, desc: String? = null, category: String? = null) =
        """<programme start="${time(start)}" stop="${time(stop)}" channel="$channel"><title lang="en">$title</title>""" +
            (desc?.let { "<desc>$it</desc>" } ?: "") + (category?.let { "<category>$it</category>" } ?: "") + "</programme>"

    fun channel(id: String, vararg names: String) = """<channel id="$id">${names.joinToString("") { "<display-name>$it</display-name>" }}</channel>"""

    fun feed(vararg parts: String, doctype: String = """<!DOCTYPE tv SYSTEM "xmltv.dtd">""") =
        """<?xml version="1.0" encoding="UTF-8"?>$doctype<tv generator-info-name="test">${parts.joinToString("")}</tv>"""
}

/**
 * Holds a response body after [after] bytes until [open] is released, so a test can act while a download is paused mid-body. It gives way
 * when the call is cancelled, and by itself after [giveUpSeconds], so a test that fails before it opens the gate ends instead of hanging.
 */
internal class HeldBody(val after: Long, private val giveUpSeconds: Long = 20) {
    val reached = java.util.concurrent.CountDownLatch(1)
    val open = java.util.concurrent.CountDownLatch(1)

    /** [client] with the body of the first response [matches] holds. */
    fun on(client: okhttp3.OkHttpClient, matches: (okhttp3.Request) -> Boolean): okhttp3.OkHttpClient = client.newBuilder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!matches(chain.request())) return@addInterceptor response
        val body = response.body
        val held = object : okio.ForwardingSource(body.source()) {
            var seen = 0L
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (seen >= after) {
                    reached.countDown()
                    val giveUp = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(giveUpSeconds)
                    while (!open.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (chain.call().isCanceled() || System.nanoTime() > giveUp) throw java.io.IOException("Canceled")
                    }
                }
                val room = if (seen < after) minOf(byteCount, after - seen) else byteCount
                return super.read(sink, room).also { if (it > 0) seen += it }
            }
        }
        response.newBuilder().body(held.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }.build()
}
