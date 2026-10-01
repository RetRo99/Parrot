package com.retro99.database.implementation.dao.books

/** `books.audio_track_durations_ms`: each file's length in ms, comma-separated. */
internal fun encodeTrackDurations(durationsMs: List<Long>?): String? =
    durationsMs?.takeIf { durations -> durations.isNotEmpty() }?.joinToString(",")

/** Null for a missing or unreadable value: a partly known list is never returned. */
internal fun decodeTrackDurations(value: String?): List<Long>? {
    if (value.isNullOrBlank()) return null
    val durations = value.split(',').map { part -> part.trim().toLongOrNull() }
    if (durations.any { duration -> duration == null || duration < 0 }) return null
    return durations.filterNotNull()
}
