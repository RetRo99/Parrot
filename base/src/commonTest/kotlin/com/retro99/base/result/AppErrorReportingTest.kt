package com.retro99.base.result

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppErrorReportingTest {
    @Test
    fun authenticationFailuresAreHandledOutcomesNotUnexpectedExceptions() {
        assertFalse(AppError.AuthError(message = "Authentication required").shouldReportException)
        assertFalse(
            AppError.AuthError(
                message = "Authentication cancelled",
                isCancellation = true,
            ).shouldReportException,
        )
    }

    @Test
    fun unexpectedErrorsRemainEligibleForExceptionReporting() {
        assertTrue(AppError.UnknownError(IllegalStateException()).shouldReportException)
        assertTrue(AppError.DatabaseError(IllegalStateException()).shouldReportException)
        assertTrue(AppError.NetworkError(IllegalStateException(), isExpectedFailure = false).shouldReportException)
    }

    @Test
    fun expectedHttpAndConnectivityFailuresRemainNonFatalOutcomes() {
        assertFalse(AppError.ApiError(code = 401).shouldReportException)
        assertFalse(AppError.ApiError(code = 500).shouldReportException)
        assertFalse(AppError.NetworkError(IllegalStateException(), isConnectivity = true).shouldReportException)
    }
}
