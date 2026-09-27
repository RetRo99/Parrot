package com.retro99.reader.ui.reader

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CurrentBookTargetPersistenceTest {
    @Test
    fun successEmitsAttemptCompletionAndCorrelatedBreadcrumbs() {
        val analytics = RecordingAnalytics()
        var saved: CurrentlyReadingDomainModel? = null

        val succeeded = persistCurrentBookTarget(
            analytics = analytics,
            target = target,
            entryPoint = "reading_duration_threshold",
            isRetry = false,
        ) { saved = it }

        assertTrue(succeeded)
        assertEquals(target, saved)
        assertEquals(
            listOf("current_book_target_save_attempted", "current_book_target_save_completed"),
            analytics.events.map { it.name },
        )
        assertEquals(
            listOf("started" to "started", "terminal" to "succeeded"),
            analytics.breadcrumbs.map { it.stage to it.outcome },
        )
        assertEquals("reading_duration_threshold", analytics.events.first().parameters["entry_point"])
        assertEquals("ebook", analytics.events.first().parameters["book_type"])
        assertEquals(false, analytics.events.last().parameters["is_retry"])
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun failureIsReportedOnceAndARecoveryAttemptIsDistinctlyMarked() {
        val analytics = RecordingAnalytics()
        val failure = IllegalStateException("private preference details")

        val firstSucceeded = persistCurrentBookTarget(
            analytics = analytics,
            target = target,
            entryPoint = "reading_duration_threshold",
            isRetry = false,
        ) { throw failure }
        val retrySucceeded = persistCurrentBookTarget(
            analytics = analytics,
            target = target,
            entryPoint = "reader_close",
            isRetry = true,
        ) {}

        assertFalse(firstSucceeded)
        assertTrue(retrySucceeded)
        assertEquals(
            listOf(
                "current_book_target_save_attempted",
                "current_book_target_save_failed",
                "current_book_target_save_attempted",
                "current_book_target_save_completed",
            ),
            analytics.events.map { it.name },
        )
        assertEquals(false, analytics.events[0].parameters["is_retry"])
        assertEquals(false, analytics.events[1].parameters["is_retry"])
        assertEquals(true, analytics.events[2].parameters["is_retry"])
        assertEquals(true, analytics.events[3].parameters["is_retry"])
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        val context = assertNotNull(analytics.exceptions.single().second)
        assertEquals("reader", context.screen)
        assertEquals("save_current_book_target", context.action)
        assertEquals("current_book_target_save_failed", context.reasonCode)
        assertEquals(analytics.breadcrumbs[0].correlationId, analytics.breadcrumbs[1].correlationId)
        assertEquals(analytics.breadcrumbs[2].correlationId, analytics.breadcrumbs[3].correlationId)
    }

    @Test
    fun cancellationPropagatesWithoutFailureEventOrExceptionReport() {
        val analytics = RecordingAnalytics()
        val cancellation = CancellationException("scope cancelled")

        val thrown = runCatching {
            persistCurrentBookTarget(
                analytics = analytics,
                target = target,
                entryPoint = "reading_duration_threshold",
                isRetry = false,
            ) { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("current_book_target_save_attempted"), analytics.events.map { it.name })
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
    }

    private companion object {
        val target = CurrentlyReadingDomainModel(
            serverId = "local",
            bookUuid = "test-fixture-id",
            bookType = BookType.EBOOK,
            bookTitle = "Private test title",
            coverUrl = null,
            totalProgression = 0.49,
        )
    }
}

private class RecordingAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()
    val breadcrumbs = mutableListOf<DiagnosticContext>()
    val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext?>>()

    override fun logException(throwable: Throwable, message: String?) {
        exceptions += throwable to null
    }

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
