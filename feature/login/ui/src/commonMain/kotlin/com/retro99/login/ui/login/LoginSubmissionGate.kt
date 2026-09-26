package com.retro99.login.ui.login

import kotlinx.coroutines.flow.MutableStateFlow

/** Atomically accepts one credentials/OAuth submit until its recoverable outcome is handled. */
internal class LoginSubmissionGate {
    private val inFlight = MutableStateFlow(false)

    fun tryStart(): Boolean = inFlight.compareAndSet(expect = false, update = true)

    fun finish() {
        inFlight.value = false
    }
}
