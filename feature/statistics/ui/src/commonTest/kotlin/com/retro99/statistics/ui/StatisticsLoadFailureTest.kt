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
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
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
import com.retro99.statistics.domain.usecase.GetStatisticsOverviewUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun detailQueriesKeepSheetsRetryAndReportSeparateOutcomes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val failure = AppError.DatabaseError(IllegalStateException("synthetic detail query failure"))
            val repository = FakeStatisticsRepository(
                statisticsResults = mutableListOf(Ok(emptyStatistics())),
                periodBookResults = mutableListOf(Err(failure), Ok(emptyList())),
                allBooksReadResults = mutableListOf(Err(failure), Ok(emptyList())),
                allSessionsResults = mutableListOf(Err(failure), Ok(emptyList())),
            )
            val analytics = RecordingAnalytics()
            val viewModel = createViewModel(repository, analytics)
            advanceUntilIdle()

            viewModel.onIntent(StatisticsIntent.OnPeriodClicked(com.retro99.statistics.domain.model.StatisticsPeriod.WEEK))
            advanceUntilIdle()
            assertIs<AppError.DatabaseError>(viewModel.currentViewState().detailState?.error)
            assertFalse(viewModel.currentViewState().detailState?.isLoading ?: true)
            assertEquals("statistics_detail_load_failed", analytics.events.last().name)
            assertEquals("period_books", analytics.events.last().parameters["detail_type"])
            assertEquals("database_error", analytics.events.last().parameters["reason_code"])
            assertEquals("load_period_books", analytics.exceptionContexts.last().action)
            assertEquals("statistics_detail_load", analytics.exceptionContexts.last().operation)

            viewModel.onIntent(StatisticsIntent.OnRetryDetail)
            advanceUntilIdle()
            assertNull(viewModel.currentViewState().detailState?.error)
            assertEquals("statistics_detail_load_attempted", analytics.events[analytics.events.lastIndex - 1].name)
            assertEquals(true, analytics.events[analytics.events.lastIndex - 1].parameters["is_retry"])
            assertEquals("statistics_detail_load_succeeded", analytics.events.last().name)

            viewModel.onIntent(StatisticsIntent.OnBooksReadClicked)
            advanceUntilIdle()
            assertIs<AppError.DatabaseError>(viewModel.currentViewState().booksReadDetailState?.error)
            assertEquals("books_read", analytics.events.last().parameters["detail_type"])
            viewModel.onIntent(StatisticsIntent.OnRetryDetail)
            advanceUntilIdle()
            assertNull(viewModel.currentViewState().booksReadDetailState?.error)
            assertEquals("statistics_detail_load_succeeded", analytics.events.last().name)

            viewModel.onIntent(StatisticsIntent.OnTotalSessionsClicked)
            advanceUntilIdle()
            assertIs<AppError.DatabaseError>(viewModel.currentViewState().sessionsDetailState?.error)
            assertEquals("recent_sessions", analytics.events.last().parameters["detail_type"])
            viewModel.onIntent(StatisticsIntent.OnRetryDetail)
            advanceUntilIdle()
            assertNull(viewModel.currentViewState().sessionsDetailState?.error)
            assertEquals("statistics_detail_load_succeeded", analytics.events.last().name)

            val detailOutcomes = analytics.events.filter {
                it.name.startsWith("statistics_detail_load_")
            }
            assertEquals(12, detailOutcomes.size)
            assertEquals(3, analytics.exceptionContexts.count { it.operation == "statistics_detail_load" })
            val detailBreadcrumbs = analytics.breadcrumbs.filter {
                it.operation == "statistics_detail_load"
            }
            assertEquals(12, detailBreadcrumbs.size)
            detailBreadcrumbs.chunked(2).forEach { pair ->
                assertEquals("started", pair.first().stage)
                assertEquals("terminal", pair.last().stage)
                assertEquals(pair.first().correlationId, pair.last().correlationId)
                assertTrue(pair.first().correlationId != null)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dismissingLoadingDetailRecordsCancellationWithoutException() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeStatisticsRepository(
                statisticsResults = mutableListOf(Ok(emptyStatistics())),
                suspendSessions = true,
            )
            val analytics = RecordingAnalytics()
            val viewModel = createViewModel(repository, analytics)
            advanceUntilIdle()

            viewModel.onIntent(StatisticsIntent.OnTotalSessionsClicked)
            runCurrent()
            assertTrue(viewModel.currentViewState().sessionsDetailState?.isLoading == true)

            viewModel.onIntent(StatisticsIntent.OnDismissDetail)
            advanceUntilIdle()

            assertNull(viewModel.currentViewState().sessionsDetailState)
            val cancellations = analytics.events.filter {
                it.name == "statistics_detail_load_cancelled"
            }
            assertEquals(1, cancellations.size)
            assertEquals("detail_dismissed", cancellations.single().parameters["reason_code"])
            assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
            assertEquals("detail_dismissed", analytics.breadcrumbs.last().reasonCode)
            assertTrue(analytics.exceptionContexts.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun navigatingBackCancelsLoadingDetailAndInvokesBackOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var backCount = 0
            val repository = FakeStatisticsRepository(
                statisticsResults = mutableListOf(Ok(emptyStatistics())),
                suspendSessions = true,
            )
            val analytics = RecordingAnalytics()
            val viewModel = createViewModel(repository, analytics, onBack = { backCount++ })
            advanceUntilIdle()

            viewModel.onIntent(StatisticsIntent.OnTotalSessionsClicked)
            runCurrent()
            viewModel.onIntent(StatisticsIntent.OnBackClicked)
            advanceUntilIdle()

            assertEquals(1, backCount)
            val cancellations = analytics.events.filter {
                it.name == "statistics_detail_load_cancelled"
            }
            assertEquals(1, cancellations.size)
            assertEquals("navigation_back", cancellations.single().parameters["reason_code"])
            assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
            assertEquals("navigation_back", analytics.breadcrumbs.last().reasonCode)
            assertTrue(analytics.exceptionContexts.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        repository: StatisticsRepository,
        analytics: Analytics,
        onBack: () -> Unit = {},
    ) = StatisticsViewModel(
        onBack = onBack,
        getReadingStatisticsUseCase = GetReadingStatisticsUseCase(repository),
        getBooksForPeriodUseCase = GetBooksForPeriodUseCase(repository),
        getAllBooksReadUseCase = GetAllBooksReadUseCase(repository),
        getRecentSessionsUseCase = GetRecentSessionsUseCase(repository),
        getStatisticsOverviewUseCase = GetStatisticsOverviewUseCase(
            // The overview reads sessions from its own repository so it doesn't consume
            // the results the detail-sheet tests queue up.
            FakeStatisticsRepository(
                statisticsResults = mutableListOf(),
                allSessionsResults = MutableList(OVERVIEW_LOADS) { Ok(emptyList()) },
            ),
        ),
        preferences = InMemoryPreferences(),
        analytics = analytics,
    )
}

private const val OVERVIEW_LOADS = 10

private class InMemoryPreferences : Preferences {
    private val values = mutableMapOf<String, String>()

    override fun getStringOrNull(key: PreferencesKey): String? = values[key.name]
    override fun putString(key: PreferencesKey, value: String) {
        values[key.name] = value
    }

    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = flowOf(values[key.name])
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean = defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) = Unit
    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
        flowOf(defaultValue)

    override fun getLong(key: PreferencesKey, defaultValue: Long): Long = defaultValue
    override fun putLong(key: PreferencesKey, value: Long) = Unit
    override fun remove(key: PreferencesKey) {
        values.remove(key.name)
    }
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
    private val periodBookResults: MutableList<AppResult<List<BookReadingStatsDomainModel>>> =
        mutableListOf(Ok(emptyList())),
    private val allBooksReadResults: MutableList<AppResult<List<BookReadingStatsDomainModel>>> =
        mutableListOf(Ok(emptyList())),
    private val allSessionsResults: MutableList<AppResult<List<ReadingSessionDomainModel>>> =
        mutableListOf(Ok(emptyList())),
    private val suspendSessions: Boolean = false,
) : StatisticsRepository {
    override suspend fun saveReadingSession(session: ReadingSessionDomainModel): CompletableResult = Ok(Unit)

    override suspend fun getAllSessions(): AppResult<List<ReadingSessionDomainModel>> =
        if (suspendSessions) awaitCancellation() else allSessionsResults.removeAt(0)

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
        allBooksReadResults.removeAt(0)

    override suspend fun getMostReadBooksInDateRange(
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): AppResult<List<BookReadingStatsDomainModel>> = periodBookResults.removeAt(0)

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
