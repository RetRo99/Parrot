package com.retro99.statistics.domain

import kotlin.time.TimeSource

/** Measures active wall time, independently of clock changes, pauses and playback speed. */
class ActiveSessionTimer(
    private val elapsedMillis: () -> Long = monotonicClock(),
) {
    private var activeSince: Long? = null
    private var accumulatedMs = 0L
    private var finished = false

    fun setActive(active: Boolean) {
        if (finished) return
        if (active && activeSince == null) activeSince = elapsedMillis()
        if (!active) {
            activeSince?.let { accumulatedMs += (elapsedMillis() - it).coerceAtLeast(0L) }
            activeSince = null
        }
    }

    /** Finishing twice cannot record the same session twice. */
    fun finish(): Long? {
        if (finished) return null
        setActive(false)
        finished = true
        return accumulatedMs
    }
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
