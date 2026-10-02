package com.retro99.reader.data.recap

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext

/** Recap breadcrumbs: codes and statuses only, never excerpt or summary text. */
class RecapDiagnostics(private val analytics: Analytics) {

    fun breadcrumb(stage: String, outcome: String, reasonCode: String? = null) {
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "reader",
                operation = OPERATION,
                stage = stage,
                outcome = outcome.lowercase(),
                reasonCode = reasonCode?.lowercase(),
            ),
        )
    }

    fun failure(throwable: Throwable, stage: String) {
        analytics.logException(
            throwable,
            DiagnosticContext(screen = "reader", operation = OPERATION, stage = stage),
        )
    }

    private companion object {
        const val OPERATION = "session_recap"
    }
}
