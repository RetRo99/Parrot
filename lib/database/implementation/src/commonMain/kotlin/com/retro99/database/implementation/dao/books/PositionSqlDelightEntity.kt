package com.retro99.database.implementation.dao.books

import com.retro99.database.api.books.PositionEntity

data class PositionSqlDelightEntity(
    override val bookUuid: String,
    override val libraryBookId: String?,
    override val localGeneration: Long = 0L,
    override val remoteRevision: Long?,
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
    override val bookTimeMs: Long? = null,
    override val ebookLocationRaw: String? = null,
) : PositionEntity
