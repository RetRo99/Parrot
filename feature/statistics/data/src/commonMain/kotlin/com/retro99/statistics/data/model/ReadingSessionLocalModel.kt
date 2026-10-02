package com.retro99.statistics.data.model

import com.retro99.books.domain.model.BookType
import com.retro99.database.api.statistics.ReadingSessionEntity
import com.retro99.statistics.domain.model.ReadingSessionDomainModel

/**
 * Local model for reading sessions that implements the database entity interface.
 */
data class ReadingSessionLocalModel(
    override val id: Long,
    override val bookUuid: String,
    override val bookTitle: String,
    override val bookType: String,
    override val startTime: Long,
    override val endTime: Long,
    override val durationMs: Long,
    override val pagesRead: Int?,
    override val startProgression: Double?,
    override val endProgression: Double?,
    override val readingSpeedWpm: Int?,
    override val recapSessionId: String? = null,
) : ReadingSessionEntity

private const val DEFAULT_SESSION_READING_SPEED_WPM = 250

fun ReadingSessionLocalModel.toDomain(): ReadingSessionDomainModel {
    return ReadingSessionDomainModel(
        id = id,
        bookUuid = bookUuid,
        bookTitle = bookTitle,
        bookType = BookType.fromValue(bookType),
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMs,
        pagesRead = pagesRead,
        startProgression = startProgression,
        endProgression = endProgression,
        readingSpeedWpm = readingSpeedWpm ?: DEFAULT_SESSION_READING_SPEED_WPM,
        recapSessionId = recapSessionId,
    )
}

fun ReadingSessionDomainModel.toLocal(): ReadingSessionLocalModel {
    return ReadingSessionLocalModel(
        id = id,
        bookUuid = bookUuid,
        bookTitle = bookTitle,
        bookType = bookType.value,
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMs,
        pagesRead = pagesRead,
        startProgression = startProgression,
        endProgression = endProgression,
        readingSpeedWpm = readingSpeedWpm,
        recapSessionId = recapSessionId,
    )
}

fun ReadingSessionEntity.toDomain(): ReadingSessionDomainModel {
    return ReadingSessionDomainModel(
        id = id,
        bookUuid = bookUuid,
        bookTitle = bookTitle,
        bookType = BookType.fromValue(bookType),
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMs,
        pagesRead = pagesRead,
        startProgression = startProgression,
        endProgression = endProgression,
        readingSpeedWpm = readingSpeedWpm ?: DEFAULT_SESSION_READING_SPEED_WPM,
        recapSessionId = recapSessionId,
    )
}

