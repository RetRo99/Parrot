package com.retro99.reader.data.model

import com.retro99.database.api.books.PositionEntity
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.TextAnchor

data class PositionLocalModel(
    override val bookUuid: String,
    override val localGeneration: Long = 0L,
    override val remoteRevision: Long? = null,
    override val timestamp: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val locatorHref: String?,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val locatorTarget: Int?,
    override val cssSelector: String? = null,
    override val audioTimestampMs: Long?,
    override val chapterIndex: Int?,
    override val progression: Double?,
    override val totalChapters: Int?,
    override val totalDurationMs: Long?,
    override val totalProgression: Double?,
    override val position: Int?,
    override val origin: String = PositionEntity.ORIGIN_USER,
    override val observedAt: String? = null,
    override val textAnchor: String? = null,
) : PositionEntity

fun PositionLocalModel.toDomain(serverId: String): PositionDomainModel {
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
        cssSelector = cssSelector,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
        origin = PositionOrigin.fromValue(origin),
        observedAt = observedAt,
        textAnchor = TextAnchor.fromJson(textAnchor),
    )
}

fun PositionDomainModel.toLocal(): PositionLocalModel {
    // Note: serverId is not stored locally - it's passed through the call chain
    return PositionLocalModel(
        bookUuid = bookUuid,
        remoteRevision = null,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        locatorTarget = locatorTarget,
        cssSelector = cssSelector,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
        origin = origin.value,
        observedAt = observedAt,
        textAnchor = textAnchor?.toJson(),
    )
}

fun PositionEntity.toLocalModel(): PositionLocalModel {
    return PositionLocalModel(
        bookUuid = bookUuid,
        localGeneration = localGeneration,
        remoteRevision = remoteRevision,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        locatorTarget = locatorTarget,
        cssSelector = cssSelector,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
        origin = origin,
        observedAt = observedAt,
        textAnchor = textAnchor,
    )
}
