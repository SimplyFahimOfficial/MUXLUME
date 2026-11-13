package com.muxlume.app.data.lock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ParentalLockTest {
    @get:Rule val folder = TemporaryFolder()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val second = TimeUnit.SECONDS.toMillis(1)
    private val minute = TimeUnit.MINUTES.toMillis(1)

    @After fun tearDown() = ioScope.cancel()

    private fun store(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("lock.preferences_pb") }

    /** The test's virtual clock is the lock's clock; a few hundred PBKDF2 rounds keep the tests quick. */
    private fun TestScope.lock(store: DataStore<Preferences> = store()) = ParentalLock(store, backgroundScope, { currentTime }, iterations = 300)

    @Test
    fun `the PIN is stored as a salted hash, never as text`() = runTest {
        val store = store()
        lock(store).setPin("4821")
        val first = store.data.first().asMap().entries.associate { it.key.name to it.value.toString() }
        assertThat(first.values.joinToString()).doesNotContain("4821")
        assertThat(first.keys).containsAtLeast("pin_hash", "pin_salt")

        lock(store).setPin("4821")
        val second = store.data.first().asMap().entries.associate { it.key.name to it.value.toString() }
        assertThat(second.getValue("pin_hash")).isNotEqualTo(first.getValue("pin_hash")) // new salt
    }

    @Test
    fun `only 4 to 8 digits are accepted`() = runTest {
        val lock = lock()
        listOf("", "123", "123456789", "12a4", "12 4", "١٢٣٤").forEach { bad ->
            assertThat(ParentalLock.isValid(bad)).isFalse()
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { lock.setPin(bad) } }
        }
        assertThat(ParentalLock.isValid("1234")).isTrue()
        assertThat(ParentalLock.isValid("12345678")).isTrue()
    }

    @Test
    fun `verify accepts the PIN and counts down wrong tries`() = runTest {
        val lock = lock()
        lock.setPin("4821")
        lock.lock()
        assertThat(lock.verify("0000")).isEqualTo(PinResult.Wrong(4))
        assertThat(lock.verify("1111")).isEqualTo(PinResult.Wrong(3))
        assertThat(lock.unlocked.value).isFalse()
        assertThat(lock.verify("4821")).isEqualTo(PinResult.Ok)
        assertThat(lock.unlocked.value).isTrue()
        lock.lock()
        assertThat(lock.verify("0000")).isEqualTo(PinResult.Wrong(4)) // the earlier wrong tries were forgotten
    }

    @Test
    fun `five wrong tries lock out for 30 seconds, then twice as long each time`() = runTest {
        val lock = lock()
        lock.setPin("4821")
        lock.lock()
        repeat(4) { lock.verify("0000") }
        assertThat(lock.verify("0000")).isEqualTo(PinResult.LockedOut(currentTime + 30 * second))

        // During the lockout even the right PIN is refused, and the refusal is not a try.
        advanceTimeBy(29 * second)
        assertThat(lock.verify("4821")).isEqualTo(PinResult.LockedOut(30 * second))
        assertThat(lock.unlocked.value).isFalse()

        advanceTimeBy(2 * second)
        repeat(4) { lock.verify("0000") }
        assertThat(lock.verify("0000")).isEqualTo(PinResult.LockedOut(currentTime + 60 * second))
        advanceTimeBy(61 * second)
        assertThat(lock.verify("4821")).isEqualTo(PinResult.Ok)
    }

    @Test
    fun `the lockout schedule doubles and is capped at a day`() {
        assertThat((0..4).map { ParentalLock.lockoutMs(it) }).containsExactly(0L, 0L, 0L, 0L, 0L)
        assertThat(ParentalLock.lockoutMs(5)).isEqualTo(30 * second)
        assertThat(ParentalLock.lockoutMs(7)).isEqualTo(0L)
        assertThat(ParentalLock.lockoutMs(10)).isEqualTo(60 * second)
        assertThat(ParentalLock.lockoutMs(15)).isEqualTo(120 * second)
        assertThat(ParentalLock.lockoutMs(Int.MAX_VALUE - Int.MAX_VALUE % 5)).isEqualTo(TimeUnit.DAYS.toMillis(1))
    }

    @Test
    fun `a lockout survives a restart, and a new process starts locked`() = runTest {
        val store = store()
        val before = lock(store)
        before.setPin("4821")
        repeat(5) { before.verify("0000") }

        val after = lock(store) // the app was killed and started again
        after.load()
        assertThat(after.unlocked.value).isFalse()
        assertThat(after.verify("4821")).isInstanceOf(PinResult.LockedOut::class.java)
        advanceTimeBy(31 * second)
        assertThat(after.verify("4821")).isEqualTo(PinResult.Ok)
        assertThat(after.unlocked.value).isTrue()
    }

    @Test
    fun `nothing is enforced without a PIN, and nothing is unlocked before it is known`() = runTest {
        val lock = lock()
        assertThat(lock.unlocked.value).isFalse() // not loaded yet: the safe answer
        assertThat(lock.unlockedNow()).isTrue() // no PIN set
        lock.setPin("4821")
        lock.lock()
        assertThat(lock.unlockedNow()).isFalse()
        lock.clear() // Delete all data
        assertThat(lock.unlockedNow()).isTrue()
        assertThat(lock.verify("anything")).isEqualTo(PinResult.Ok)
    }

    @Test
    fun `it relocks five minutes after the app went to the background`() = runTest {
        val lock = lock()
        lock.setPin("4821")
        lock.onBackground()
        advanceTimeBy(5 * minute - 1)
        assertThat(lock.unlocked.value).isTrue()
        advanceTimeBy(2)
        assertThat(lock.unlocked.value).isFalse()
    }

    @Test
    fun `coming back in time keeps it unlocked, and the timer starts over next time`() = runTest {
        val lock = lock()
        lock.setPin("4821")
        lock.onBackground()
        advanceTimeBy(4 * minute)
        lock.onForeground()
        advanceTimeBy(10 * minute)
        assertThat(lock.unlocked.value).isTrue() // in the foreground the whole time

        lock.onBackground()
        advanceTimeBy(3 * minute)
        lock.onForeground()
        lock.onBackground()
        advanceTimeBy(3 * minute)
        assertThat(lock.unlocked.value).isTrue() // 3 minutes away twice is not 5 in a row
    }

    @Test
    fun `a sleeping device cannot keep it open past the deadline`() = runTest {
        // The timer never runs (the device slept), but the clock moved on: every read and the return to the app check the deadline.
        var clock = 0L
        val lock = ParentalLock(store(), backgroundScope, { clock }, iterations = 300)
        lock.setPin("4821")
        lock.onBackground()
        clock = 6 * minute
        assertThat(lock.unlocked.value).isTrue() // nobody has looked yet
        assertThat(lock.unlockedNow()).isFalse()

        assertThat(lock.verify("4821")).isEqualTo(PinResult.Ok)
        lock.onBackground()
        clock += 6 * minute
        lock.onForeground()
        assertThat(lock.unlocked.value).isFalse()
    }
}
