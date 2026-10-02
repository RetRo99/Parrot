package com.retro99.reader.ui.tts

/**
 * Which sentence the player is really playing, and whether it played from
 * its start, so only sentences heard in full count as finished. The engine
 * moves its own current index ahead of the player (while it synthesises a
 * seek or skip target), so it can't answer this itself. Not thread-safe.
 */
internal class TtsHeardSentenceTracker(
    private val startToleranceMs: Long = START_TOLERANCE_MS,
) {
    private var playingIndex = -1
    private var fromStart = false

    /** A new playlist starts at [index], [progress] (0..1) into the item. */
    fun onPlaylistStarted(index: Int, progress: Double) {
        playingIndex = index
        fromStart = progress <= 0.0
    }

    /**
     * The player moved to [index]. [auto] means the previous item played
     * out; returns that item's index when it was heard in full.
     */
    fun onTransition(index: Int, auto: Boolean): Int? {
        val finished = playingIndex.takeIf { auto && it >= 0 && it != index && fromStart }
        playingIndex = index
        // An item reached by playing on starts at zero; a seek sets its own.
        if (auto) fromStart = true
        return finished
    }

    /** A seek landed [positionMs] into the playing item. */
    fun onSeek(positionMs: Long) {
        fromStart = positionMs <= startToleranceMs
    }

    /** The last queued item played out; its index when heard in full. */
    fun onEnded(): Int? {
        val finished = playingIndex.takeIf { it >= 0 && fromStart }
        // ENDED can repeat for the same item; count it once.
        fromStart = false
        return finished
    }

    fun reset() {
        playingIndex = -1
        fromStart = false
    }

    private companion object {
        /** A seek this close to the start still hears the whole sentence. */
        const val START_TOLERANCE_MS = 250L
    }
}
