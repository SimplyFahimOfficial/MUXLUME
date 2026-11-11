package com.muxlume.app.support

/**
 * The developer's project and payment details, in one place so a link is a one-line change.
 *
 * Nothing here is sent anywhere by the app: links open in apps the user picks, and the UPI QR code
 * is generated on the device. Only destinations that were checked to exist are shown in the UI; the
 * project website is listed even though it is not live yet, at the developer's request.
 */
object SupportConfig {
    /** Project website (may be unreachable until it is published). */
    const val WEBSITE = "https://simplyfahim.pages.dev/Muxlume"

    /** Telegram community. */
    const val TELEGRAM = "https://t.me/Muxlume"

    /** Developer contact page. */
    const val CONTACT = "https://simplyfahim.pages.dev/contactme"

    /** Other payment options (cards, etc.), on the developer's site. */
    const val MORE_WAYS_TO_PAY = "https://simplyfahim.pages.dev/pay-options"

    /** Project repository (source code, issues, releases). */
    const val GITHUB_REPO = "https://github.com/SimplyFahimOfficial/MUXLUME"

    /** Public UPI ID (VPA) and the payee name UPI apps show before the user confirms. */
    const val UPI_ID = "SimplyFahim@sbi"
    const val UPI_PAYEE_NAME = "Md Fahim"
    const val UPI_CURRENCY = "INR"

    /**
     * Standard UPI link that only identifies the payee:
     * `upi://pay?pa=SimplyFahim@sbi&pn=Md%20Fahim&cu=INR`.
     *
     * No amount (`am`) is ever set: the user enters it and confirms in their own UPI app. The same
     * string is the "open UPI app" intent data and the QR payload. `@` stays literal (valid in a URI
     * query, and what bank-issued UPI QR codes use); anything else outside the RFC 3986 unreserved
     * set is percent-encoded.
     */
    fun upiPaymentUri(
        upiId: String = UPI_ID,
        payeeName: String = UPI_PAYEE_NAME,
        currency: String = UPI_CURRENCY,
    ): String = "upi://pay?pa=${encode(upiId)}&pn=${encode(payeeName)}&cu=${encode(currency)}"

    private val KEPT_AS_IS: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~', '@')).toSet()
    private const val BYTE_MASK = 0xFF

    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and BYTE_MASK
            val c = unsigned.toChar()
            if (c in KEPT_AS_IS) append(c) else append("%%%02X".format(unsigned))
        }
    }
}
