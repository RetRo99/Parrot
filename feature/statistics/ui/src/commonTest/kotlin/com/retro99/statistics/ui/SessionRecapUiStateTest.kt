package com.retro99.statistics.ui

import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRecapUiStateTest {

    private fun SessionRecap?.state(enabled: Boolean = true, available: Boolean = true) =
        toSessionRecapUiState(cloudRecapsEnabled = enabled, engineAvailable = available)

    @Test
    fun noRecordedRecapIsNoneAndSaysWhetherRecapsAreOff() {
        assertEquals(SessionRecapUiState.None(cloudRecapsEnabled = false), null.state(enabled = false))
        assertEquals(SessionRecapUiState.None(cloudRecapsEnabled = true), null.state())
    }

    @Test
    fun skippedSessionIsIneligible() {
        assertEquals(
            SessionRecapUiState.Ineligible,
            sessionRecap(RecapStatus.SKIPPED_INELIGIBLE, lastError = RecapErrorCode.TOO_LITTLE_READING)
                .state(),
        )
    }

    @Test
    fun waitingRowsNeedConsentFirst() {
        listOf(RecapStatus.CAPTURING, RecapStatus.PENDING, RecapStatus.FAILED_RETRYABLE).forEach {
            assertEquals(
                SessionRecapUiState.WaitingForOptIn,
                sessionRecap(it).state(enabled = false, available = false),
                it.name,
            )
        }
    }

    @Test
    fun waitingRowsNeedSignInOnceConsented() {
        listOf(RecapStatus.PENDING, RecapStatus.FAILED_RETRYABLE).forEach {
            assertEquals(
                SessionRecapUiState.SignInRequired,
                sessionRecap(it, lastError = RecapErrorCode.AUTH_REQUIRED).state(available = false),
                it.name,
            )
        }
    }

    @Test
    fun pendingAndRunningRowsAreGenerating() {
        assertEquals(SessionRecapUiState.Generating, sessionRecap(RecapStatus.PENDING).state())
        assertEquals(SessionRecapUiState.Generating, sessionRecap(RecapStatus.CAPTURING).state())
        // A request may already be in flight, whatever the consent now.
        assertEquals(
            SessionRecapUiState.Generating,
            sessionRecap(RecapStatus.RUNNING).state(enabled = false, available = false),
        )
    }

    @Test
    fun succeededShowsSummaryAndEngine() {
        assertEquals(
            SessionRecapUiState.Succeeded("Summary.", engineId = "cloud", model = "hy3"),
            sessionRecap(RecapStatus.SUCCEEDED, summary = "Summary.", engineId = "cloud", model = "hy3")
                .state(enabled = false, available = false),
        )
    }

    @Test
    fun succeededWithoutSummaryIsAPermanentFailure() {
        assertEquals(
            SessionRecapUiState.FailedPermanent(canRetry = false),
            sessionRecap(RecapStatus.SUCCEEDED, summary = " ").state(),
        )
    }

    @Test
    fun notEnoughIsShownAsIs() {
        assertEquals(SessionRecapUiState.NotEnough, sessionRecap(RecapStatus.NOT_ENOUGH).state())
    }

    @Test
    fun failuresCarryWhetherRetryCanWork() {
        assertEquals(
            SessionRecapUiState.FailedRetryable(canRetry = true),
            sessionRecap(RecapStatus.FAILED_RETRYABLE, lastError = RecapErrorCode.NETWORK, canRetry = true)
                .state(),
        )
        assertEquals(
            SessionRecapUiState.FailedPermanent(canRetry = true),
            sessionRecap(RecapStatus.FAILED_PERMANENT, lastError = RecapErrorCode.MAX_ATTEMPTS, canRetry = true)
                .state(enabled = false),
        )
        assertEquals(
            SessionRecapUiState.FailedPermanent(canRetry = false),
            sessionRecap(RecapStatus.FAILED_PERMANENT, lastError = RecapErrorCode.EXCERPT_EXPIRED).state(),
        )
    }

    @Test
    fun succeededStateNeverPrintsTheSummary() {
        val state = SessionRecapUiState.Succeeded("Secret plot.", engineId = "cloud", model = null)

        assertEquals(false, state.toString().contains("Secret"))
    }
}
