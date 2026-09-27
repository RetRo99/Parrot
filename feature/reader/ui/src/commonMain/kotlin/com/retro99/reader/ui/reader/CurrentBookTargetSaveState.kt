package com.retro99.reader.ui.reader

/** Tracks one qualifying session's target checkpoint and allows a later retry after failure. */
internal class CurrentBookTargetSaveState {
    var hasSucceeded: Boolean = false
        private set

    private var hasFailed: Boolean = false

    val isRetry: Boolean
        get() = hasFailed

    fun recordResult(succeeded: Boolean) {
        hasSucceeded = succeeded
        hasFailed = !succeeded
    }
}
