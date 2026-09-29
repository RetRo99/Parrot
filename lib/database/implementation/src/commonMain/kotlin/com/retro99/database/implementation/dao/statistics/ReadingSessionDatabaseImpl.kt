package com.retro99.database.implementation.dao.statistics

import com.retro99.database.api.statistics.BookReadingStatsEntity
import com.retro99.database.api.statistics.DailyReadingTimeEntity
import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.database.api.statistics.ReadingSessionEntity

/**
 * Implementation of ReadingSessionDatabase using SQLDelight.
 */
internal class ReadingSessionDatabaseImpl(
    private val dao: ReadingSessionSqlDelightDao,
) : ReadingSessionDatabase {

    override suspend fun insertSession(session: ReadingSessionEntity) {
        dao.insertSession(session)
    }

    override suspend fun getAllSessions(): List<ReadingSessionEntity> {
        return dao.getAllSessions()
    }

    override suspend fun getSessionsByBookUuid(bookUuid: String): List<ReadingSessionEntity> {
        return dao.getSessionsByBookUuid(bookUuid)
    }

    override suspend fun getSessionsInDateRange(
        startTime: Long,
        endTime: Long,
    ): List<ReadingSessionEntity> {
        return dao.getSessionsInDateRange(startTime, endTime)
    }

    override suspend fun getTotalReadingTimeMs(): Long {
        return dao.getTotalReadingTimeMs()
    }

    override suspend fun getTotalReadingTimeMsInDateRange(startTime: Long, endTime: Long): Long {
        return dao.getTotalReadingTimeMsInDateRange(startTime, endTime)
    }

    override suspend fun getSessionCountInDateRange(startTime: Long, endTime: Long): Long {
        return dao.getSessionCountInDateRange(startTime, endTime)
    }

    override suspend fun getDistinctBooksReadInDateRange(startTime: Long, endTime: Long): Long {
        return dao.getDistinctBooksReadInDateRange(startTime, endTime)
    }

    override suspend fun getRecentSessions(limit: Int): List<ReadingSessionEntity> {
        return dao.getRecentSessions(limit)
    }

    override suspend fun getDailyReadingTime(sinceTimestamp: Long): List<DailyReadingTimeEntity> {
        return dao.getDailyReadingTime(sinceTimestamp)
    }

    override suspend fun getReadingTimeByBookType(startTime: Long, endTime: Long): Map<String, Long> {
        return dao.getReadingTimeByBookType(startTime, endTime)
    }

    override suspend fun getMostReadBooks(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<BookReadingStatsEntity> {
        return dao.getMostReadBooks(startTime, endTime, limit)
    }

    override suspend fun getReadingDays(sinceTimestamp: Long): List<Long> {
        return dao.getReadingDays(sinceTimestamp)
    }

    override suspend fun getSessionsAfterId(
        afterId: Long,
        limit: Int,
    ): List<ReadingSessionEntity> {
        return dao.getSessionsAfterId(afterId, limit)
    }

    override suspend fun getSessionByNaturalKey(
        bookUuid: String,
        bookType: String,
        startTime: Long,
        endTime: Long,
        durationMs: Long,
    ): ReadingSessionEntity? {
        return dao.getSessionByNaturalKey(bookUuid, bookType, startTime, endTime, durationMs)
    }

    override suspend fun deleteSession(id: Long) {
        dao.deleteSession(id)
    }

    override suspend fun deleteAllSessions() {
        dao.deleteAllSessions()
    }
}

