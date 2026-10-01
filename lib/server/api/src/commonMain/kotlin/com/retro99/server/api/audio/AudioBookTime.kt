package com.retro99.server.api.audio

/**
 * Conversions between a multi-file audiobook's per-file positions (track index plus offset in
 * that file, which the player resumes from) and the time from the start of the book (which
 * servers such as Audiobookshelf use). [trackDurationsMs] holds every file's length, in
 * playlist order; an empty list means the lengths aren't known.
 */

/**
 * Book time for [trackIndex] at [offsetMs], or null when the lengths are unknown or the track
 * isn't one of them. The offset is held inside its track.
 */
fun bookTimeMs(trackDurationsMs: List<Long>, trackIndex: Int, offsetMs: Long): Long? {
    if (trackIndex !in trackDurationsMs.indices) return null
    val before = trackDurationsMs.take(trackIndex).sum()
    return before + offsetMs.coerceIn(0L, trackDurationsMs[trackIndex])
}

/**
 * (track index, offset in it) for [bookTimeMs], clamped into the book, or null when the
 * lengths are unknown. A time exactly on a boundary belongs to the next track.
 */
fun trackPosition(trackDurationsMs: List<Long>, bookTimeMs: Long): Pair<Int, Long>? {
    if (trackDurationsMs.isEmpty()) return null
    var remaining = bookTimeMs.coerceAtLeast(0L)
    trackDurationsMs.forEachIndexed { index, duration ->
        if (remaining < duration) return index to remaining
        remaining -= duration
    }
    return trackDurationsMs.lastIndex to trackDurationsMs.last()
}

/** The whole book's length, or null when the lengths are unknown. */
fun totalDurationMs(trackDurationsMs: List<Long>): Long? =
    trackDurationsMs.takeIf { durations -> durations.isNotEmpty() }?.sum()
