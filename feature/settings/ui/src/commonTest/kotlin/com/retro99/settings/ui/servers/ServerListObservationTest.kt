package com.retro99.settings.ui.servers

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServerListObservationTest {
    @Test
    fun initialValueCompletesLoadOnceAndContinuesEmitting() = runTest {
        val analytics = RecordingAnalytics()
        val values = mutableListOf<Int>()

        observeServerList(
            analytics = analytics,
            isRetry = false,
            source = flow {
                emit(1)
                emit(2)
            },
            onValue = values::add,
            onFailure = { error("Unexpected failure") },
        )

        assertEquals(listOf(1, 2), values)
        assertEquals(
            listOf("server_list_load_attempted", "server_list_load_completed"),
            analytics.events.map { it.name },
        )
        assertEquals("succeeded", analytics.events.last().parameters["outcome"])
        assertEquals(2, analytics.breadcrumbs.size)
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun initialLoadFailureIsReportedOnceAndAllowsRetry() = runTest {
        val analytics = RecordingAnalytics()
        var hasLoadedValue: Boolean? = null
        val sourceError = IllegalStateException("private server response")

        observeServerList(
            analytics = analytics,
            isRetry = true,
            source = flow<Int> { throw sourceError },
            onValue = { error("No value expected") },
            onFailure = { hasLoadedValue = it },
        )

        assertEquals(false, hasLoadedValue)
        assertEquals(
            listOf("server_list_load_attempted", "server_list_load_completed"),
            analytics.events.map { it.name },
        )
        assertEquals(true, analytics.events.first().parameters["is_retry"])
        assertEquals("failed", analytics.events.last().parameters["outcome"])
        assertEquals("server_list_load_failed", analytics.events.last().parameters["reason_code"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals("server_list_load", analytics.exceptions.single().second.operation)
        assertEquals("failed", analytics.exceptions.single().second.outcome)
        assertEquals(2, analytics.breadcrumbs.size)
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
    }

    @Test
    fun completionBeforeFirstValueLeavesLoadingAndIsReportedAsFailure() = runTest {
        val analytics = RecordingAnalytics()
        var loadFailed = false
        var hasLoadedValue: Boolean? = null

        observeServerList(
            analytics = analytics,
            isRetry = false,
            source = emptyFlow<Int>(),
            onValue = { error("No value expected") },
            onFailure = {
                loadFailed = true
                hasLoadedValue = it
            },
        )

        assertTrue(loadFailed)
        assertEquals(false, hasLoadedValue)
        assertEquals("failed", analytics.events.last().parameters["outcome"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals("server_list_load", analytics.exceptions.single().second.operation)
        assertEquals(2, analytics.breadcrumbs.size)
    }

    @Test
    fun retryAfterLoadFailureCanRecoverWithOneSuccessfulTerminalOutcome() = runTest {
        val analytics = RecordingAnalytics()
        val values = mutableListOf<Int>()
        var serverListLoadFailed = false

        observeServerList(
            analytics = analytics,
            isRetry = false,
            source = flow<Int> { throw IllegalStateException("load failed") },
            onValue = values::add,
            onFailure = { serverListLoadFailed = true },
        )
        assertTrue(serverListLoadFailed)

        observeServerList(
            analytics = analytics,
            isRetry = true,
            source = flow { emit(42) },
            onValue = {
                values += it
                serverListLoadFailed = false
            },
            onFailure = { serverListLoadFailed = true },
        )

        assertEquals(listOf(42), values)
        assertFalse(serverListLoadFailed)
        assertEquals(
            listOf(
                "server_list_load_attempted",
                "server_list_load_completed",
                "server_list_load_attempted",
                "server_list_load_completed",
            ),
            analytics.events.map { it.name },
        )
        assertEquals(listOf(false, true), analytics.events.filterIsInstance<
            ServerManagementAnalyticsEvent.ServerListLoadAttempted
        >().map { it.isRetry })
        assertEquals(listOf("failed", "succeeded"), analytics.events.filterIsInstance<
            ServerManagementAnalyticsEvent.ServerListLoadCompleted
        >().map { it.outcome.value })
        assertEquals(1, analytics.exceptions.size)
        assertEquals(4, analytics.breadcrumbs.size)
        assertTrue(analytics.breadcrumbs.take(2).all { it.correlationId == analytics.breadcrumbs[0].correlationId })
        assertTrue(analytics.breadcrumbs.drop(2).all { it.correlationId == analytics.breadcrumbs[2].correlationId })
    }

    @Test
    fun failureAfterFirstValuePreservesLoadedStateAndReportsObservationFailure() = runTest {
        val analytics = RecordingAnalytics()
        val values = mutableListOf<Int>()
        val failures = mutableListOf<Boolean>()

        observeServerList(
            analytics = analytics,
            isRetry = false,
            source = flow {
                emit(1)
                throw IllegalStateException("private registry details")
            },
            onValue = values::add,
            onFailure = failures::add,
        )

        assertEquals(listOf(1), values)
        assertEquals(listOf(true), failures)
        assertEquals(
            listOf("server_list_load_attempted", "server_list_load_completed", "server_list_observation_failed"),
            analytics.events.map { it.name },
        )
        assertEquals(1, analytics.exceptions.size)
        assertEquals("server_list_observation", analytics.exceptions.single().second.operation)
        assertEquals("server_list_observation_failed", analytics.exceptions.single().second.reasonCode)
        assertEquals(3, analytics.breadcrumbs.size)
        assertEquals(
            analytics.breadcrumbs.first().correlationId,
            analytics.breadcrumbs.last().correlationId,
        )
    }

    @Test
    fun cancellationBeforeFirstValueIsMeasuredWithoutExceptionReportAndRethrown() = runTest {
        val analytics = RecordingAnalytics()
        var failureCallbackCalled = false
        val collection = async {
            observeServerList(
                analytics = analytics,
                isRetry = false,
                source = flow<Int> { kotlinx.coroutines.awaitCancellation() },
                onValue = { error("No value expected") },
                onFailure = { failureCallbackCalled = true },
            )
        }

        runCurrent()
        collection.cancelAndJoin()

        assertEquals(
            listOf("server_list_load_attempted", "server_list_load_completed"),
            analytics.events.map { it.name },
        )
        assertEquals("cancelled", analytics.events.last().parameters["outcome"])
        assertFalse(failureCallbackCalled)
        assertTrue(analytics.exceptions.isEmpty())
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
    }

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
