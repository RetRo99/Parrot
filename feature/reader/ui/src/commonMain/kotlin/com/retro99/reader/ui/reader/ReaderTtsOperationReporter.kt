package com.retro99.reader.ui.reader

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.navigator.TtsPlaybackOperation
import org.koin.core.annotation.Scope

/**
 * The outcomes of a playback attempt that have already been reported.
 *
 * Reader-scoped, so every reader screen open on one book shares one of these. Each screen
 * collects the controller's `playbackOperations` for itself, and the flow gives every
 * subscriber every event, so one start used to be reported once per live screen — two
 * byte-identical `succeeded` events and two breadcrumbs under one correlation id
 * (QA-BUG-0100, TTS-F10).
 *
 * Reader screens live on the main thread, so this is not synchronised.
 */
@Scope(ReaderScope::class)
class TtsPlaybackOperationReports {

    private val reported = ArrayDeque<String>()

    /** True the first time this outcome of this attempt is offered, false after that. */
    fun claim(correlationId: String, outcome: String): Boolean {
        // Today's behaviour, so the test can be seen failing: every collector reports.
        reported.addLast("$correlationId/$outcome")
        return true
    }

    private companion object {
        /**
         * An attempt has one `attempted` and one terminal outcome, so this remembers the
         * last 32 attempts of a reading session and cannot grow past it. A reader has one
         * attempt in flight; by the time the 33rd has been and gone, no collector is still
         * going to offer the first one.
         */
        const val MAX_REMEMBERED = 64
    }
}

/**
 * One analytics event and one diagnostic breadcrumb per attempted and per terminal outcome,
 * however many reader screens are collecting the same controller.
 *
 * Separate from [ReaderViewModel] so it can be tested: the ViewModel takes more than forty
 * dependencies, while this takes the shared record of what has been reported and analytics.
 * What the operation does to the screen — the failure banner, the usage session — stays with
 * the screen, because each screen has its own.
 */
internal class ReaderTtsOperationReporter(
    private val reports: TtsPlaybackOperationReports,
    private val analytics: Analytics,
) {

    fun report(operation: TtsPlaybackOperation) {
        val action = operation.action.analyticsValue
        val outcome = when (operation) {
            is TtsPlaybackOperation.Attempted -> "attempted"
            is TtsPlaybackOperation.Succeeded -> "succeeded"
            is TtsPlaybackOperation.Failed -> "failed"
            is TtsPlaybackOperation.Cancelled -> "cancelled"
        }
        if (!reports.claim(operation.correlationId, outcome)) return

        val stage = if (operation is TtsPlaybackOperation.Attempted) "start" else "terminal"
        val durationMs = when (operation) {
            is TtsPlaybackOperation.Attempted -> null
            is TtsPlaybackOperation.Succeeded -> operation.durationMs
            is TtsPlaybackOperation.Failed -> operation.durationMs
            is TtsPlaybackOperation.Cancelled -> operation.durationMs
        }
        val reasonCode = when (operation) {
            is TtsPlaybackOperation.Failed -> operation.reasonCode.analyticsValue
            is TtsPlaybackOperation.Cancelled -> operation.reasonCode.analyticsValue
            else -> null
        }
        analytics.logEvent(
            ReaderAnalyticsEvent.TtsPlaybackOperation(
                action = action,
                outcome = outcome,
                isRetry = operation.isRetry,
                durationMs = durationMs,
                reasonCode = reasonCode,
            ),
        )
        val context = DiagnosticContext(
            screen = "reader",
            sourceScreen = "reader",
            entryPoint = action,
            action = "start_tts_playback",
            operation = "tts_playback",
            stage = stage,
            outcome = outcome,
            reasonCode = reasonCode,
            mediaType = "ebook",
            correlationId = operation.correlationId,
        )
        val error = (operation as? TtsPlaybackOperation.Failed)?.error
        if (error != null) {
            analytics.logException(error, context)
        } else {
            analytics.logBreadcrumb(context)
        }
    }
}
