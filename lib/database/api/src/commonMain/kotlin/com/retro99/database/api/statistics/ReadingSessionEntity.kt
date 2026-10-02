package com.retro99.database.api.statistics

/**
 * Entity representing a single reading session.
 * Tracks when a user reads a book, for how long, and their progress.
 */
interface ReadingSessionEntity {
    val id: Long
    val bookUuid: String
    val bookTitle: String
    val bookType: String
    val startTime: Long
    val endTime: Long
    val durationMs: Long
    val pagesRead: Int?
    val startProgression: Double?
    val endProgression: Double?
    val readingSpeedWpm: Int?

    /** Recap session of the same reader session; local only, never synced. */
    val recapSessionId: String?
        get() = null
}

