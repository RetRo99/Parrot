package com.retro99.database.api.statistics

import com.retro99.database.api.DataClearable

/**
 * Database interface for reading session operations.
 * Used for tracking and querying reading statistics.
 */
interface ReadingSessionDatabase : DataClearable {

    /**
     * Inserts a new reading session.
     */
    suspend fun insertSession(session: ReadingSessionEntity)

    /**
     * Gets all reading sessions ordered by start time descending.
     */
    suspend fun getAllSessions(): List<ReadingSessionEntity>

    /**
     * Gets reading sessions for a specific book.
     */
    suspend fun getSessionsByBookUuid(bookUuid: String): List<ReadingSessionEntity>

    /**
     * Gets reading sessions within a date range.
     */
    suspend fun getSessionsInDateRange(startTime: Long, endTime: Long): List<ReadingSessionEntity>

    /**
     * Gets total reading time in milliseconds.
     */
    suspend fun getTotalReadingTimeMs(): Long

    /**
     * Gets total reading time in milliseconds within a date range.
     */
    suspend fun getTotalReadingTimeMsInDateRange(startTime: Long, endTime: Long): Long

    /**
     * Gets the count of reading sessions within a date range.
     */
    suspend fun getSessionCountInDateRange(startTime: Long, endTime: Long): Long

    /**
     * Gets the count of distinct books read within a date range.
     */
    suspend fun getDistinctBooksReadInDateRange(startTime: Long, endTime: Long): Long

    /**
     * Gets the most recent reading sessions.
     */
    suspend fun getRecentSessions(limit: Int): List<ReadingSessionEntity>

    /**
     * Gets daily reading time aggregated by day since a given timestamp.
     */
    suspend fun getDailyReadingTime(sinceTimestamp: Long): List<DailyReadingTimeEntity>

    /**
     * Gets reading time grouped by book type within a date range.
     */
    suspend fun getReadingTimeByBookType(
        startTime: Long,
        endTime: Long,
    ): Map<String, Long>

    /**
     * Gets the most read books within a date range.
     */
    suspend fun getMostReadBooks(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<BookReadingStatsEntity>

    /**
     * Gets the day numbers (days since epoch) that have reading activity.
     * Used for calculating reading streaks.
     */
    suspend fun getReadingDays(sinceTimestamp: Long): List<Long>

    /**
     * Gets reading sessions with a local id greater than [afterId], ordered by id ascending.
     * Used to sweep new sessions into cloud sync without a schema marker column.
     */
    suspend fun getSessionsAfterId(afterId: Long, limit: Int): List<ReadingSessionEntity>

    /**
     * Finds a reading session by its natural identity (book, type and time window).
     * Used to deduplicate sessions applied from cloud sync.
     */
    suspend fun getSessionByNaturalKey(
        bookUuid: String,
        bookType: String,
        startTime: Long,
        endTime: Long,
        durationMs: Long,
    ): ReadingSessionEntity?

    /**
     * Deletes a reading session by ID, with its linked recap.
     */
    suspend fun deleteSession(id: Long)

    /**
     * Deletes all reading sessions and the recaps linked to them.
     */
    suspend fun deleteAllSessions()

    override suspend fun clearAllData() {
        deleteAllSessions()
    }
}

