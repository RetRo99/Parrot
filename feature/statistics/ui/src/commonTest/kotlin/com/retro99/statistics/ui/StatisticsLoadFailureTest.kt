package com.retro99.statistics.ui

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.statistics.domain.StatisticsRepository
import com.retro99.statistics.domain.model.BookReadingStatsDomainModel
import com.retro99.statistics.domain.model.DailyReadingTimeDomainModel
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.model.ReadingStatisticsDomainModel
import com.retro99.statistics.domain.model.ReadingStreakDomainModel
import com.retro99.statistics.domain.usecase.GetAllBooksReadUseCase
import com.retro99.statistics.domain.usecase.GetBooksForPeriodUseCase
import com.retro99.statistics.domain.usecase.GetReadingStatisticsUseCase
import com.retro99.statistics.domain.usecase.GetRecentSessionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsLoadFailureTest {

    @Test
    fun databaseFailureShowsRetryableErrorAndSuccessfulRetryClearsIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val failure = AppError.DatabaseError(IllegalStateException("synthetic database failure"))
            val repository = FakeStatisticsRepository(
                statisticsResults = mutableListOf(
                    Err(failure),
                    Ok(emptyStatistics()),
                ),
            )
            val analytics = RecordingAnalytics()
            val viewModel = createViewModel(repository, analytics)

            advanceUntilIdle()

            val failedState = viewModel.currentViewState()
            assertFalse(failedState.isLoading)
            assertNull(failedState.statistics)
            assertIs<AppError.DatabaseError>(failedState.error)
            assertEquals(
                listOf(
                    "statistics_load_attempted",
                    "statistics_load_failed",
                ),
                analytics.events.map(AnalyticsEvent::name),
            )
            assertEquals(1, analytics.exceptionContexts.size)
            assertEquals("failed", analytics.exceptionContexts.single().outcome)
            assertEquals("database_error", analytics.exceptionContexts.single().reasonCode)
            assertEquals(2, analytics.breadcrumbs.size)
            assertEquals(
                analytics.breadcrumbs.first().correlationId,
                analytics.breadcrumbs.last().correlationId,
            )

            viewModel.onIntent(StatisticsIntent.OnRefresh)
            advanceUntilIdle()

            val recoveredState = viewModel.currentViewState()
            assertFalse(recoveredState.isLoading)
            assertNotNull(recoveredState.statistics)
            assertNull(recoveredState.error)
            assertEquals(
                "statistics_load_attempted",
                analytics.events[2].name,
            )
            assertEquals(
                mapOf(
                    "screen" to "statistics",
                    "action" to "retry_statistics",
                    "operation" to "statistics_load",
                    "stage" to "started",
                    "outcome" to "started",
                    "is_retry" to true,
                ),
                analytics.events[2].parameters,
            )
            assertEquals("statistics_load_succeeded", analytics.events[3].name)
            assertEquals("succeeded", analytics.breadcrumbs.last().outcome)
            assertEquals(1, analytics.exceptionContexts.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        repository: StatisticsRepository,
        analytics: Analytics,
    ) = StatisticsViewModel(
        onBack = {},
        getReadingStatisticsUseCase = GetReadingStatisticsUseCase(repository),
        getBooksForPeriodUseCase = GetBooksForPeriodUseCase(repository),
        getAllBooksReadUseCase = GetAllBooksReadUseCase(repository),
        getRecentSessionsUseCase = GetRecentSessionsUseCase(repository),
        analytics = analytics,
    )
}

private class RecordingAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()
    val breadcrumbs = mutableListOf<DiagnosticContext>()
    val exceptionContexts = mutableListOf<DiagnosticContext>()

    override fun logException(throwable: Throwable, message: String?) = Unit

    override fun logException(throwable: Throwable, context: DiagnosticContext) {
        exceptionContexts += context
    }

    override fun logBreadcrumb(context: DiagnosticContext) {
        breadcrumbs += context
    }

    override fun logEvent(event: AnalyticsEvent) {
        events += event
    }

    override fun setUserId(userId: String?) = Unit
}

private class FakeStatisticsRepository(
    private val statisticsResults: MutableList<AppResult<ReadingStatisticsDomainModel>>,
) : StatisticsRepository {
    override suspend fun saveReadingSession(session: ReadingSessionDomainModel): CompletableResult = Ok(Unit)

    override suspend fun getAllSessions(): AppResult<List<ReadingSessionDomainModel>> = Ok(emptyList())

    override suspend fun getSessionsByBook(bookUuid: String): AppResult<List<ReadingSessionDomainModel>> =
        Ok(emptyList())

    override fun getReadingStatistics(): Flow<AppResult<ReadingStatisticsDomainModel>> =
        flowOf(statisticsResults.removeAt(0))

    override suspend fun getTotalReadingTimeMs(): AppResult<Long> = Ok(0L)

    override suspend fun getTodayReadingTimeMs(): AppResult<Long> = Ok(0L)

    override suspend fun getWeekReadingTimeMs(): AppResult<Long> = Ok(0L)

    override suspend fun getMonthReadingTimeMs(): AppResult<Long> = Ok(0L)

    override suspend fun getDailyReadingTime(days: Int): AppResult<List<DailyReadingTimeDomainModel>> =
        Ok(emptyList())

    override suspend fun getMostReadBooks(limit: Int): AppResult<List<BookReadingStatsDomainModel>> =
        Ok(emptyList())

    override suspend fun getMostReadBooksInDateRange(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): AppResult<List<BookReadingStatsDomainModel>> = Ok(emptyList())

    override suspend fun getReadingTimeByType(): AppResult<Map<BookType, Long>> = Ok(emptyMap())

    override suspend fun getReadingStreak(): AppResult<ReadingStreakDomainModel> =
        Ok(ReadingStreakDomainModel(0, 0, null))

    override suspend fun clearAllSessions(): CompletableResult = Ok(Unit)
}

private fun emptyStatistics() = ReadingStatisticsDomainModel(
    totalReadingTimeMs = 0,
    todayReadingTimeMs = 0,
    weekReadingTimeMs = 0,
    monthReadingTimeMs = 0,
    totalSessions = 0,
    totalBooksRead = 0,
    currentStreak = 0,
    longestStreak = 0,
    currentStreakDays = emptyList(),
    longestStreakDays = emptyList(),
    dailyReadingTime = emptyList(),
    mostReadBooks = emptyList(),
    readingTimeByType = emptyMap(),
)
