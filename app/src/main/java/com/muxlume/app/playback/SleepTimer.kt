package com.muxlume.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Runs [onExpire] (pause playback) after a chosen number of minutes. Lives as long as the app process. */
class SleepTimer(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onExpire: suspend () -> Unit,
) {
    data class Active(val minutes: Int, val endsAt: Long)

    private val _state = MutableStateFlow<Active?>(null)
    val state: StateFlow<Active?> = _state
    private var job: Job? = null

    /** Starts (or restarts) the timer; a non-positive [minutes] cancels it. */
    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val active = Active(minutes, now() + TimeUnit.MINUTES.toMillis(minutes.toLong()))
        _state.value = active
        job = scope.launch {
            delay(active.endsAt - now())
            _state.value = null
            onExpire()
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = null
    }
}
