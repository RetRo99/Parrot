package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.user.api.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileOperationTest {
    @Test
    fun duplicateProfileNameValidationEmitsOnlyBoundedValidationSignals() {
        val analytics = ProfileRecordingAnalytics()

        reportDuplicateProfileNameRejected(
            analytics = analytics,
            operation = AppSettingsAnalyticsEvent.ProfileOperation.Create,
        )

        assertEquals(listOf("profile_name_validation_failed"), analytics.events.map { it.name })
        assertEquals(
            mapOf(
                "screen" to "app_settings",
                "action" to "create_profile",
                "operation" to "profile_create",
                "stage" to "validation",
                "outcome" to "rejected",
                "reason_code" to "duplicate_name",
            ),
            analytics.events.single().parameters,
        )
        assertEquals(1, analytics.breadcrumbs.size)
        assertEquals("app_settings", analytics.breadcrumbs.single().screen)
        assertEquals("profile_create", analytics.breadcrumbs.single().operation)
        assertEquals("validation", analytics.breadcrumbs.single().stage)
        assertEquals("rejected", analytics.breadcrumbs.single().outcome)
        assertEquals("duplicate_name", analytics.breadcrumbs.single().reasonCode)
        assertTrue(analytics.exceptions.isEmpty())
        assertFalse(analytics.events.any { "profile_name" in it.parameters || "profile_id" in it.parameters })
    }

    @Test
    fun profileNameUniquenessNormalizesCaseAndSurroundingWhitespace() {
        val profiles = listOf(
            UserProfile(id = "profile-a", name = "Reader One", createdAt = 1L),
            UserProfile(id = "profile-b", name = "Reader Two", createdAt = 2L),
        )

        assertTrue(isDuplicateProfileName("  reader one ", profiles))
        assertTrue(isDuplicateProfileName("READER TWO", profiles))
        assertFalse(isDuplicateProfileName(" reader one ", profiles, excludingProfileId = "profile-a"))
        assertFalse(isDuplicateProfileName("   ", profiles))
        assertFalse(isDuplicateProfileName("A New Reader", profiles))
    }

    @Test
    fun existingNormalizedDuplicateNamesReceiveStableDistinctOrdinals() {
        val profiles = listOf(
            UserProfile(id = "profile-z", name = "  Reader One", createdAt = 1L),
            UserProfile(id = "profile-b", name = "READER ONE ", createdAt = 1L),
            UserProfile(id = "profile-unique", name = "Reader Two", createdAt = 2L),
        )

        assertEquals(
            mapOf("profile-b" to 1, "profile-z" to 2),
            duplicateProfileOrdinals(profiles),
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun profileTapShieldConsumesInputThroughTheRepeatTapWindow() = runTest {
        val shield = ProfileOperationTapShield()
        shield.block()

        val release = launch { shield.releaseAfterRepeatTapWindow() }
        assertTrue(shield.isBlocking.value)

        advanceTimeBy(ProfileOperationTapShield.DEFAULT_REPEAT_TAP_WINDOW_MILLIS - 1)
        runCurrent()
        assertTrue(shield.isBlocking.value)

        advanceTimeBy(1)
        runCurrent()
        release.join()
        assertFalse(shield.isBlocking.value)
    }

    @Test
    fun operationGateAcceptsOnlyOneMutationUntilTheAcceptedOneFinishes() {
        val gate = ProfileOperationGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertTrue(gate.isInProgress)

        gate.finish()

        assertFalse(gate.isInProgress)
        assertTrue(gate.tryStart())
    }

    @Test
    fun retryAttributionIsScopedToTheSameTargetOrDialogSession() {
        val tracker = ProfileOperationRetryTracker()
        val failedSwitch = "switch:target_a"
        val differentSwitch = "switch:target_b"

        assertFalse(tracker.isRetry(failedSwitch))
        tracker.recordFailure(failedSwitch)

        assertTrue(tracker.isRetry(failedSwitch))
        assertFalse(tracker.isRetry(differentSwitch))

        val firstCreateSession = tracker.newSessionKey(AppSettingsAnalyticsEvent.ProfileOperation.Create)
        tracker.recordFailure(firstCreateSession)
        val nextCreateSession = tracker.newSessionKey(AppSettingsAnalyticsEvent.ProfileOperation.Create)
        assertTrue(tracker.isRetry(firstCreateSession))
        assertFalse(tracker.isRetry(nextCreateSession))

        tracker.clear(firstCreateSession)
        assertFalse(tracker.isRetry(firstCreateSession))
    }

    @Test
    fun successfulRetryClearsOnlyItsOwnRetryKey() {
        val tracker = ProfileOperationRetryTracker()
        val first = "switch:target_a"
        val second = "switch:target_b"
        tracker.recordFailure(first)
        tracker.recordFailure(second)

        tracker.recordSuccess(first)

        assertFalse(tracker.isRetry(first))
        assertTrue(tracker.isRetry(second))
    }

    @Test
    fun successfulOperationEmitsOneAttemptSuccessAndCorrelatedBreadcrumbs() = runTest {
        val analytics = ProfileRecordingAnalytics()
        var executed = false

        val succeeded = executeProfileOperation(
            analytics = analytics,
            operation = AppSettingsAnalyticsEvent.ProfileOperation.Create,
            isRetry = false,
            successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileCreated(isRetry) },
        ) {
            executed = true
        }

        assertTrue(succeeded)
        assertTrue(executed)
        assertEquals(
            listOf("profile_operation_attempted", "profile_created"),
            analytics.events.map { it.name },
        )
        assertEquals(
            listOf("started" to "started", "terminal" to "succeeded"),
            analytics.breadcrumbs.map { it.stage to it.outcome },
        )
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
        assertTrue(analytics.exceptions.isEmpty())
        assertFalse(analytics.events.any { "profile_name" in it.parameters || "profile_id" in it.parameters })
    }

    @Test
    fun unexpectedFailureEmitsOneFailureReportAndKeepsBoundedContext() = runTest {
        val analytics = ProfileRecordingAnalytics()
        val failure = IllegalStateException("private profile storage detail")

        val succeeded = executeProfileOperation(
            analytics = analytics,
            operation = AppSettingsAnalyticsEvent.ProfileOperation.Switch,
            isRetry = false,
            successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileSwitched(isRetry) },
        ) {
            throw failure
        }

        assertFalse(succeeded)
        assertEquals(
            listOf("profile_operation_attempted", "profile_operation_failed"),
            analytics.events.map { it.name },
        )
        assertEquals("failed", analytics.events.last().parameters["outcome"])
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().first)
        val context = analytics.exceptions.single().second
        assertEquals("app_settings", context?.screen)
        assertEquals("switch_profile", context?.action)
        assertEquals("profile_switch", context?.operation)
        assertEquals("profile_operation_failed", context?.reasonCode)
        assertEquals(analytics.breadcrumbs.first().correlationId, analytics.breadcrumbs.last().correlationId)
    }

    @Test
    fun cancellationIsRecordedWithoutExceptionReportAndPropagates() = runTest {
        val analytics = ProfileRecordingAnalytics()
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            executeProfileOperation(
                analytics = analytics,
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Delete,
                isRetry = true,
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileDeleted(isRetry) },
            ) {
                throw cancellation
            }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(
            listOf("profile_operation_attempted", "profile_operation_cancelled"),
            analytics.events.map { it.name },
        )
        assertEquals("cancelled", analytics.breadcrumbs.last().outcome)
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun retryAttemptAndTerminalOutcomeRetainRetryAttribution() = runTest {
        val analytics = ProfileRecordingAnalytics()
        val retryTracker = ProfileOperationRetryTracker()
        val retryKey = retryTracker.newSessionKey(AppSettingsAnalyticsEvent.ProfileOperation.Rename)
        var shouldFail = true

        suspend fun submit(): Boolean {
            val succeeded = executeProfileOperation(
                analytics = analytics,
                operation = AppSettingsAnalyticsEvent.ProfileOperation.Rename,
                isRetry = retryTracker.isRetry(retryKey),
                successEvent = { isRetry -> AppSettingsAnalyticsEvent.ProfileRenamed(isRetry) },
            ) {
                if (shouldFail) error("private test failure")
            }
            if (succeeded) retryTracker.recordSuccess(retryKey) else retryTracker.recordFailure(retryKey)
            return succeeded
        }

        assertFalse(submit())
        shouldFail = false
        assertTrue(submit())

        assertEquals(
            listOf("profile_operation_attempted", "profile_operation_failed", "profile_operation_attempted", "profile_renamed"),
            analytics.events.map { it.name },
        )
        assertEquals(listOf(false, false, true, true), analytics.events.map { it.parameters["is_retry"] })
    }
}

private class ProfileRecordingAnalytics : Analytics {
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
