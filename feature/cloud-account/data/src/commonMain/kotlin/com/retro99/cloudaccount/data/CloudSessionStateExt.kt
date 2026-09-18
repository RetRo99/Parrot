package com.retro99.cloudaccount.data

import com.retro99.cloud.implementation.CloudSessionState
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import io.github.jan.supabase.auth.status.SessionStatus

internal fun CloudSessionState.toCloudAuthState(): CloudAuthState {
    return when (val sessionStatus = status) {
        SessionStatus.Initializing -> CloudAuthState.RestoringSession
        is SessionStatus.NotAuthenticated -> accountId?.let { cloudUserId ->
            CloudAuthState.ReauthenticationRequired(CloudAccount(cloudUserId, null))
        } ?: CloudAuthState.SignedOut
        is SessionStatus.Authenticated -> sessionStatus.session.user?.toCloudAccount()
            ?.let { account -> CloudAuthState.SignedIn(account) }
            ?: CloudAuthState.SignedOut
        is SessionStatus.RefreshFailure -> accountId?.let { cloudUserId ->
            CloudAuthState.RefreshUnavailable(CloudAccount(cloudUserId, null))
        } ?: CloudAuthState.SignedOut
    }
}
