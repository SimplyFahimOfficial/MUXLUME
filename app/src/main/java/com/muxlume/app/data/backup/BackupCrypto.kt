package com.muxlume.app.data.backup

import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A backup could not be written or read. [reason] picks the message shown to the user. */
class BackupException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NOT_A_BACKUP, WRONG_PASSPHRASE, TOO_NEW, TOO_LARGE, WEAK_PASSPHRASE }
}

/**
 * Passphrase encryption for backup files (they hold playlist links and logins).
 *
 * File layout: `MXBK` | version (1) | PBKDF2 iterations (int) | salt (16) | IV (12) | AES-256-GCM ciphertext+tag.
 * The header is authenticated as associated data, so changing it makes decryption fail.
 */
object BackupCrypto {
    const val MIN_PASSPHRASE = 8
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT = 16
    private const val IV = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 5_000_000
    private val HEADER = MAGIC.size + 1 + Int.SIZE_BYTES + SALT + IV

    fun encrypt(plain: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        if (passphrase.size < MIN_PASSPHRASE) throw BackupException(BackupException.Reason.WEAK_PASSPHRASE)
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(header)
        }
        return header + cipher.doFinal(plain)
    }

    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        headerProblem(file)?.let { throw BackupException(it) }
        val buf = ByteBuffer.wrap(file, MAGIC.size + 1, HEADER - MAGIC.size - 1)
        val iterations = buf.getInt()
        val salt = ByteArray(SALT).also(buf::get)
        val iv = ByteArray(IV).also(buf::get)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(file, 0, HEADER)
                doFinal(file, HEADER, file.size - HEADER)
            }
        } catch (e: GeneralSecurityException) {
            // A wrong passphrase and a damaged file look the same (the GCM tag does not match).
            throw BackupException(BackupException.Reason.WRONG_PASSPHRASE).apply { initCause(e) }
        }
    }

    private fun headerProblem(file: ByteArray): BackupException.Reason? = when {
        file.size < HEADER + TAG_BITS / Byte.SIZE_BITS || !file.copyOf(MAGIC.size).contentEquals(MAGIC) -> BackupException.Reason.NOT_A_BACKUP
        file[MAGIC.size] != VERSION -> BackupException.Reason.TOO_NEW
        ByteBuffer.wrap(file, MAGIC.size + 1, Int.SIZE_BYTES).getInt() !in MIN_ITERATIONS..MAX_ITERATIONS -> BackupException.Reason.NOT_A_BACKUP
        else -> null
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
