package com.retro99.reader.data.model

import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.ServerPosition

/**
 * Converts a ServerPosition to PositionDomainModel.
 */
fun ServerPosition.toDomain(): PositionDomainModel {
    return PositionDomainModel(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        locatorTarget = locatorTarget,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
        cssSelector = cssSelector,
        origin = origin,
        observedAt = observedAt,
        textAnchor = textAnchor,
    )
}

/**
 * Converts a PositionDomainModel to ServerPosition.
 */
fun PositionDomainModel.toServerPosition(): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        locatorTarget = locatorTarget,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
        cssSelector = cssSelector,
        origin = origin,
        observedAt = observedAt,
        textAnchor = textAnchor,
    )
}

