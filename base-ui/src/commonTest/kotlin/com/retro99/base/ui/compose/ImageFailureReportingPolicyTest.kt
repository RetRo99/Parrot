package com.retro99.base.ui.compose

import coil3.network.HttpException
import coil3.network.NetworkResponse
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFailureReportingPolicyTest {
    @Test
    fun suppressesHttpStatusFailures() {
        assertFalse(shouldReportUnexpectedImageFailure(HttpException(NetworkResponse(code = 503))))
    }

    @Test
    fun suppressesHttpFailuresNestedInAnotherException() {
        val httpFailure = HttpException(NetworkResponse(code = 401))
        assertFalse(shouldReportUnexpectedImageFailure(IllegalStateException("wrapper", httpFailure)))
    }

    @Test
    fun suppressesNetworkIoFailures() {
        assertFalse(shouldReportUnexpectedImageFailure(IOException("network unavailable")))
        assertFalse(shouldReportUnexpectedImageFailure(IllegalStateException("wrapper", IOException())))
    }

    @Test
    fun suppressesCancellation() {
        assertFalse(shouldReportUnexpectedImageFailure(CancellationException("request cancelled")))
        assertFalse(
            shouldReportUnexpectedImageFailure(
                IllegalStateException("wrapper", CancellationException("request cancelled")),
            ),
        )
    }

    @Test
    fun leavesUnexpectedImageProcessingFailuresReportable() {
        assertTrue(shouldReportUnexpectedImageFailure(IllegalStateException("image decode failed")))
    }

    @Test
    fun reportsUnexpectedFailureOnlyOncePerImageRequest() {
        val gate = ImageFailureReportGate()

        assertTrue(gate.shouldReport(IllegalStateException("image decode failed")))
        assertFalse(gate.shouldReport(IllegalStateException("repeated image decode failure")))
    }

    @Test
    fun expectedFailureDoesNotConsumeUnexpectedFailureReportAllowance() {
        val gate = ImageFailureReportGate()

        assertFalse(gate.shouldReport(HttpException(NetworkResponse(code = 404))))
        assertTrue(gate.shouldReport(IllegalStateException("image decode failed")))
    }
}
