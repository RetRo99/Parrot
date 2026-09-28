package com.retro99.analytics.implementation

import kotlin.coroutines.cancellation.CancellationException

/** Deletes all requested diagnostic files and verifies the privacy-sensitive postcondition. */
internal fun clearDiagnosticLogFiles(
    paths: List<String>,
    exists: (String) -> Boolean,
    delete: (String) -> Boolean,
) {
    try {
        paths.forEach { path ->
            if (exists(path)) {
                delete(path)
                check(!exists(path)) { FAILURE_MESSAGE }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Do not let platform exception messages leak private file paths into diagnostics.
        throw IllegalStateException(FAILURE_MESSAGE)
    }
}

private const val FAILURE_MESSAGE = "Diagnostic logs could not be cleared"
