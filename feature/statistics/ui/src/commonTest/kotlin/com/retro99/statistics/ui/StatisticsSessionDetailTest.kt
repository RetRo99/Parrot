package com.retro99.statistics.ui

import com.github.michaelbull.result.Ok
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.recap.RecapRequestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import com.retro99.statistics.domain.usecase.GetAllBooksReadUseCase
import com.retro99.statistics.domain.usecase.GetBooksForPeriodUseCase
import com.retro99.statistics.domain.usecase.GetReadingStatisticsUseCase
import com.retro99.statistics.domain.usecase.GetRecentSessionsUseCase
import com.retro99.statistics.domain.usecase.GetStatisticsOverviewUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsSessionDetailTest {

    private val recaps = FakeRecapRepository()
    private val settings = FakeRecapSettings(enabled = true)
    private val selector = FakeRecapEngineSelector(available = true)

    @Test
    fun openingASessionShowsItsStoredRecapWithoutGenerating() = runDetailTest { viewModel ->
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.PENDING, sessionId = "r1"))

        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()

        val detail = viewModel.selected()!!
        assertEquals(LINKED, detail.session.id)
        assertEquals(SessionRecapUiState.Ready, detail.recap)
        assertTrue(detail.canRequestRecap)
        assertEquals(listOf("r1"), recaps.observed)
        assertEquals(0, selector.selectCalls)
        assertTrue(recaps.retried.isEmpty())
        assertTrue(recaps.requested.isEmpty())
    }

    @Test
    fun theDetailFollowsTheStoredRecap() = runDetailTest { viewModel ->
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.RUNNING, sessionId = "r1"))
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()

        recaps.recaps.value = mapOf(
            "r1" to sessionRecap(RecapStatus.SUCCEEDED, sessionId = "r1", summary = "S.", engineId = "cloud"),
        )
        advanceUntilIdle()

        assertEquals(
            SessionRecapUiState.Succeeded("S.", engineId = "cloud", model = null),
            viewModel.selected()!!.recap,
        )
    }

    @Test
    fun consentAndSignInChangesUpdateTheState() = runDetailTest { viewModel ->
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.PENDING, sessionId = "r1"))
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()

        settings.enabled.value = false
        selector.available.value = false
        advanceUntilIdle()
        assertEquals(SessionRecapUiState.WaitingForOptIn, viewModel.selected()!!.recap)

        settings.enabled.value = true
        advanceUntilIdle()
        assertEquals(SessionRecapUiState.SignInRequired, viewModel.selected()!!.recap)
    }

    @Test
    fun consentWithoutASessionAsksForSignInNotForOptIn() = runDetailTest { viewModel ->
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.PENDING, sessionId = "r1"))
        // Consent stays on, but the enabled gate needs a live session and is off.
        settings.consent = MutableStateFlow(true)
        settings.enabled.value = false
        selector.available.value = false
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()

        assertEquals(SessionRecapUiState.SignInRequired, viewModel.selected()!!.recap)
    }

    @Test
    fun aSessionWithoutALinkHasNoRecap() = runDetailTest { viewModel ->
        settings.enabled.value = false

        viewModel.onIntent(StatisticsIntent.OnSessionClicked(UNLINKED))
        advanceUntilIdle()

        assertEquals(SessionRecapUiState.None(cloudRecapsEnabled = false), viewModel.selected()!!.recap)
        assertTrue(recaps.observed.isEmpty())
    }

    @Test
    fun generateAsksTheRepositoryOnce() = runDetailTest { viewModel ->
        recaps.recaps.value = mapOf(
            "r1" to sessionRecap(RecapStatus.FAILED_RETRYABLE, sessionId = "r1", canRetry = true),
        )
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()
        assertEquals(SessionRecapUiState.FailedRetryable(canRetry = true), viewModel.selected()!!.recap)

        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        advanceUntilIdle()

        assertEquals(listOf("r1"), recaps.requested)
        assertTrue(recaps.retried.isEmpty())
        assertFalse(viewModel.selected()!!.isRequestingRecap)
        assertEquals(RecapRequestResult.QUEUED, viewModel.selected()!!.recapRequestResult)
    }

    @Test
    fun aRefusedRetryIsShown() = runDetailTest { viewModel ->
        recaps.requestResult = RecapRequestResult.TEXT_UNAVAILABLE
        recaps.recaps.value = mapOf(
            "r1" to sessionRecap(RecapStatus.FAILED_PERMANENT, sessionId = "r1", canRetry = true),
        )
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()

        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        advanceUntilIdle()

        assertEquals(RecapRequestResult.TEXT_UNAVAILABLE, viewModel.selected()!!.recapRequestResult)
        assertFalse(viewModel.selected()!!.canRequestRecap)
    }

    @Test
    fun aRefusedRetryClearsOnceTheRecapMovesOn() = runDetailTest { viewModel ->
        recaps.requestResult = RecapRequestResult.TEXT_UNAVAILABLE
        recaps.recaps.value = mapOf(
            "r1" to sessionRecap(RecapStatus.FAILED_PERMANENT, sessionId = "r1", canRetry = true),
        )
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()
        // Refused because the runner had just claimed the row.
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        advanceUntilIdle()
        assertEquals(RecapRequestResult.TEXT_UNAVAILABLE, viewModel.selected()!!.recapRequestResult)

        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.RUNNING, sessionId = "r1"))
        advanceUntilIdle()
        recaps.recaps.value = mapOf(
            "r1" to sessionRecap(RecapStatus.FAILED_PERMANENT, sessionId = "r1", canRetry = true),
        )
        advanceUntilIdle()

        assertNull(viewModel.selected()!!.recapRequestResult)
    }

    @Test
    fun goingBackReturnsToTheList() = runDetailTest { viewModel ->
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()

        viewModel.onIntent(StatisticsIntent.OnSessionDetailClosed)
        advanceUntilIdle()

        assertNull(viewModel.selected())
        assertEquals(2, viewModel.currentViewState().sessionsDetailState!!.sessions.size)
    }

    @Test
    fun duplicateTapsAreIgnoredUntilTheRequestFinishes() = runDetailTest { viewModel ->
        val result = CompletableDeferred<RecapRequestResult>()
        recaps.requestHandler = { result.await() }
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.PENDING, sessionId = "r1"))
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        runCurrent()
        assertTrue(viewModel.selected()!!.isRequestingRecap)
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        runCurrent()
        assertEquals(listOf("r1"), recaps.requested)
        result.complete(RecapRequestResult.QUEUED)
        advanceUntilIdle()
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        advanceUntilIdle()
        assertEquals(listOf("r1"), recaps.requested)
    }

    @Test
    fun aLateResponseCannotChangeAnotherSession() = runDetailTest { viewModel ->
        val result = CompletableDeferred<RecapRequestResult>()
        recaps.requestHandler = { result.await() }
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.PENDING, sessionId = "r1"))
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        runCurrent()
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(UNLINKED))
        runCurrent()
        result.complete(RecapRequestResult.ACCOUNT_REQUIRED)
        advanceUntilIdle()
        assertEquals(UNLINKED, viewModel.selected()!!.session.id)
        assertNull(viewModel.selected()!!.recapRequestResult)
        assertFalse(viewModel.selected()!!.isRequestingRecap)
    }

    @Test
    fun completedHistoricalAndUnconsentedSessionsCannotRequest() = runDetailTest { viewModel ->
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(UNLINKED))
        advanceUntilIdle()
        assertFalse(viewModel.selected()!!.canRequestRecap)
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.SUCCEEDED, sessionId = "r1", summary = "Saved."))
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()
        assertFalse(viewModel.selected()!!.canRequestRecap)
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.PENDING, sessionId = "r1"))
        settings.enabled.value = false
        advanceUntilIdle()
        assertFalse(viewModel.selected()!!.canRequestRecap)
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        advanceUntilIdle()
        assertTrue(recaps.requested.isEmpty())
    }

    @Test
    fun requestFailureLeavesTheButtonAvailableForAnotherTry() = runDetailTest { viewModel ->
        recaps.requestHandler = { error("test failure") }
        recaps.recaps.value = mapOf("r1" to sessionRecap(RecapStatus.PENDING, sessionId = "r1"))
        viewModel.onIntent(StatisticsIntent.OnSessionClicked(LINKED))
        advanceUntilIdle()
        viewModel.onIntent(StatisticsIntent.OnGenerateRecap)
        advanceUntilIdle()
        assertTrue(viewModel.selected()!!.recapRequestFailed)
        assertTrue(viewModel.selected()!!.canRequestRecap)
    }

    private fun runDetailTest(block: suspend TestScope.(StatisticsViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeStatisticsRepository(
                statisticsResults = mutableListOf(Ok(emptyStatistics())),
                allSessionsResults = mutableListOf(
                    Ok(listOf(session(LINKED, "r1"), session(UNLINKED, null))),
                ),
            )
            val viewModel = StatisticsViewModel(
                onBack = {},
                getReadingStatisticsUseCase = GetReadingStatisticsUseCase(repository),
                getBooksForPeriodUseCase = GetBooksForPeriodUseCase(repository),
                getAllBooksReadUseCase = GetAllBooksReadUseCase(repository),
                getRecentSessionsUseCase = GetRecentSessionsUseCase(repository),
                getStatisticsOverviewUseCase = GetStatisticsOverviewUseCase(
                    FakeStatisticsRepository(
                        statisticsResults = mutableListOf(),
                        allSessionsResults = mutableListOf(Ok(emptyList())),
                    ),
                ),
                preferences = InMemoryPreferences(),
                analytics = RecordingAnalytics(),
                recapRepository = recaps,
                recapSettings = settings,
                recapEngineSelector = selector,
            )
            advanceUntilIdle()
            viewModel.onIntent(StatisticsIntent.OnTotalSessionsClicked)
            advanceUntilIdle()
            block(viewModel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun StatisticsViewModel.selected() = currentViewState().sessionsDetailState?.selected

    private fun session(id: Long, recapSessionId: String?) = ReadingSessionDomainModel(
        id = id,
        bookUuid = "book",
        bookTitle = "Book",
        bookType = BookType.EBOOK,
        startTime = 1_000,
        endTime = 61_000,
        durationMs = 60_000,
        pagesRead = null,
        startProgression = 0.1,
        endProgression = 0.2,
        readingSpeedWpm = 250,
        recapSessionId = recapSessionId,
    )

    private companion object {
        const val LINKED = 1L
        const val UNLINKED = 2L
    }
}
