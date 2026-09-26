package com.retro99.network.implementation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.retro99.base.result.AppError
import com.retro99.base.result.log
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent

class NetworkFailureClassifierTest {

    @Test
    fun recognizesConnectionFailuresThroughCauseChainWithoutExceptionReporting() {
        val failure = classifyNetworkFailure(
            IllegalStateException("request failed", ConnectException("connection refused")),
        )

        assertEquals("connection_failed", failure.errorType)
        assertFalse(failure.isTimeout)
        assertTrue(failure.isConnectivity)
        assertTrue(failure.isExpectedFailure)
        assertFalse(
            AppError.NetworkError(
                throwable = ConnectException("connection refused"),
                isConnectivity = failure.isConnectivity,
                isTimeout = failure.isTimeout,
                isExpectedFailure = failure.isExpectedFailure,
            ).shouldReportException,
        )
    }

    @Test
    fun recognizesTimeoutAndDnsFailuresAsExpectedOutcomes() {
        val timeout = classifyNetworkFailure(SocketTimeoutException("timeout"))
        val requestTimeout = classifyNetworkFailure(HttpRequestTimeoutException("request timeout"))
        val dns = classifyNetworkFailure(IllegalStateException("request", UnknownHostException("dns")))

        assertEquals("socket_timeout", timeout.errorType)
        assertTrue(timeout.isTimeout)
        assertTrue(timeout.isExpectedFailure)
        assertEquals("connect_timeout", requestTimeout.errorType)
        assertTrue(requestTimeout.isTimeout)
        assertTrue(requestTimeout.isExpectedFailure)
        assertFalse(
            AppError.NetworkError(
                throwable = SocketTimeoutException("timeout"),
                isConnectivity = timeout.isConnectivity,
                isTimeout = timeout.isTimeout,
                isExpectedFailure = timeout.isExpectedFailure,
            ).shouldReportException,
        )
        assertEquals("dns_resolution_failed", dns.errorType)
        assertTrue(dns.isConnectivity)
        assertTrue(dns.isExpectedFailure)
    }

    @Test
    fun keepsUnexpectedIoFailuresActionable() {
        val failure = classifyNetworkFailure(UnexpectedStorageIOException("disk write failed"))

        assertEquals("unknown", failure.errorType)
        assertFalse(failure.isConnectivity)
        assertFalse(failure.isExpectedFailure)
        assertTrue(AppError.NetworkError(UnexpectedStorageIOException("disk write failed")).shouldReportException)
        assertFalse(AppError.ApiError(code = 401).shouldReportException)
    }

    @Test
    fun expectedNetworkAndHttpOutcomesDoNotBecomeCrashlyticsExceptionsAtPropagationBoundary() {
        val analytics = RecordingAnalytics()

        AppError.NetworkError(ConnectException("connection refused"), isConnectivity = true)
            .log(analytics, "network failure")
        AppError.NetworkError(SocketTimeoutException("timeout"), isTimeout = true)
            .log(analytics, "timeout")
        AppError.ApiError(404, "not found").log(analytics, "HTTP error")

        assertEquals(0, analytics.exceptionCount)
    }
}

private class ConnectException(message: String) : Exception(message)
private class SocketTimeoutException(message: String) : Exception(message)
private class HttpRequestTimeoutException(message: String) : Exception(message)
private class UnknownHostException(message: String) : Exception(message)
private class UnexpectedStorageIOException(message: String) : Exception(message)

private class RecordingAnalytics : Analytics {
    var exceptionCount = 0

    override fun logException(throwable: Throwable, message: String?) {
        exceptionCount += 1
    }

    override fun logEvent(event: AnalyticsEvent) = Unit

    override fun setUserId(userId: String?) = Unit
}
