package com.retro99.statistics.ui

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.statistics.domain.StatisticsRepository
import com.retro99.statistics.domain.model.ReadingStatisticsDomainModel
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.statistics.domain.usecase.GetAllBooksReadUseCase
import com.retro99.statistics.domain.usecase.GetBooksForPeriodUseCase
import com.retro99.statistics.domain.usecase.GetReadingStatisticsUseCase
import com.retro99.statistics.domain.usecase.GetRecentSessionsUseCase
import com.retro99.statistics.domain.usecase.GetStatisticsOverviewUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsOverviewRegressionTest {
    @Test
    fun `dismissed session sheet cannot reopen when its old query completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val releaseOld = CompletableDeferred<Unit>()
        var calls = 0
        val repository = object : StatisticsRepository by FakeStatisticsRepository(mutableListOf(Ok(emptyStatistics()))) {
            override suspend fun getAllSessions(): AppResult<List<ReadingSessionDomainModel>> {
                if (++calls == 1) withContext(NonCancellable) { releaseOld.await() }
                return Ok(emptyList())
            }
        }
        val viewModel = createViewModel(repository, FakeStatisticsRepository(mutableListOf(), allSessionsResults = mutableListOf(Ok(emptyList()))))
        try {
            runCurrent()
            viewModel.onIntent(StatisticsIntent.OnTotalSessionsClicked)
            runCurrent()
            viewModel.onIntent(StatisticsIntent.OnDismissDetail)
            viewModel.onIntent(StatisticsIntent.OnBooksReadClicked)
            runCurrent()
            assertNotNull(viewModel.currentViewState().booksReadDetailState)
            releaseOld.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.currentViewState().sessionsDetailState)
            assertNotNull(viewModel.currentViewState().booksReadDetailState)
        } finally {
            releaseOld.complete(Unit)
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test
    fun `superseded range response cannot replace the newest chart even if query ignores cancellation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val releaseOld = CompletableDeferred<Unit>()
        var calls = 0
        val overview = object : StatisticsRepository by FakeStatisticsRepository(mutableListOf()) {
            override suspend fun getAllSessions(): AppResult<List<ReadingSessionDomainModel>> {
                if (++calls == 1) withContext(NonCancellable) { releaseOld.await() }
                return Ok(emptyList())
            }
        }
        val viewModel = createViewModel(FakeStatisticsRepository(mutableListOf(Ok(emptyStatistics()))), overview)
        try {
            runCurrent()
            viewModel.onIntent(StatisticsIntent.OnRangeSelected(StatisticsRange.MONTH))
            runCurrent()
            viewModel.onIntent(StatisticsIntent.OnRangeSelected(StatisticsRange.YEAR))
            runCurrent()
            assertEquals(StatisticsRange.YEAR, viewModel.currentViewState().overview?.range)
            releaseOld.complete(Unit)
            advanceUntilIdle()
            assertEquals(StatisticsRange.YEAR, viewModel.currentViewState().overview?.range)
            assertNull(viewModel.currentViewState().error)
        } finally {
            releaseOld.complete(Unit)
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `refresh while totals are pending does not launch duplicate totals queries`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val totals = object : StatisticsRepository by FakeStatisticsRepository(mutableListOf()) {
            override fun getReadingStatistics(): Flow<AppResult<ReadingStatisticsDomainModel>> = flow {
                calls++
                release.await()
                emit(Ok(emptyStatistics()))
            }
        }
        val viewModel = createViewModel(totals, FakeStatisticsRepository(mutableListOf(), allSessionsResults = MutableList(12) { Ok(emptyList()) }))
        try {
            runCurrent()
            repeat(10) { viewModel.onIntent(StatisticsIntent.OnRefresh); runCurrent() }
            assertEquals(1, calls)
            release.complete(Unit)
            advanceUntilIdle()
            assertNotNull(viewModel.currentViewState().statistics)
            assertEquals(false, viewModel.currentViewState().isLoading)
        } finally {
            release.complete(Unit)
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test
    fun `a successful range retry clears an overview error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failure = AppError.DatabaseError(IllegalStateException("overview failed"))
        val viewModel = createViewModel(
            FakeStatisticsRepository(mutableListOf(Ok(emptyStatistics()))),
            FakeStatisticsRepository(mutableListOf(), allSessionsResults = mutableListOf(Err(failure), Ok(emptyList()))),
        )
        try {
            advanceUntilIdle()
            assertEquals(failure, viewModel.currentViewState().error)
            viewModel.onIntent(StatisticsIntent.OnRangeSelected(StatisticsRange.MONTH))
            advanceUntilIdle()
            assertNotNull(viewModel.currentViewState().overview)
            assertNull(viewModel.currentViewState().error)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `late totals success cannot erase a failed chart load`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val releaseTotals = CompletableDeferred<Unit>()
        val totals = object : StatisticsRepository by FakeStatisticsRepository(mutableListOf()) {
            override fun getReadingStatistics(): Flow<AppResult<ReadingStatisticsDomainModel>> = flow {
                releaseTotals.await()
                emit(Ok(emptyStatistics()))
            }
        }
        val failure = AppError.DatabaseError(IllegalStateException("overview failed"))
        val viewModel = createViewModel(totals, FakeStatisticsRepository(mutableListOf(), allSessionsResults = mutableListOf(Err(failure))))
        try {
            runCurrent()
            assertEquals(failure, viewModel.currentViewState().error)
            releaseTotals.complete(Unit)
            advanceUntilIdle()
            assertNotNull(viewModel.currentViewState().statistics)
            assertNull(viewModel.currentViewState().overview)
            assertEquals(failure, viewModel.currentViewState().error)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `successful overview cannot erase a totals failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failure = AppError.DatabaseError(IllegalStateException("totals failed"))
        val viewModel = createViewModel(
            FakeStatisticsRepository(mutableListOf(Err(failure))),
            FakeStatisticsRepository(mutableListOf(), allSessionsResults = mutableListOf(Ok(emptyList()))),
        )
        try {
            advanceUntilIdle()
            assertNotNull(viewModel.currentViewState().overview)
            assertEquals(failure, viewModel.currentViewState().error)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `range reload keeps a still failing totals error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failure = AppError.DatabaseError(IllegalStateException("totals failed"))
        val viewModel = createViewModel(
            FakeStatisticsRepository(mutableListOf(Err(failure))),
            FakeStatisticsRepository(mutableListOf(), allSessionsResults = mutableListOf(Ok(emptyList()), Ok(emptyList()))),
        )
        try {
            advanceUntilIdle()
            viewModel.onIntent(StatisticsIntent.OnRangeSelected(StatisticsRange.YEAR))
            advanceUntilIdle()
            assertEquals(StatisticsRange.YEAR, viewModel.currentViewState().overview?.range)
            assertEquals(failure, viewModel.currentViewState().error)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(totals: StatisticsRepository, overview: StatisticsRepository) = StatisticsViewModel(
        onBack = {}, getReadingStatisticsUseCase = GetReadingStatisticsUseCase(totals),
        getBooksForPeriodUseCase = GetBooksForPeriodUseCase(totals),
        getAllBooksReadUseCase = GetAllBooksReadUseCase(totals),
        getRecentSessionsUseCase = GetRecentSessionsUseCase(totals),
        getStatisticsOverviewUseCase = GetStatisticsOverviewUseCase(overview),
        preferences = InMemoryPreferences(), analytics = RecordingAnalytics(),
        recapRepository = FakeRecapRepository(), recapSettings = FakeRecapSettings(),
        recapEngineSelector = FakeRecapEngineSelector(),
    )
}
