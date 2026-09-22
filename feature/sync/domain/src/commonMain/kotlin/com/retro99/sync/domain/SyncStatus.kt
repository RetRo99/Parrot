package com.retro99.sync.domain

sealed interface SyncStatus {
    data object Idle : SyncStatus

    data class Synchronizing(
        val request: SyncRequest,
        val lastSuccessfulAt: String? = null,
    ) : SyncStatus

    data class UpToDate(
        val lastSuccessfulAt: String? = null,
    ) : SyncStatus

    data class Pending(
        val pendingMutationCount: Int,
        val lastSuccessfulAt: String? = null,
    ) : SyncStatus

    data class ActionRequired(
        val reason: SyncActionRequired,
        val lastSuccessfulAt: String? = null,
    ) : SyncStatus

    data class Failed(
        val message: String,
        val lastSuccessfulAt: String? = null,
    ) : SyncStatus
}

enum class SyncActionRequired {
    NOT_CONFIGURED,
    NOT_AUTHENTICATED,
    PROFILE_NOT_LINKED,
    SYNC_DISABLED,
}
