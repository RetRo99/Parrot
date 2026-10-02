package com.retro99.database.implementation.dao.statistics

import com.retro99.database.api.statistics.ReadingSessionEntity

/**
 * SQLDelight entity for reading_session table.
 */
data class ReadingSessionSqlDelightEntity(
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

