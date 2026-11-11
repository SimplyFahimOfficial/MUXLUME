package com.muxlume.app.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    private val pass = "correct horse".toCharArray()
    private val plain = """{"secret":"http://user:pw@host/list.m3u"}""".toByteArray()

    private fun reason(block: () -> Unit) = assertThrows(BackupException::class.java, block).reason

    @Test
    fun roundTripsAndHidesTheContent() {
        val file = BackupCrypto.encrypt(plain, pass)
        assertThat(String(file, Charsets.ISO_8859_1)).doesNotContain("host")
        assertThat(BackupCrypto.decrypt(file, pass)).isEqualTo(plain)
    }

    @Test
    fun twoBackupsOfTheSameDataDiffer() {
        assertThat(BackupCrypto.encrypt(plain, pass)).isNotEqualTo(BackupCrypto.encrypt(plain, pass))
    }

    @Test
    fun wrongPassphraseIsRefused() {
        val file = BackupCrypto.encrypt(plain, pass)
        assertThat(reason { BackupCrypto.decrypt(file, "wrong horse".toCharArray()) }).isEqualTo(BackupException.Reason.WRONG_PASSPHRASE)
    }

    @Test
    fun tamperingWithHeaderOrBodyIsDetected() {
        val file = BackupCrypto.encrypt(plain, pass)
        for (i in listOf(4, 5, 12, 20, file.size - 1)) { // version, iteration count, salt, IV, tag
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val r = reason { BackupCrypto.decrypt(bad, pass) }
            assertThat(r).isAnyOf(BackupException.Reason.WRONG_PASSPHRASE, BackupException.Reason.TOO_NEW, BackupException.Reason.NOT_A_BACKUP)
        }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertThat(reason { BackupCrypto.decrypt("hello".toByteArray(), pass) }).isEqualTo(BackupException.Reason.NOT_A_BACKUP)
        assertThat(reason { BackupCrypto.decrypt(ByteArray(200), pass) }).isEqualTo(BackupException.Reason.NOT_A_BACKUP)
    }

    @Test
    fun shortPassphraseIsRefused() {
        assertThat(reason { BackupCrypto.encrypt(plain, "short".toCharArray()) }).isEqualTo(BackupException.Reason.WEAK_PASSPHRASE)
    }
}
