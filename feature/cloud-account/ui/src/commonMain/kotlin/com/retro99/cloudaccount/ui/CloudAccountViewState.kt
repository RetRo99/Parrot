package com.retro99.cloudaccount.ui

import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.CloudStorageUsage
import com.retro99.sync.domain.SyncStatus

data class CloudAccountViewState(
    val authState: CloudAuthState = CloudAuthState.RestoringSession,
    val profileLink: CloudProfileLink? = null,
    val mode: CloudAccountMode = CloudAccountMode.SignIn,
    val isLoading: Boolean = true,
    val isSubmitEnabled: Boolean = false,
    val showVerificationMessage: Boolean = false,
    val showLinkConfirmation: Boolean = false,
    val showDeleteAccountConfirmation: Boolean = false,
    val showAutoBackupConfirmation: Boolean = false,
    val autoBackupRightsAttested: Boolean = false,
    val isUpdatingAutoBackup: Boolean = false,
    val error: CloudAccountError? = null,
    val syncStatus: SyncStatus = SyncStatus.Idle(),
    val storageUsage: CloudStorageUsage? = null,
    val isLoadingStorageUsage: Boolean = false,
    val storageUsageError: String? = null,
)

enum class CloudAccountMode {
    SignIn,
    CreateAccount,
}

enum class CloudAccountError {
    ProfileAlreadyLinked,
    NotConfigured,
    Generic,
}
