package com.retro99.statistics.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.statistics.data.source.StatisticsLocalSource
import com.retro99.statistics.domain.model.BookReadingStatsDomainModel
import com.retro99.statistics.domain.model.DailyReadingTimeDomainModel
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.ReadingStatisticsDomainModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StatisticsDataRepositoryFailureTest {

    @Test
    fun aggregateQueryFailureIsPropagatedInsteadOfBecomingZeroStatistics() = runTest {
        val repository = StatisticsDataRepository(
            localSource = FakeStatisticsLocalSource(failAt = Query.TOTAL_TIME),
        )

        val result = repository.getReadingStatistics().first()

        result.fold(
            success = { error("Expected the aggregate failure to propagate") },
            failure = { assertIs<AppError.DatabaseError>(it) },
        )
    }

    @Test
    fun sessionQueryFailureIsPropagatedInsteadOfBecomingAnEmptyStreak() = runTest {
        val repository = StatisticsDataRepository(
            localSource = FakeStatisticsLocalSource(failAt = Query.ALL_SESSIONS),
        )

        val result = repository.getReadingStreak()

        result.fold(
            success = { error("Expected the session query failure to propagate") },
            failure = { assertIs<AppError.DatabaseError>(it) },
        )
    }

    @Test
    fun validEmptyDataStillProducesGenuineZeroStatistics() = runTest {
        val repository = StatisticsDataRepository(localSource = FakeStatisticsLocalSource())

        val result = repository.getReadingStatistics().first()

        val statistics = result.fold(
            success = { it },
            failure = { error("Expected valid empty statistics") },
        )
        assertEquals(0L, statistics.totalReadingTimeMs)
        assertEquals(0L, statistics.totalSessions)
        assertEquals(0L, statistics.totalBooksRead)
        assertEquals(0, statistics.currentStreak)
        assertEquals(0, statistics.longestStreak)
    }

    @Test
    fun localCalendarQueryFailureIsPropagatedFromTheDashboard() = runTest {
        val repository = StatisticsDataRepository(
            localSource = FakeStatisticsLocalSource(failAt = Query.ALL_SESSIONS),
        )
        repository.getReadingStatistics().first().fold(
            success = { error("Expected the session query failure to propagate") },
            failure = { assertIs<AppError.DatabaseError>(it) },
        )
    }

    @Test
    fun dailyCalendarQueryFailureIsNotReportedAsAnEmptyChart() = runTest {
        val repository = StatisticsDataRepository(
            localSource = FakeStatisticsLocalSource(failAt = Query.ALL_SESSIONS),
        )
        repository.getDailyReadingTime(30).fold(
            success = { error("Expected the session query failure to propagate") },
            failure = { assertIs<AppError.DatabaseError>(it) },
        )
    }
}

internal enum class Query {
    TOTAL_TIME,
    READING_DAYS,
    ALL_SESSIONS,
}

internal class FakeStatisticsLocalSource(
    private val failAt: Query? = null,
) : StatisticsLocalSource {

    private fun <T> result(query: Query, value: T): AppResult<T> =
        if (query == failAt) {
            Err(AppError.DatabaseError(IllegalStateException("synthetic database failure")))
        } else {
            Ok(value)
        }

    override suspend fun insertSession(session: ReadingSessionDomainModel): CompletableResult = Ok(Unit)

    override suspend fun getAllSessions(): AppResult<List<ReadingSessionDomainModel>> =
        result(Query.ALL_SESSIONS, emptyList())

    override suspend fun getSessionsByBookUuid(
        bookUuid: String,
    ): AppResult<List<ReadingSessionDomainModel>> = Ok(emptyList())

    override suspend fun getSessionsInDateRange(
        startTime: Long,
        endTime: Long,
    ): AppResult<List<ReadingSessionDomainModel>> = Ok(emptyList())

    override suspend fun getTotalReadingTimeMs(): AppResult<Long> = result(Query.TOTAL_TIME, 0L)

    override suspend fun getTotalReadingTimeMsInDateRange(
        startTime: Long,
        endTime: Long,
    ): AppResult<Long> = Ok(0L)

    override suspend fun getSessionCountInDateRange(
        startTime: Long,
        endTime: Long,
    ): AppResult<Long> = Ok(0L)

    override suspend fun getDistinctBooksReadInDateRange(
        startTime: Long,
        endTime: Long,
    ): AppResult<Long> = Ok(0L)

    override suspend fun getDailyReadingTime(
        sinceTimestamp: Long,
    ): AppResult<List<DailyReadingTimeDomainModel>> = Ok(emptyList())

    override suspend fun getReadingTimeByBookType(
        startTime: Long,
        endTime: Long,
    ): AppResult<Map<String, Long>> = Ok(emptyMap())

    override suspend fun getMostReadBooks(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): AppResult<List<BookReadingStatsDomainModel>> = Ok(emptyList())

    override suspend fun getReadingDays(sinceTimestamp: Long): AppResult<List<Long>> =
        result(Query.READING_DAYS, emptyList())

    override suspend fun deleteAllSessions(): CompletableResult = Ok(Unit)
}
