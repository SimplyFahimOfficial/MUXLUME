package com.muxlume.app.data.lock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val triesLeft: Int) : PinResult
    data class LockedOut(val untilMs: Long) : PinResult
}

/**
 * The parental PIN. Groups in `locked_groups` are hidden everywhere (lists, search, guide, history, reminders,
 * playback) unless [unlocked] is true. Unlocking is in memory only: a new process starts locked, and the app
 * relocks after [RELOCK_MS] in the background. Without a PIN nothing is enforced.
 *
 * The PIN is stored as a PBKDF2 hash with its own salt. A PIN has few possibilities, so the hash cannot stop
 * someone who can read the app's private files; this keeps children and casual users out, nothing more.
 * shortcut: lockouts and the relock timer use the device clock, so changing the clock shortens them; move to
 * `SystemClock.elapsedRealtime` plus a boot id if that ever matters.
 */
class ParentalLock(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val iterations: Int = ITERATIONS,
) {
    private object Keys {
        val hash = stringPreferencesKey("pin_hash")
        val salt = stringPreferencesKey("pin_salt")
        val iterations = intPreferencesKey("pin_iterations")
        val failures = intPreferencesKey("pin_failures")
        val lockedUntil = longPreferencesKey("pin_locked_until")
    }

    private val mutex = Mutex()
    private val pinSet = MutableStateFlow<Boolean?>(null)
    private val session = MutableStateFlow(false)
    private val _unlocked = MutableStateFlow(false)
    private var backgroundedAt: Long? = null
    private var relockJob: Job? = null

    /** True when locked groups may be shown: no PIN is set, or the PIN was entered and has not expired. False until [load]. */
    val unlocked: StateFlow<Boolean> get() = _unlocked

    /** Whether a PIN is set; null until [load]. */
    val hasPin: StateFlow<Boolean?> get() = pinSet

    private fun publish() {
        _unlocked.value = pinSet.value == false || session.value
    }

    /** Reads whether a PIN is set. Cheap after the first call. */
    suspend fun load() {
        if (pinSet.value != null) return
        pinSet.value = store.data.first()[Keys.hash] != null
        publish()
    }

    /** [unlocked] for code that cannot observe it (the playback service), with the background timeout applied. */
    suspend fun unlockedNow(): Boolean {
        load()
        expireIfDue()
        return _unlocked.value
    }

    suspend fun setPin(pin: String) = mutex.withLock {
        require(isValid(pin)) { "PIN must be $MIN_PIN to $MAX_PIN digits" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = hash(pin, salt, iterations)
        store.edit {
            it[Keys.hash] = encode(hash)
            it[Keys.salt] = encode(salt)
            it[Keys.iterations] = iterations
            it[Keys.failures] = 0
            it.remove(Keys.lockedUntil)
        }
        pinSet.value = true
        session.value = true // whoever sets the PIN is the one it protects against
        publish()
    }

    suspend fun verify(pin: String): PinResult = mutex.withLock {
        val prefs = store.data.first()
        val stored = prefs[Keys.hash] ?: return@withLock PinResult.Ok // no PIN: nothing to check
        val t = now()
        val until = prefs[Keys.lockedUntil] ?: 0L
        if (t < until) return@withLock PinResult.LockedOut(until)
        val salt = decode(prefs[Keys.salt].orEmpty())
        val ok = MessageDigest.isEqual(hash(pin, salt, prefs[Keys.iterations] ?: iterations), decode(stored))
        if (ok) {
            store.edit {
                it[Keys.failures] = 0
                it.remove(Keys.lockedUntil)
            }
            session.value = true
            publish()
            return@withLock PinResult.Ok
        }
        val failures = (prefs[Keys.failures] ?: 0) + 1
        val wait = lockoutMs(failures)
        store.edit {
            it[Keys.failures] = failures
            if (wait > 0) it[Keys.lockedUntil] = t + wait
        }
        if (wait > 0) PinResult.LockedOut(t + wait) else PinResult.Wrong(TRIES - failures % TRIES)
    }

    fun lock() {
        relockJob?.cancel()
        backgroundedAt = null
        session.value = false
        publish()
    }

    /** The app left the screen: relock in [RELOCK_MS] (also checked on return and in [unlockedNow]). */
    fun onBackground() {
        if (!session.value) return
        backgroundedAt = now()
        relockJob?.cancel()
        relockJob = scope.launch {
            delay(RELOCK_MS)
            lock()
        }
    }

    fun onForeground() {
        relockJob?.cancel()
        expireIfDue()
        backgroundedAt = null
    }

    private fun expireIfDue() {
        val since = backgroundedAt ?: return
        if (now() - since >= RELOCK_MS) lock()
    }

    /** Forgets the PIN (Delete all data). Locked groups go with the database. */
    suspend fun clear() = mutex.withLock {
        store.edit { prefs -> listOf(Keys.hash, Keys.salt, Keys.iterations, Keys.failures, Keys.lockedUntil).forEach { prefs.remove(it) } }
        pinSet.value = false
        session.value = false
        publish()
    }

    private suspend fun hash(pin: String, salt: ByteArray, rounds: Int): ByteArray = withContext(Dispatchers.Default) {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, KEY_BITS)
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)

    companion object {
        const val MIN_PIN = 4
        const val MAX_PIN = 8
        const val TRIES = 5
        const val RELOCK_MS = 5 * 60_000L
        private const val ITERATIONS = 150_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val FIRST_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 24 * 60 * 60_000L
        private const val MAX_DOUBLINGS = 16

        fun isValid(pin: String) = pin.length in MIN_PIN..MAX_PIN && pin.all { it in '0'..'9' }

        /** After every [TRIES] wrong tries in a row: 30 s, then twice as long each time, at most a day. 0 in between. */
        fun lockoutMs(failures: Int): Long {
            if (failures <= 0 || failures % TRIES != 0) return 0
            val doublings = (failures / TRIES - 1).coerceAtMost(MAX_DOUBLINGS)
            return (FIRST_LOCKOUT_MS shl doublings).coerceAtMost(MAX_LOCKOUT_MS)
        }
    }
}
