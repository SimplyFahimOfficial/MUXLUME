package com.muxlume.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.R
import com.muxlume.app.core.net.CleartextBlockedException
import com.muxlume.app.core.net.HttpStatusException
import com.muxlume.app.core.net.TooLargeException
import com.muxlume.app.data.xtream.XtreamAuthException
import com.muxlume.app.data.xtream.XtreamFormatException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** What a user is shown for a failure: typed texts, with the distinctions kept, and nothing a server or the system wrote. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ErrorTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private fun text(e: Throwable) = ErrorText.of(res, e)

    private val markers = listOf("SYNTH-PASS-7731", "SYNTH-TOKEN-4410", "synth-host.invalid", "203.0.113.77", "SYNTH-USER")

    @Test
    fun `text from the network stack, the system or a server is never shown, whatever it carries`() {
        val risky = listOf(
            ConnectException("Failed to connect to synth-host.invalid/203.0.113.77:8443"),
            ProtocolException("Unexpected status line: SYNTH-TOKEN-4410 SYNTH-PASS-7731 for SYNTH-USER"),
            IOException("unexpected end of stream on synth-host.invalid:8443 SYNTH-TOKEN-4410"),
            IOException("SYNTH-PASS-7731"),
            IllegalStateException("SYNTH-USER at 203.0.113.77"),
            RuntimeException(IOException("SYNTH-TOKEN-4410")),
        )
        for (e in risky) markers.forEach { assertThat(text(e)).doesNotContain(it) }
        assertThat(text(ProtocolException("Unexpected status line: SYNTH-TOKEN-4410"))).isEqualTo(res.getString(R.string.error_network))
        assertThat(text(IllegalStateException("SYNTH-USER"))).isEqualTo(res.getString(R.string.error_other))
    }

    @Test
    fun `what the app words itself is shown as it is`() {
        assertThat(text(UserFacingException("The playlist has no playable channels"))).isEqualTo("The playlist has no playable channels")
    }

    @Test
    fun `the useful distinctions stay`() {
        assertThat(text(XtreamAuthException(XtreamAuthException.Reason.INVALID_CREDENTIALS))).isEqualTo(res.getString(R.string.error_xtream_credentials))
        assertThat(text(XtreamAuthException(XtreamAuthException.Reason.EXPIRED))).isEqualTo(res.getString(R.string.error_xtream_expired))
        assertThat(text(HttpStatusException(404))).isEqualTo(res.getString(R.string.error_http_not_found))
        assertThat(text(HttpStatusException(503))).isEqualTo(res.getString(R.string.error_http_server, 503))
        assertThat(text(SocketTimeoutException("SYNTH-TOKEN-4410"))).isEqualTo(res.getString(R.string.error_timeout))
        assertThat(text(UnknownHostException("synth-host.invalid"))).isEqualTo(res.getString(R.string.error_unknown_host))
        assertThat(text(SSLHandshakeException("synth-host.invalid"))).isEqualTo(res.getString(R.string.error_tls))
        assertThat(text(CleartextBlockedException("synth-host.invalid"))).isEqualTo(res.getString(R.string.error_cleartext))
        assertThat(text(TooLargeException(1L shl 28))).isEqualTo(res.getString(R.string.error_too_large))
        assertThat(text(XtreamFormatException("SYNTH-TOKEN-4410"))).isEqualTo(res.getString(R.string.error_xtream_format))
    }

    @Test
    fun `an account status a provider sent is shown only if it looks like a status`() {
        assertThat(text(XtreamAuthException(XtreamAuthException.Reason.INACTIVE, "Disabled"))).contains("disabled")
        for (hostile in listOf("https://SYNTH-HOST.invalid/SYNTH-TOKEN-4410", "SYNTH-PASS-7731=1", "x".repeat(500), "<b>SYNTH-USER</b>", "synth user", "hunter two")) {
            val shown = text(XtreamAuthException(XtreamAuthException.Reason.INACTIVE, hostile))
            markers.forEach { assertThat(shown).doesNotContain(it) }
            assertThat(shown).contains(res.getString(R.string.error_status_unknown)) // only the usual words are passed on, whatever else a provider calls its status
            assertThat(shown.length).isLessThan(120)
        }
    }
}
