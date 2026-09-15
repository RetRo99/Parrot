package com.retro99.reader.ui.navigator

import com.retro99.base.nowMillis

/**
 * Recognizes two consecutive taps on the same sentence within the configured timeout.
 */
internal class SentenceDoubleTapRecognizer(
    private val currentTimeMillis: () -> Long = ::nowMillis,
) {

    private var lastTapTimeMs: Long = 0L
    private var lastTapFragmentId: String? = null

    fun registerTap(
        fragmentId: String,
        timeoutMs: Int,
    ): Boolean {
        val currentTimeMs = currentTimeMillis()
        val elapsedMs = currentTimeMs - lastTapTimeMs
        val isDoubleTap = fragmentId.isNotEmpty() &&
                fragmentId == lastTapFragmentId &&
                elapsedMs >= 0L &&
                elapsedMs < timeoutMs

        if (isDoubleTap || fragmentId.isEmpty()) {
            reset()
        } else {
            lastTapTimeMs = currentTimeMs
            lastTapFragmentId = fragmentId
        }

        return isDoubleTap
    }

    fun reset() {
        lastTapTimeMs = 0L
        lastTapFragmentId = null
    }
}
