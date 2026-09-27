package com.retro99.reader.ui.reader

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun persistCurrentBookTarget(
    analytics: Analytics,
    target: CurrentlyReadingDomainModel,
    entryPoint: String,
    isRetry: Boolean,
    persist: (CurrentlyReadingDomainModel) -> Unit,
): Boolean {
    val bookType = target.bookType.name.lowercase()
    val correlationId = Uuid.random().toString()
    fun context(stage: String, outcome: String, reasonCode: String? = null) = DiagnosticContext(
        screen = "reader",
        action = "save_current_book_target",
        operation = "current_book_target_save",
        entryPoint = entryPoint,
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        mediaType = bookType,
        correlationId = correlationId,
    )

    analytics.logEvent(
        ReaderAnalyticsEvent.CurrentBookTargetSaveAttempted(
            entryPoint = entryPoint,
            bookType = bookType,
            isRetry = isRetry,
        ),
    )
    analytics.logBreadcrumb(context(stage = "started", outcome = "started"))

    try {
        persist(target)
    } catch (cancellation: CancellationException) {
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "cancelled"))
        throw cancellation
    } catch (error: Exception) {
        val failureContext = context(
            stage = "terminal",
            outcome = "failed",
            reasonCode = "current_book_target_save_failed",
        )
        analytics.logEvent(
            ReaderAnalyticsEvent.CurrentBookTargetSaveFailed(
                entryPoint = entryPoint,
                bookType = bookType,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        return false
    }

    analytics.logEvent(
        ReaderAnalyticsEvent.CurrentBookTargetSaveCompleted(
            entryPoint = entryPoint,
            bookType = bookType,
            isRetry = isRetry,
        ),
    )
    analytics.logBreadcrumb(context(stage = "terminal", outcome = "succeeded"))
    return true
}
