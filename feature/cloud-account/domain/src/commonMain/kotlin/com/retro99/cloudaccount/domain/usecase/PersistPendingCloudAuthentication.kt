package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountException
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Rolls back auth state if its required local completion marker cannot be persisted. */
internal suspend fun persistPendingCloudAuthentication(
    accountRepository: CloudAccountRepository,
    pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    authentication: PendingCloudAuthentication,
) {
    try {
        pendingAuthenticationRepository.save(authentication)
    } catch (cancellation: CancellationException) {
        withContext(NonCancellable) {
            cleanupPendingAuthentication(
                accountRepository = accountRepository,
                pendingAuthenticationRepository = pendingAuthenticationRepository,
                authentication = authentication,
            )
        }
        throw cancellation
    } catch (persistenceFailure: Exception) {
        val cleanupFailed = withContext(NonCancellable) {
            cleanupPendingAuthentication(
                accountRepository = accountRepository,
                pendingAuthenticationRepository = pendingAuthenticationRepository,
                authentication = authentication,
            )
        }
        throw CloudAccountException.LocalStatePersistence(
            cause = persistenceFailure,
            cleanupFailed = cleanupFailed,
        )
    }
}

private suspend fun cleanupPendingAuthentication(
    accountRepository: CloudAccountRepository,
    pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    authentication: PendingCloudAuthentication,
): Boolean {
    var cleanupFailed = false
    try {
        accountRepository.signOut(authentication.localProfileId)
    } catch (_: Exception) {
        cleanupFailed = true
    }
    try {
        pendingAuthenticationRepository.clear(authentication.localProfileId)
    } catch (_: Exception) {
        cleanupFailed = true
    }
    return cleanupFailed
}
