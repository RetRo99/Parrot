package com.retro99.statistics.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.map
import com.retro99.base.nowMillis
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.statistics.data.source.StatisticsLocalSource
import com.retro99.statistics.domain.StatisticsRepository
import com.retro99.statistics.domain.StatisticsSessionCalendar
import com.retro99.statistics.domain.model.BookReadingStatsDomainModel
import com.retro99.statistics.domain.model.DailyReadingTimeDomainModel
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.ReadingStatisticsDomainModel
import com.retro99.statistics.domain.model.ReadingStreakDomainModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.collections.mapKeys
import kotlin.time.Instant

@Single(binds = [StatisticsRepository::class])
internal class StatisticsDataRepository(
    @Provided private val localSource: StatisticsLocalSource,
) : StatisticsRepository {

    companion object {
        private const val DAYS_IN_WEEK = 7
        private const val DAYS_FOR_CHART = 30
        private const val TOP_BOOKS_LIMIT = 5
    }

    override suspend fun saveReadingSession(
        session: ReadingSessionDomainModel,
    ): CompletableResult {
        return localSource.insertSession(session)
    }

    override suspend fun getAllSessions(): AppResult<List<ReadingSessionDomainModel>> {
        return localSource.getAllSessions()
    }

    override suspend fun getSessionsByBook(
        bookUuid: String,
    ): AppResult<List<ReadingSessionDomainModel>> {
        return localSource.getSessionsByBookUuid(bookUuid)
    }

    override fun getReadingStatistics(): Flow<AppResult<ReadingStatisticsDomainModel>> = flow {
        suspend fun <T> valueOrEmitError(result: AppResult<T>): T? = when (
            val outcome = result.fold(
                success = { StatisticsQueryOutcome.Value(it) },
                failure = { StatisticsQueryOutcome.Failure(it) },
            )
        ) {
            is StatisticsQueryOutcome.Value -> outcome.value
            is StatisticsQueryOutcome.Failure -> {
                emit(Err(outcome.error))
                null
            }
        }

        val now = nowMillis()
        val todayStart = getStartOfDay(now)
        val weekStart = getPeriodStart(now, DAYS_IN_WEEK, DateTimeUnit.DAY)
        val monthStart = getPeriodStart(now, 1, DateTimeUnit.MONTH)

        val totalTime = valueOrEmitError(localSource.getTotalReadingTimeMs()) ?: return@flow
        val todayTime = valueOrEmitError(
            localSource.getTotalReadingTimeMsInDateRange(todayStart, now),
        ) ?: return@flow
        val weekTime = valueOrEmitError(
            localSource.getTotalReadingTimeMsInDateRange(weekStart, now),
        ) ?: return@flow
        val monthTime = valueOrEmitError(
            localSource.getTotalReadingTimeMsInDateRange(monthStart, now),
        ) ?: return@flow
        val totalSessions = valueOrEmitError(localSource.getSessionCountInDateRange(0, now))
            ?: return@flow
        val totalBooks = valueOrEmitError(localSource.getDistinctBooksReadInDateRange(0, now))
            ?: return@flow
        val dailyReadingTime = valueOrEmitError(getDailyReadingTime(DAYS_FOR_CHART))
            ?: return@flow
        val mostReadBooks = valueOrEmitError(localSource.getMostReadBooks(0, now, TOP_BOOKS_LIMIT))
            ?: return@flow
        val readingTimeByType = valueOrEmitError(localSource.getReadingTimeByBookType(0, now))
            ?: return@flow
        val streak = valueOrEmitError(calculateStreak(now)) ?: return@flow

        val statistics = ReadingStatisticsDomainModel(
            totalReadingTimeMs = totalTime,
            todayReadingTimeMs = todayTime,
            weekReadingTimeMs = weekTime,
            monthReadingTimeMs = monthTime,
            totalSessions = totalSessions,
            totalBooksRead = totalBooks,
            currentStreak = streak.currentStreak,
            longestStreak = streak.longestStreak,
            currentStreakDays = streak.currentStreakDays,
            longestStreakDays = streak.longestStreakDays,
            dailyReadingTime = dailyReadingTime,
            mostReadBooks = mostReadBooks,
            readingTimeByType = readingTimeByType.mapKeys { BookType.fromValue(it.key) },
        )

        emit(Ok(statistics))
    }

    override suspend fun getTotalReadingTimeMs(): AppResult<Long> {
        return localSource.getTotalReadingTimeMs()
    }

    override suspend fun getTodayReadingTimeMs(): AppResult<Long> {
        val now = nowMillis()
        val todayStart = getStartOfDay(now)
        return localSource.getTotalReadingTimeMsInDateRange(todayStart, now)
    }

    override suspend fun getWeekReadingTimeMs(): AppResult<Long> {
        val now = nowMillis()
        val weekStart = getPeriodStart(now, DAYS_IN_WEEK, DateTimeUnit.DAY)
        return localSource.getTotalReadingTimeMsInDateRange(weekStart, now)
    }

    override suspend fun getMonthReadingTimeMs(): AppResult<Long> {
        val now = nowMillis()
        val monthStart = getPeriodStart(now, 1, DateTimeUnit.MONTH)
        return localSource.getTotalReadingTimeMsInDateRange(monthStart, now)
    }

    override suspend fun getDailyReadingTime(
        days: Int,
    ): AppResult<List<DailyReadingTimeDomainModel>> {
        val now = nowMillis()
        require(days >= 0)
        val sinceTimestamp = getPeriodStart(now, days, DateTimeUnit.DAY)
        val timeZone = TimeZone.currentSystemDefault()
        return localSource.getAllSessions().map { sessions ->
            StatisticsSessionCalendar.dailyTime(sessions, sinceTimestamp, timeZone)
        }
    }

    override suspend fun getMostReadBooks(
        limit: Int,
    ): AppResult<List<BookReadingStatsDomainModel>> {
        val now = nowMillis()
        return localSource.getMostReadBooks(0, now, limit)
    }

    override suspend fun getMostReadBooksInDateRange(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): AppResult<List<BookReadingStatsDomainModel>> {
        return localSource.getMostReadBooks(startTime, endTime, limit)
    }

    override suspend fun getReadingTimeByType(): AppResult<Map<BookType, Long>> {
        val now = nowMillis()
        return localSource.getReadingTimeByBookType(0, now).map { typeMap ->
            typeMap.mapKeys { BookType.fromValue(it.key) }
        }
    }

    override suspend fun getReadingStreak(): AppResult<ReadingStreakDomainModel> {
        val now = nowMillis()
        return calculateStreak(now)
    }

    override suspend fun clearAllSessions(): CompletableResult {
        return localSource.deleteAllSessions()
    }

    private suspend fun calculateStreak(now: Long): AppResult<ReadingStreakDomainModel> {
        val timeZone = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(timeZone).date
        return localSource.getAllSessions().map { sessions ->
            StatisticsSessionCalendar.streak(sessions, today, timeZone)
        }
    }

    private fun getStartOfDay(timestamp: Long): Long {
        return getPeriodStart(timestamp, 0, DateTimeUnit.DAY)
    }

    private fun getPeriodStart(timestamp: Long, amount: Int, unit: DateTimeUnit.DateBased): Long {
        val timeZone = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(timeZone).date
            .minus(amount, unit).atStartOfDayIn(timeZone).toEpochMilliseconds()
    }
}

private sealed interface StatisticsQueryOutcome<out T> {
    data class Value<T>(val value: T) : StatisticsQueryOutcome<T>
    data class Failure(val error: AppError) : StatisticsQueryOutcome<Nothing>
}
