package com.muxlume.app.support

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.Accent
import com.muxlume.app.ui.theme.accentColor
import org.junit.Test
import java.io.File

class SupportTest {
    @Test
    fun `upi link identifies only the payee`() {
        assertThat(SupportConfig.upiPaymentUri()).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=Md%20Fahim&cu=INR")
        assertThat(SupportConfig.upiPaymentUri()).doesNotContain("am=")
        assertThat(SupportConfig.upiPaymentUri(payeeName = "A&B=C")).contains("pn=A%26B%3DC")
    }

    @Test
    fun `qr code is square with the quiet zone`() {
        val m = UpiQrCode.encode(SupportConfig.upiPaymentUri())
        assertThat(m.width).isEqualTo(m.height)
        for (i in 0 until UpiQrCode.QUIET_ZONE_MODULES) assertThat(m[i, i]).isFalse()
    }

    /** Without the <queries> entry, Android 11+ hides every UPI app and "Pay with UPI" always fails. */
    @Test
    fun `manifest declares upi package visibility and a private file provider`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).containsMatch("""<queries>[\s\S]*android:scheme="upi"[\s\S]*</queries>""")
        assertThat(manifest).containsMatch("""FileProvider"\s+android:authorities="\$\{applicationId\}\.supportshare"\s+android:exported="false"""")
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `every accent reaches WCAG AA in both themes`() {
        val paper = Color(0xFFFAF7F2)
        for (accent in Accent.entries) {
            val light = accentColor(accent, light = true)
            val dark = accentColor(accent, light = false)
            assertThat(contrast(light, paper)).isAtLeast(4.5) // accent text and icons, light theme
            assertThat(contrast(Color.White, light)).isAtLeast(4.5) // white label on an accent fill
            assertThat(contrast(dark, Color.Black)).isAtLeast(4.5) // accent text and icons, dark theme
            assertThat(contrast(Color.Black, dark)).isAtLeast(4.5) // black label on an accent fill
        }
    }
}
