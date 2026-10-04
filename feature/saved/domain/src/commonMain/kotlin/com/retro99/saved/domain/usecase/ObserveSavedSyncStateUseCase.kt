package com.retro99.saved.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.saved.domain.SavedItemsRepository
import com.retro99.saved.domain.model.SavedSyncState
import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ObserveSavedSyncStateUseCase(
    @Provided private val cloudAccountRepository: CloudAccountRepository,
    @Provided private val syncRepository: SyncRepository,
    @Provided private val savedItemsRepository: SavedItemsRepository,
) {
    operator fun invoke(): Flow<SavedSyncState> = combine(
        cloudAccountRepository.observeAuthState(),
        syncRepository.observeStatus(),
        savedItemsRepository.observePendingSyncCount(),
    ) { auth, status, pending -> savedSyncState(auth, status, pending) }
        .distinctUntilChanged()
}

internal fun savedSyncState(
    auth: CloudAuthState,
    status: SyncStatus,
    pendingCount: Long,
): SavedSyncState = when (auth) {
    CloudAuthState.RestoringSession -> SavedSyncState.Unknown
    CloudAuthState.SignedOut,
    is CloudAuthState.AwaitingEmailVerification,
    -> SavedSyncState.SignedOut
    is CloudAuthState.SignedIn,
    is CloudAuthState.ReauthenticationRequired,
    is CloudAuthState.RefreshUnavailable,
    -> when {
        pendingCount == 0L -> SavedSyncState.Synced(status.lastSyncedAt())
        status is SyncStatus.Offline || status is SyncStatus.Failed -> SavedSyncState.WaitingForNetwork
        else -> SavedSyncState.Syncing
    }
}

private fun SyncStatus.lastSyncedAt(): String? = when (this) {
    is SyncStatus.Idle -> lastSuccessfulAt
    is SyncStatus.Completed -> completedAt
    else -> null
}
