package com.retro99.sync.domain

sealed interface SyncResult {
    data class Completed(
        val pushedMutationCount: Int,
        val pulledChangeCount: Int,
        val pendingMutationCount: Int,
    ) : SyncResult

    data object NotConfigured : SyncResult

    data object NotAuthenticated : SyncResult

    data object ProfileNotLinked : SyncResult

    data object SyncDisabled : SyncResult

    data class Failed(val message: String) : SyncResult
}
