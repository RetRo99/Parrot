package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DeleteCloudAccountUseCase(
    @Provided private val accountRepository: CloudAccountRepository,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke() {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        accountRepository.withProfileSession(localProfileId) {
            accountRepository.deleteAccount(localProfileId)
        }
        profileLinkRepository.unlink(localProfileId)
        pendingAuthenticationRepository.clear(localProfileId)
    }
}
