package com.retro99.database.implementation.dao.statistics

import com.retro99.database.api.statistics.BookReadingStatsEntity
import com.retro99.database.api.statistics.DailyReadingTimeEntity
import com.retro99.database.api.statistics.ReadingSessionEntity
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Reading_session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * SQLDelight DAO for reading_session table operations.
 */
internal class ReadingSessionSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val queries get() = databaseManager.getDatabase().readingSessionQueries

    suspend fun insertSession(session: ReadingSessionEntity) {
        withContext(Dispatchers.IO) {
            queries.insertSession(
                book_uuid = session.bookUuid,
                book_title = session.bookTitle,
                book_type = session.bookType,
                start_time = session.startTime,
                end_time = session.endTime,
                duration_ms = session.durationMs,
                pages_read = session.pagesRead?.toLong(),
                start_progression = session.startProgression,
                end_progression = session.endProgression,
                reading_speed_wpm = session.readingSpeedWpm?.toLong(),
                recap_session_id = session.recapSessionId,
            )
        }
    }

    suspend fun getAllSessions(): List<ReadingSessionEntity> {
        return withContext(Dispatchers.IO) {
            queries.getAllSessions().executeAsList().map { row ->
                ReadingSessionSqlDelightEntity(
                    id = row.id,
                    bookUuid = row.book_uuid,
                    bookTitle = row.book_title,
                    bookType = row.book_type,
                    startTime = row.start_time,
                    endTime = row.end_time,
                    durationMs = row.duration_ms,
                    pagesRead = row.pages_read?.toInt(),
                    startProgression = row.start_progression,
                    endProgression = row.end_progression,
                    readingSpeedWpm = row.reading_speed_wpm?.toInt(),
                    recapSessionId = row.recap_session_id,
                )
            }
        }
    }

    suspend fun getSessionsByBookUuid(bookUuid: String): List<ReadingSessionEntity> {
        return withContext(Dispatchers.IO) {
            queries.getSessionsByBookUuid(bookUuid).executeAsList().map { row ->
                ReadingSessionSqlDelightEntity(
                    id = row.id,
                    bookUuid = row.book_uuid,
                    bookTitle = row.book_title,
                    bookType = row.book_type,
                    startTime = row.start_time,
                    endTime = row.end_time,
                    durationMs = row.duration_ms,
                    pagesRead = row.pages_read?.toInt(),
                    startProgression = row.start_progression,
                    endProgression = row.end_progression,
                    readingSpeedWpm = row.reading_speed_wpm?.toInt(),
                    recapSessionId = row.recap_session_id,
                )
            }
        }
    }

    suspend fun getSessionsInDateRange(startTime: Long, endTime: Long): List<ReadingSessionEntity> {
        return withContext(Dispatchers.IO) {
            queries.getSessionsInDateRange(startTime, endTime).executeAsList().map { row ->
                ReadingSessionSqlDelightEntity(
                    id = row.id,
                    bookUuid = row.book_uuid,
                    bookTitle = row.book_title,
                    bookType = row.book_type,
                    startTime = row.start_time,
                    endTime = row.end_time,
                    durationMs = row.duration_ms,
                    pagesRead = row.pages_read?.toInt(),
                    startProgression = row.start_progression,
                    endProgression = row.end_progression,
                    readingSpeedWpm = row.reading_speed_wpm?.toInt(),
                    recapSessionId = row.recap_session_id,
                )
            }
        }
    }

    suspend fun getTotalReadingTimeMs(): Long {
        return withContext(Dispatchers.IO) {
            queries.getTotalReadingTimeMs().executeAsOne()
        }
    }

    suspend fun getTotalReadingTimeMsInDateRange(startTime: Long, endTime: Long): Long {
        return withContext(Dispatchers.IO) {
            queries.getTotalReadingTimeMsInDateRange(startTime, endTime).executeAsOne()
        }
    }

    suspend fun getSessionCountInDateRange(startTime: Long, endTime: Long): Long {
        return withContext(Dispatchers.IO) {
            queries.getSessionCountInDateRange(startTime, endTime).executeAsOne()
        }
    }

    suspend fun getDistinctBooksReadInDateRange(startTime: Long, endTime: Long): Long {
        return withContext(Dispatchers.IO) {
            queries.getDistinctBooksReadInDateRange(startTime, endTime).executeAsOne()
        }
    }

    suspend fun getRecentSessions(limit: Int): List<ReadingSessionEntity> {
        return withContext(Dispatchers.IO) {
            queries.getRecentSessions(limit.toLong()).executeAsList().map { row ->
                ReadingSessionSqlDelightEntity(
                    id = row.id,
                    bookUuid = row.book_uuid,
                    bookTitle = row.book_title,
                    bookType = row.book_type,
                    startTime = row.start_time,
                    endTime = row.end_time,
                    durationMs = row.duration_ms,
                    pagesRead = row.pages_read?.toInt(),
                    startProgression = row.start_progression,
                    endProgression = row.end_progression,
                    readingSpeedWpm = row.reading_speed_wpm?.toInt(),
                    recapSessionId = row.recap_session_id,
                )
            }
        }
    }

    suspend fun getDailyReadingTime(sinceTimestamp: Long): List<DailyReadingTimeEntity> {
        return withContext(Dispatchers.IO) {
            queries.getDailyReadingTime(sinceTimestamp).executeAsList().map { row ->
                DailyReadingTimeSqlDelightEntity(
                    dayStart = row.day_start,
                    totalDurationMs = row.total_duration_ms ?: 0L,
                )
            }
        }
    }

    suspend fun getReadingTimeByBookType(startTime: Long, endTime: Long): Map<String, Long> {
        return withContext(Dispatchers.IO) {
            queries.getReadingTimeByBookType(startTime, endTime).executeAsList()
                .associate { row -> row.book_type to (row.total_duration_ms ?: 0L) }
        }
    }

    suspend fun getMostReadBooks(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<BookReadingStatsEntity> {
        return withContext(Dispatchers.IO) {
            queries.getMostReadBooks(startTime, endTime, limit.toLong()).executeAsList()
                .map { row ->
                    BookReadingStatsSqlDelightEntity(
                        bookUuid = row.book_uuid,
                        bookTitle = row.book_title,
                        totalDurationMs = row.total_duration_ms ?: 0L,
                        sessionCount = row.session_count,
                    )
                }
        }
    }

    suspend fun getReadingDays(sinceTimestamp: Long): List<Long> {
        return withContext(Dispatchers.IO) {
            queries.getReadingDays(sinceTimestamp).executeAsList()
        }
    }

    suspend fun getSessionsAfterId(afterId: Long, limit: Int): List<ReadingSessionEntity> {
        return withContext(Dispatchers.IO) {
            queries.getSessionsAfterId(afterId, limit.toLong()).executeAsList().map { row ->
                row.toEntity()
            }
        }
    }

    suspend fun getSessionByNaturalKey(
        bookUuid: String,
        bookType: String,
        startTime: Long,
        endTime: Long,
        durationMs: Long,
    ): ReadingSessionEntity? {
        return withContext(Dispatchers.IO) {
            queries.getSessionByNaturalKey(bookUuid, bookType, startTime, endTime, durationMs)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun deleteSession(id: Long) {
        withContext(Dispatchers.IO) {
            queries.transaction {
                queries.deleteRecapOfSession(id)
                queries.deleteSession(id)
            }
        }
    }

    suspend fun deleteAllSessions() {
        withContext(Dispatchers.IO) {
            queries.transaction {
                queries.deleteLinkedRecaps()
                queries.deleteAllSessions()
            }
        }
    }
}

private fun Reading_session.toEntity(): ReadingSessionEntity {
    return ReadingSessionSqlDelightEntity(
        id = id,
        bookUuid = book_uuid,
        bookTitle = book_title,
        bookType = book_type,
        startTime = start_time,
        endTime = end_time,
        durationMs = duration_ms,
        pagesRead = pages_read?.toInt(),
        startProgression = start_progression,
        endProgression = end_progression,
        readingSpeedWpm = reading_speed_wpm?.toInt(),
        recapSessionId = recap_session_id,
    )
}

