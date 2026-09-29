package com.retro99.reader.ui.reader

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.model.ReaderTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReaderSettingsSaveOperationTest {

    @Test
    fun readerCallerReportsReturnedDatabaseFailureWithBoundedContext() = runTest {
        val analytics = ReaderSettingsRecordingAnalytics()
        val failure = IllegalStateException("private database detail")
        val failedSettings = ReaderSettingsDomainModel().copy(ttsEnabled = true)
        var attemptedSettings: ReaderSettingsDomainModel? = null

        val result = persistReaderSettingsUpdateFromReader(
            currentSettings = ReaderSettingsDomainModel(),
            update = { it.copy(ttsEnabled = true) },
            saveSettings = { settings ->
                attemptedSettings = settings
                com.github.michaelbull.result.Err(
                    AppError.DatabaseError(failure, table = "reader_settings"),
                )
            },
            analytics = analytics,
        )

        assertEquals(failedSettings, attemptedSettings)
        assertTrue(result?.isErr == true)
        assertEquals(1, analytics.exceptions.size)
        assertSame(failure, analytics.exceptions.single().throwable)
        assertEquals(1, analytics.breadcrumbs.size)
        val context = assertNotNull(analytics.exceptions.single().context)
        assertEquals("reader", context.screen)
        assertEquals("reader", context.sourceScreen)
        assertEquals("reader_settings_control", context.entryPoint)
        assertEquals("save_reader_setting", context.action)
        assertEquals("reader_settings_save", context.operation)
        assertEquals("terminal", context.stage)
        assertEquals("failed", context.outcome)
        assertEquals("database_error", context.reasonCode)
        assertTrue(context.correlationId?.isNotBlank() == true)
        assertEquals(context, analytics.breadcrumbs.single())
        assertFalse(context.toString().contains("private database detail"))
    }

    @Test
    fun successDoesNotEmitFailureDiagnostics() = runTest {
        val analytics = ReaderSettingsRecordingAnalytics()
        val savedSettings = ReaderSettingsDomainModel().copy(theme = ReaderTheme.SEPIA)

        val result = persistReaderSettingsUpdateFromReader(
            currentSettings = ReaderSettingsDomainModel(),
            update = { it.copy(theme = ReaderTheme.SEPIA) },
            saveSettings = { settings ->
                assertEquals(savedSettings, settings)
                com.github.michaelbull.result.Ok(Unit)
            },
            analytics = analytics,
        )

        assertTrue(result?.isOk == true)
        assertTrue(analytics.exceptions.isEmpty())
        assertTrue(analytics.breadcrumbs.isEmpty())
    }

    @Test
    fun thrownCoroutineCancellationPropagatesWithoutFailureReport() = runTest {
        val analytics = ReaderSettingsRecordingAnalytics()
        val cancellation = CancellationException("scope cancelled")

        val thrown = runCatching {
            persistReaderSettingsUpdateFromReader(
                currentSettings = ReaderSettingsDomainModel(),
                update = { it.copy(ttsEnabled = true) },
                saveSettings = { throw cancellation },
                analytics = analytics,
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertTrue(analytics.exceptions.isEmpty())
        assertTrue(analytics.breadcrumbs.isEmpty())
    }

    @Test
    fun returnedOrdinaryCancellationDoesNotReportCrashlyticsException() = runTest {
        val analytics = ReaderSettingsRecordingAnalytics()

        val result = persistReaderSettingsUpdateFromReader(
            currentSettings = ReaderSettingsDomainModel(),
            update = { it.copy(ttsEnabled = true) },
            saveSettings = {
                com.github.michaelbull.result.Err(
                    AppError.AuthError("cancelled", isCancellation = true),
                )
            },
            analytics = analytics,
        )

        assertTrue(result?.isErr == true)
        assertTrue(analytics.exceptions.isEmpty())
        assertEquals("cancelled", analytics.breadcrumbs.single().outcome)
        assertEquals("cancelled", analytics.breadcrumbs.single().reasonCode)
        assertTrue(analytics.breadcrumbs.single().correlationId?.isNotBlank() == true)
    }
}

private class ReaderSettingsRecordingAnalytics : Analytics {
    data class ExceptionRecord(
        val throwable: Throwable,
        val context: DiagnosticContext?,
    )

    val breadcrumbs = mutableListOf<DiagnosticContext>()
    val exceptions = mutableListOf<ExceptionRecord>()

    override fun logException(throwable: Throwable, message: String?) {
        exceptions += ExceptionRecord(throwable, null)
    }

    override fun logException(throwable: Throwable, context: DiagnosticContext) {
        exceptions += ExceptionRecord(throwable, context)
    }

    override fun logBreadcrumb(context: DiagnosticContext) {
        breadcrumbs += context
    }

    override fun logEvent(event: AnalyticsEvent) = Unit

    override fun setUserId(userId: String?) = Unit
}
