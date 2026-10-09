package com.retro99.reader.ui.reader

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.reader.ui.navigator.TtsPlaybackAction
import com.retro99.reader.ui.navigator.TtsPlaybackFailureReason
import com.retro99.reader.ui.navigator.TtsPlaybackOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA-BUG-0100: one successful start was reported twice, with one correlation id. Two reader
 * screens for one book collect the same controller's `playbackOperations`, and the flow
 * gives every subscriber every event (TTS-F10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTtsOperationReporterTest {

    private val correlationId = "ece47fbb-98b9-440c-97d2-37d89a350ec8"

    @Test
    fun two_readers_of_one_book_report_one_attempt_and_one_terminal_outcome() = runTest {
        val analytics = RecordingAnalytics()
        val reports = TtsPlaybackOperationReports()
        val operations = MutableSharedFlow<TtsPlaybackOperation>(extraBufferCapacity = 8)

        // Two live reader screens, each with its own collector of the one shared flow.
        repeat(2) {
            val reporter = ReaderTtsOperationReporter(reports, analytics)
            operations
                .onEach { reporter.report(it) }
                .launchIn(backgroundScope)
        }
        runCurrent()

        operations.emit(attempted())
        runCurrent()
        operations.emit(succeeded())
        runCurrent()

        assertEquals(
            listOf("attempted", "succeeded"),
            analytics.events.map { it.parameters["tts_outcome"] },
        )
        assertEquals(listOf("attempted", "succeeded"), analytics.breadcrumbs.map { it.outcome })
        assertEquals(
            listOf(correlationId, correlationId),
            analytics.breadcrumbs.map { it.correlationId },
        )
    }

    @Test
    fun two_readers_of_one_book_report_one_failure_and_one_exception() = runTest {
        val analytics = RecordingAnalytics()
        val reports = TtsPlaybackOperationReports()
        val first = ReaderTtsOperationReporter(reports, analytics)
        val second = ReaderTtsOperationReporter(reports, analytics)

        val failure = failed(IllegalStateException("synthesis gave up"))
        first.report(failure)
        second.report(failure)

        assertEquals(1, analytics.events.size)
        assertEquals("failed", analytics.events.single().parameters["tts_outcome"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals(0, analytics.breadcrumbs.size)
    }

    @Test
    fun a_second_attempt_with_a_new_correlation_id_is_reported() {
        val analytics = RecordingAnalytics()
        val reports = TtsPlaybackOperationReports()
        val reporter = ReaderTtsOperationReporter(reports, analytics)

        reporter.report(attempted())
        reporter.report(attempted(correlationId = "a-second-start"))

        assertEquals(2, analytics.events.size)
        assertEquals(
            listOf(correlationId, "a-second-start"),
            analytics.breadcrumbs.map { it.correlationId },
        )
    }

    /**
     * What is remembered is bounded: after enough newer attempts the oldest pair is
     * forgotten, so a long reading session cannot grow the record without end.
     */
    @Test
    fun what_has_been_reported_is_forgotten_after_enough_newer_attempts() {
        val reports = TtsPlaybackOperationReports()

        reports.claim("first", "attempted")
        assertFalse(reports.claim("first", "attempted"), "One outcome was reported twice")

        repeat(64) { index -> reports.claim("attempt-$index", "attempted") }

        assertTrue(reports.claim("first", "attempted"), "The record grows without bound")
    }

    private fun attempted(correlationId: String = this.correlationId) =
        TtsPlaybackOperation.Attempted(
            correlationId = correlationId,
            action = TtsPlaybackAction.CONTROLS,
            isRetry = false,
        )

    private fun succeeded() = TtsPlaybackOperation.Succeeded(
        correlationId = correlationId,
        action = TtsPlaybackAction.CONTROLS,
        isRetry = false,
        durationMs = 2085,
    )

    private fun failed(error: Throwable) = TtsPlaybackOperation.Failed(
        correlationId = correlationId,
        action = TtsPlaybackAction.CONTROLS,
        isRetry = false,
        durationMs = 1200,
        reasonCode = TtsPlaybackFailureReason.SYNTHESIS_FAILED,
        error = error,
    )

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val breadcrumbs = mutableListOf<DiagnosticContext>()
        val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext>>()

        override fun logException(throwable: Throwable, message: String?) = Unit

        override fun logException(throwable: Throwable, context: DiagnosticContext) {
            exceptions += throwable to context
        }

        override fun logBreadcrumb(context: DiagnosticContext) {
            breadcrumbs += context
        }

        override fun logEvent(event: AnalyticsEvent) {
            events += event
        }

        override fun setUserId(userId: String?) = Unit
    }
}
