package com.muxlume.app

import com.muxlume.app.core.security.SecretBox
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
