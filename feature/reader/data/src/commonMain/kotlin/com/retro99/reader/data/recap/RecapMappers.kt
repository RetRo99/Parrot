package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap

internal fun SessionRecapEntity.toDomain(): SessionRecap {
    val status = RecapStatus.fromName(status)
    val lastError = RecapErrorCode.fromName(lastError)
    val failed = status == RecapStatus.FAILED_RETRYABLE || status == RecapStatus.FAILED_PERMANENT
    return SessionRecap(
        sessionId = sessionId,
        serverId = serverId,
        bookId = bookUuid,
        status = status,
        summary = summary,
        lastError = lastError,
        startPosition = RecapPosition(startHref, startProgression, startTotalProgression),
        endPosition = RecapPosition(endHref, endProgression, endTotalProgression),
        startChapter = RecapChapter(startChapterIndex, startChapterTitle),
        endChapter = RecapChapter(endChapterIndex, endChapterTitle),
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        engineId = engineId,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt,
        endedAt = endedAt,
        generatedAt = generatedAt,
        // Same rule as SessionRecapDataRepository.retry.
        canRetry = failed && excerpt != null && lastError?.isInputError != true,
    )
}
