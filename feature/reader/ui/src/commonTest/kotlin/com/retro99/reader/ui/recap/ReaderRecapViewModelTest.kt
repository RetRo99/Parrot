package com.retro99.reader.ui.recap

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent

import com.retro99.reader.domain.recap.RecapBannerDismissals
import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapRetryResult
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class ReaderRecapViewModelTest {

    private val history = MutableStateFlow<List<SessionRecap>>(emptyList())
    private val enabled = MutableStateFlow(true)
    private val repository = FakeRepository(history)
    private val dismissals = FakeDismissals()
    private val events = mutableListOf<AnalyticsEvent>()
    private val analytics = object : Analytics {
        override fun logEvent(event: AnalyticsEvent) { events += event }
        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun setUserId(userId: String?) = Unit
    }

    @Test
    fun theLatestSucceededRecapIsOffered() {
        val banner = readerRecapBanner(
            listOf(recap("new", RecapStatus.SUCCEEDED, "New."), recap("old", RecapStatus.SUCCEEDED, "Old.")),
            cloudRecapsEnabled = true,
        )

        assertEquals(ReaderRecapBanner("new", "New.", isLatestSession = true), banner)
    }

    @Test
    fun pendingAndFailedRecapsAreNotOffered() {
        listOf(
            RecapStatus.PENDING,
            RecapStatus.RUNNING,
            RecapStatus.FAILED_RETRYABLE,
            RecapStatus.FAILED_PERMANENT,
            RecapStatus.NOT_ENOUGH,
        ).forEach { status ->
            assertNull(readerRecapBanner(listOf(recap("s", status)), cloudRecapsEnabled = true), status.name)
        }
        assertNull(readerRecapBanner(emptyList(), cloudRecapsEnabled = true))
        assertNull(readerRecapBanner(listOf(recap("s", RecapStatus.SUCCEEDED, " ")), cloudRecapsEnabled = true))
    }

    @Test
    fun anOlderRecapBehindAnUnfinishedSessionIsMarkedEarlier() {
        val banner = readerRecapBanner(
            listOf(recap("new", RecapStatus.PENDING), recap("old", RecapStatus.SUCCEEDED, "Old.")),
            cloudRecapsEnabled = true,
        )

        assertEquals(ReaderRecapBanner("old", "Old.", isLatestSession = false), banner)
    }

    @Test
    fun nothingIsOfferedWithCloudRecapsOff() {
        assertNull(readerRecapBanner(listOf(recap("s", RecapStatus.SUCCEEDED, "S.")), cloudRecapsEnabled = false))
    }

    @Test
    fun theBannerFollowsStoredRecaps() = runVmTest { viewModel ->
        assertNull(viewModel.currentViewState().banner)

        history.value = listOf(recap("s1", RecapStatus.SUCCEEDED, "Summary."))
        advanceUntilIdle()

        assertEquals("s1", viewModel.currentViewState().banner?.sessionId)
        assertEquals(0, repository.retries)
    }

    @Test
    fun dismissingHidesAndRemembersWithoutRetrying() = runVmTest { viewModel ->
        history.value = listOf(recap("s1", RecapStatus.SUCCEEDED, "Summary."))
        advanceUntilIdle()

        viewModel.onIntent(ReaderRecapIntent.Dismiss)
        advanceUntilIdle()

        assertNull(viewModel.currentViewState().banner)
        assertEquals(listOf("s1"), dismissals.dismissed)
        assertEquals(0, repository.retries)
    }

    @Test
    fun aDismissedRecapStaysHiddenOnTheNextOpen() = runVmTest(dismissed = listOf("s1")) { viewModel ->
        history.value = listOf(recap("s1", RecapStatus.SUCCEEDED, "Summary."))
        advanceUntilIdle()

        assertNull(viewModel.currentViewState().banner)
    }

    @Test
    fun expandingTogglesAndANewRecapCollapses() = runVmTest { viewModel ->
        history.value = listOf(recap("s1", RecapStatus.SUCCEEDED, "One."))
        advanceUntilIdle()

        viewModel.onIntent(ReaderRecapIntent.ToggleExpanded)
        assertTrue(viewModel.currentViewState().isExpanded)

        history.value = listOf(recap("s2", RecapStatus.SUCCEEDED, "Two."), history.value.single())
        advanceUntilIdle()

        assertEquals("s2", viewModel.currentViewState().banner?.sessionId)
        assertFalse(viewModel.currentViewState().isExpanded)
    }

    @Test
    fun turningCloudRecapsOffHidesTheBanner() = runVmTest { viewModel ->
        history.value = listOf(recap("s1", RecapStatus.SUCCEEDED, "Summary."))
        advanceUntilIdle()

        enabled.value = false
        advanceUntilIdle()

        assertNull(viewModel.currentViewState().banner)
    }

    @Test
    fun theBannerNeverPrintsTheSummary() {
        assertFalse(ReaderRecapBanner("s", "Secret plot.", true).toString().contains("Secret"))
    }

    @Test
    fun telemetryTracksVisibleUseWithoutSummaryOrIdentity() = runVmTest { viewModel ->
        history.value = listOf(recap("private_session", RecapStatus.SUCCEEDED, "Private plot."))
        advanceUntilIdle()
        assertTrue(events.isEmpty()) // Stored availability is not a visible exposure.
        viewModel.onIntent(ReaderRecapIntent.Visible)
        viewModel.onIntent(ReaderRecapIntent.Visible)
        viewModel.onIntent(ReaderRecapIntent.ToggleExpanded)
        viewModel.onIntent(ReaderRecapIntent.ToggleExpanded)
        viewModel.onIntent(ReaderRecapIntent.Dismiss)
        assertEquals(listOf("feature_exposed", "recap_interaction", "recap_interaction", "recap_interaction", "recap_interaction"), events.map { it.name })
        assertEquals(listOf("shown", "expanded", "collapsed", "dismissed"), events.drop(1).map { it.parameters["usage_action"] })
        assertTrue(events.none { it.parameters.toString().contains("Private") || it.parameters.toString().contains("private_session") })
        advanceUntilIdle()
    }

    private fun runVmTest(
        dismissed: List<String> = emptyList(),
        block: suspend TestScope.(ReaderRecapViewModel) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            dismissals.dismissed += dismissed
            val viewModel = ReaderRecapViewModel(
                bookUuid = "book",
                recapRepository = repository,
                recapSettings = FakeSettings(enabled),
                dismissals = dismissals,
                analytics = analytics,
            )
            advanceUntilIdle()
            block(viewModel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun recap(sessionId: String, status: RecapStatus, summary: String? = null) = SessionRecap(
        sessionId = sessionId,
        serverId = "server",
        bookId = "book",
        status = status,
        summary = summary,
        lastError = null,
        startPosition = RecapPosition(),
        endPosition = RecapPosition(),
        startChapter = RecapChapter(),
        endChapter = RecapChapter(),
        attemptCount = 0,
        nextAttemptAt = null,
        engineId = "cloud",
        model = null,
        createdAt = 1,
        updatedAt = 1,
        endedAt = 2,
        generatedAt = null,
    )

    private class FakeRepository(private val history: Flow<List<SessionRecap>>) : RecapRepository {
        var retries = 0

        override fun observeRecap(sessionId: String): Flow<SessionRecap?> = flowOf(null)

        override fun observeLatestForBook(bookId: String): Flow<SessionRecap?> = flowOf(null)

        override fun observeHistory(bookId: String): Flow<List<SessionRecap>> = history

        override suspend fun retry(sessionId: String): RecapRetryResult {
            retries++
            return RecapRetryResult.NOT_FOUND
        }
    }

    private class FakeSettings(private val enabled: MutableStateFlow<Boolean>) : RecapSettings {
        override fun observeCloudRecapsEnabled(): Flow<Boolean> = enabled

        override suspend fun isCloudRecapsEnabled(): Boolean = enabled.value

        override suspend fun setCloudRecapsEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    private class FakeDismissals : RecapBannerDismissals {
        val dismissed = mutableListOf<String>()

        override suspend fun isDismissed(sessionId: String): Boolean = sessionId in dismissed

        override suspend fun dismiss(sessionId: String) {
            dismissed += sessionId
        }
    }
}
