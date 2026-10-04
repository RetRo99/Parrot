package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountException
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.CloudRecapsRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.user.api.UserRegistry
import kotlin.coroutines.cancellation.CancellationException
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DeleteCloudAccountUseCase(
    @Provided private val accountRepository: CloudAccountRepository,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    @Provided private val cloudRecapsRepository: CloudRecapsRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke() {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        // Captured before the account goes away: afterwards nothing can prove
        // which account's recap data this device must drop.
        val deletedAccountId = accountRepository.withProfileSession(localProfileId) {
            val sessionAccount =
                (accountRepository.currentAuthState() as? CloudAuthState.SignedIn)?.account?.id
            accountRepository.deleteAccount(localProfileId)
            sessionAccount
        } ?: profileLinkRepository.getForLocalProfile(localProfileId)?.cloudUserId
        profileLinkRepository.unlink(localProfileId)
        pendingAuthenticationRepository.clear(localProfileId)
        if (deletedAccountId == null) return
        try {
            // The account and its cloud recaps are gone. Drop the local caches,
            // queued read text and consent so this device keeps nothing of it.
            cloudRecapsRepository.purgeAccountData(deletedAccountId, localProfileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The deletion itself succeeded; only its cleanup on this device
            // failed, and the caller must report exactly that.
            throw CloudAccountException.LocalStatePersistence(e, cleanupFailed = true)
        }
    }
}
