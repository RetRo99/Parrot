package com.retro99.reader.ui.reader

/** Allows one terminal outcome per last-book launch attempt, including retries. */
internal class LastBookLaunchOutcomeGate {
    private var resolved = false

    fun beginAttempt() {
        resolved = false
    }

    fun tryResolve(): Boolean {
        if (resolved) return false
        resolved = true
        return true
    }
}
