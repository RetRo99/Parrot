package com.retro99.sync.domain

sealed interface SyncStatus {
    data object Idle : SyncStatus

    data class Synchronizing(
        val request: SyncRequest,
    ) : SyncStatus

    data object UpToDate : SyncStatus

    data class Pending(
        val pendingMutationCount: Int,
    ) : SyncStatus

    data class ActionRequired(
        val reason: SyncActionRequired,
    ) : SyncStatus

    data class Failed(
        val message: String,
    ) : SyncStatus
}

enum class SyncActionRequired {
    NOT_CONFIGURED,
    NOT_AUTHENTICATED,
    PROFILE_NOT_LINKED,
    SYNC_DISABLED,
}
