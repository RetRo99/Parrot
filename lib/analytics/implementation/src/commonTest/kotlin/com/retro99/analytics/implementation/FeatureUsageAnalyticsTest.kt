package com.retro99.analytics.implementation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiscoveryDestination
import com.retro99.analytics.api.DiscoveryRoute
import com.retro99.analytics.api.FeatureUsageAnalyticsEvent
import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.ReaderNavigationMethod
import com.retro99.analytics.api.ReaderNavigationTracker
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.UsageFeature
import com.retro99.analytics.api.UsageMode
import com.retro99.analytics.api.UsageOperation
import com.retro99.analytics.api.trackUsageOperation
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureUsageAnalyticsTest {
    @Test
    fun allNewDimensionsSurviveThePrivacyBoundary() {
        val events = UsageOperation.entries.flatMap { operation ->
            ProductOutcome.entries.map { outcome ->
                FeatureUsageAnalyticsEvent.Operation(operation, UsageAction.Accept, "reader", outcome, 15, 3)
            }
        } + DiscoveryRoute.entries.flatMap { route ->
            DiscoveryDestination.entries.map { FeatureUsageAnalyticsEvent.DiscoverySelected(route, it) }
        } + UsageAction.entries.map { FeatureUsageAnalyticsEvent.RecapInteraction(it, false) } +
            UsageFeature.entries.map { ProductAnalyticsEvent.FeatureExposed(it, "reader", true) } +
            ReaderNavigationMethod.entries.map {
                FeatureUsageAnalyticsEvent.NavigationCompleted(it, UsageMode.ReadAloud, ProductOutcome.Succeeded, 20)
            } + FeatureUsageAnalyticsEvent.LibraryLoadCompleted(ProductOutcome.Succeeded, 30, 20)

        events.forEach { event ->
            assertEquals(event.parameters, sanitizeAnalyticsParameters(event.parameters), event.name)
        }
    }

    @Test
    fun rejectsPrivateAndUnboundedDimensions() {
        assertEquals(emptyMap(), sanitizeAnalyticsParameters(mapOf(
            "usage_action" to "private_title", "discovery_route" to "private_query",
            "discovery_destination" to "private_author", "navigation_method" to "private_chapter",
            "load_kind" to "private_server", "target_count_bucket" to "private_id",
            "is_latest_recap" to "private_summary", "duration_ms" to -1L,
            "book_uuid" to "private_book", "summary" to "private_plot",
        )))
    }

    @Test
    fun successfulOperationHasOneAttemptAndOneTerminalAndReturnsOriginalValue() {
        val analytics = RecordingProductAnalytics()
        val result = immediate {
            analytics.trackUsageOperation(UsageOperation.Link, UsageAction.BulkLink, "link_review", 3,
                outcome = { ProductOutcome.Partial }) { 2 }
        }
        assertEquals(2, result)
        assertEquals(listOf("started", "partial"), analytics.events.map { it.parameters["outcome"] })
        assertEquals(listOf("started", "terminal"), analytics.events.map { it.parameters["stage"] })
        assertTrue(analytics.events.all { it.parameters["target_count_bucket"] == "two_to_five" })
    }

    @Test
    fun failureAndCancellationAreTerminalAndRethrown() {
        val analytics = RecordingProductAnalytics()
        assertFailsWith<IllegalStateException> {
            immediate {
                analytics.trackUsageOperation<Unit>(UsageOperation.Import, UsageAction.Import, "books_library",
                    outcome = { ProductOutcome.Succeeded }) { error("private filename") }
            }
        }
        assertEquals(listOf("started", "failed"), analytics.events.map { it.parameters["outcome"] })
        analytics.events.clear()
        assertFailsWith<CancellationException> {
            immediate {
                analytics.trackUsageOperation<Unit>(UsageOperation.Import, UsageAction.Import, "books_library",
                    outcome = { ProductOutcome.Succeeded }) { throw CancellationException("private filename") }
            }
        }
        assertEquals(listOf("started", "cancelled"), analytics.events.map { it.parameters["outcome"] })
        assertTrue(analytics.events.none { it.parameters.toString().contains("private") })
    }

    @Test
    fun brokenProviderDoesNotBreakTheAction() {
        val analytics = object : Analytics {
            override fun logEvent(event: AnalyticsEvent) = error("provider failed")
            override fun logException(throwable: Throwable, message: String?) = Unit
            override fun setUserId(userId: String?) = Unit
        }
        assertEquals("result", immediate {
            analytics.trackUsageOperation(UsageOperation.Link, UsageAction.ManualLink, "link_picker",
                outcome = { ProductOutcome.Succeeded }) { "result" }
        })
    }

    @Test
    fun navigationReportsOnlyOneTerminalAndPreservesRequestModeAndDuration() {
        val analytics = RecordingProductAnalytics()
        var elapsed = 0L
        val tracker = ReaderNavigationTracker(analytics) { elapsed }
        tracker.complete(ProductOutcome.Succeeded)
        assertTrue(analytics.events.isEmpty())
        tracker.begin(ReaderNavigationMethod.Bookmark, UsageMode.Tts)
        elapsed = 123
        tracker.complete(ProductOutcome.Succeeded)
        tracker.complete(ProductOutcome.Failed)
        assertEquals(1, analytics.events.size)
        assertEquals("bookmark", analytics.events.single().parameters["navigation_method"])
        assertEquals("tts", analytics.events.single().parameters["usage_mode"])
        assertEquals(123L, analytics.events.single().parameters["duration_ms"])
    }

    @Test
    fun replacingNavigationCancelsTheOldRequestAndTimeoutFailsTheNewRequest() {
        val analytics = RecordingProductAnalytics()
        var elapsed = 0L
        val tracker = ReaderNavigationTracker(analytics) { elapsed }
        tracker.begin(ReaderNavigationMethod.Toc, UsageMode.Reading)
        elapsed = 20
        tracker.begin(ReaderNavigationMethod.ProgressSlider, UsageMode.ReadAloud)
        elapsed = 10_020
        tracker.complete(ProductOutcome.Failed)
        assertEquals(listOf("cancelled", "failed"), analytics.events.map { it.parameters["outcome"] })
        assertEquals(listOf(20L, 10_000L), analytics.events.map { it.parameters["duration_ms"] })
    }

    /** These blocks never suspend; no additional coroutine dependency is needed. */
    private fun <T> immediate(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return checkNotNull(completed).getOrThrow()
    }
}
