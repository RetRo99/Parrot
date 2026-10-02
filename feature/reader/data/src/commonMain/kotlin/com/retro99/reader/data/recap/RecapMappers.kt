package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap

internal fun SessionRecapEntity.toDomain(): SessionRecap = SessionRecap(
    sessionId = sessionId,
    serverId = serverId,
    bookId = bookUuid,
    status = RecapStatus.fromName(status),
    summary = summary,
    lastError = RecapErrorCode.fromName(lastError),
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
)
