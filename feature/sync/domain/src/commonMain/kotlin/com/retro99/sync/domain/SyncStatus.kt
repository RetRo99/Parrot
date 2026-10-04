package com.retro99.sync.domain

sealed interface SyncStatus {
    data object Disabled : SyncStatus

    data class Idle(
        val lastSuccessfulAt: String? = null,
        val pendingCount: Int = 0,
    ) : SyncStatus

    data class Running(
        val phase: SyncPhase,
        val completedItems: Int = 0,
        val totalItems: Int? = null,
        val bytesTransferred: Long = 0,
        val totalBytes: Long? = null,
    ) : SyncStatus

    data class Offline(
        val pendingCount: Int,
        val lastSuccessfulAt: String? = null,
    ) : SyncStatus

    data class Failed(
        val error: String,
        val pendingCount: Int,
        val canRetry: Boolean,
        val lastSuccessfulAt: String? = null,
    ) : SyncStatus

    data class Completed(
        val pushedCount: Int,
        val pulledCount: Int,
        val pendingCount: Int,
        val completedAt: String,
    ) : SyncStatus
}

enum class SyncPhase {
    PREPARING,
    PULLING,
    APPLYING,
    UPLOADING_CHANGES,
    UPLOADING_FILES,
    DOWNLOADING_FILES,
    TRANSFERRING_FILES,
    FINALIZING,
}

typealias SyncPhaseReporter = (
    phase: SyncPhase,
    completedItems: Int,
    totalItems: Int?,
) -> Unit
