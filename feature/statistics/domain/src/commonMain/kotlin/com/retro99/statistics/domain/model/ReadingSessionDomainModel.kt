package com.retro99.statistics.domain.model

import com.retro99.books.domain.model.BookType

/**
 * Represents a single reading session.
 * Tracks when a user reads a book, for how long, and their progress.
 */
data class ReadingSessionDomainModel(
    val id: Long,
    val bookUuid: String,
    val bookTitle: String,
    val bookType: BookType,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val pagesRead: Int?,
    val startProgression: Double?,
    val endProgression: Double?,
    val readingSpeedWpm: Int,
    /** Recap of the same reader session, if one was recorded. Local only. */
    val recapSessionId: String? = null,
)

