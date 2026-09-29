package com.retro99.analytics.api

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class ContinueReadingEntryPoint(val value: String) {
    Shelf("continue_reading_shelf"),
    FloatingBubble("floating_bubble"),
}

enum class ContinueReadingMediaType(val value: String) {
    Ebook("ebook"),
    Audiobook("audiobook"),
    ReadAloud("readaloud"),
}

class ContinueReadingOpenOperation internal constructor(
    val entryPoint: ContinueReadingEntryPoint,
    val mediaType: ContinueReadingMediaType,
    val correlationId: String,
)

fun continueReadingOpenOperation(
    entryPoint: String?,
    mediaType: String,
    correlationId: String?,
): ContinueReadingOpenOperation? {
    val source = ContinueReadingEntryPoint.values().firstOrNull { it.value == entryPoint } ?: return null
    val type = ContinueReadingMediaType.values().firstOrNull { it.value == mediaType } ?: return null
    val id = correlationId?.takeIf { SAFE_CORRELATION_ID.matches(it) } ?: return null
    return ContinueReadingOpenOperation(source, type, id)
}

@OptIn(ExperimentalUuidApi::class)
fun Analytics.beginContinueReadingOpen(
    entryPoint: ContinueReadingEntryPoint,
    mediaType: ContinueReadingMediaType,
    isRetry: Boolean = false,
): ContinueReadingOpenOperation {
    val operation = ContinueReadingOpenOperation(
        entryPoint = entryPoint,
        mediaType = mediaType,
        correlationId = Uuid.random().toString(),
    )
    logEvent(
        NavigationAnalyticsEvent.ContinueReadingOpenAttempted(
            entryPoint = entryPoint,
            mediaType = mediaType.value,
            isRetry = isRetry,
        ),
    )
    logBreadcrumb(operation.diagnosticContext(
        screen = "home",
        stage = if (isRetry) "retry" else "started",
        outcome = "started",
    ))
    return operation
}

fun Analytics.completeContinueReadingOpen(
    operation: ContinueReadingOpenOperation,
    outcome: NavigationAnalyticsEvent.ContinueReadingOpenOutcome,
    reasonCode: NavigationAnalyticsEvent.ContinueReadingOpenReasonCode? = null,
) {
    logEvent(
        NavigationAnalyticsEvent.ContinueReadingOpenCompleted(
            entryPoint = operation.entryPoint,
            mediaType = operation.mediaType.value,
            outcome = outcome,
            reasonCode = reasonCode,
        ),
    )
    logBreadcrumb(
        operation.diagnosticContext(
            screen = if (outcome == NavigationAnalyticsEvent.ContinueReadingOpenOutcome.Cancelled) "home" else "reader",
            stage = "terminal",
            outcome = outcome.value,
            reasonCode = reasonCode?.value,
        ),
    )
}

fun ContinueReadingOpenOperation.diagnosticContext(
    screen: String,
    stage: String,
    outcome: String,
    reasonCode: String? = null,
): DiagnosticContext = DiagnosticContext(
    screen = screen,
    sourceScreen = "home",
    entryPoint = entryPoint.value,
    action = "open_continue_reading",
    operation = "continue_reading_open",
    stage = stage,
    outcome = outcome,
    reasonCode = reasonCode,
    mediaType = mediaType.value,
    correlationId = correlationId,
)

private val SAFE_CORRELATION_ID = Regex("[A-Fa-f0-9-]{16,64}")
