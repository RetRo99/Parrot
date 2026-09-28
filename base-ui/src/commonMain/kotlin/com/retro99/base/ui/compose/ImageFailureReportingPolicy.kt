package com.retro99.base.ui.compose

import coil3.network.HttpException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.IOException

/**
 * HTTP/network failures and cancellation are expected outcomes for optional images: Coil renders
 * its error/placeholder painter, while the surrounding screen remains usable. Keep unexpected
 * processing failures eligible for one bounded diagnostic instead.
 */
internal fun shouldReportUnexpectedImageFailure(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    repeat(MAX_CAUSE_DEPTH) {
        val cause = current ?: return true
        if (cause is CancellationException || cause is HttpException || cause is IOException) {
            return false
        }
        current = cause.cause
    }
    return true
}

internal class ImageFailureReportGate {
    private var hasReported = false

    fun shouldReport(throwable: Throwable): Boolean {
        if (hasReported || !shouldReportUnexpectedImageFailure(throwable)) return false
        hasReported = true
        return true
    }
}

private const val MAX_CAUSE_DEPTH = 16
