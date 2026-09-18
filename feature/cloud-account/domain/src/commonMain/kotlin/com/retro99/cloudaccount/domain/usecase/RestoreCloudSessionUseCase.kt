package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import com.retro99.user.api.UserRegistry
import kotlin.time.Clock
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class RestoreCloudSessionUseCase(
    @Provided private val accountRepository: CloudAccountRepository,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(): CloudAuthState {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val authState = accountRepository.restoreSession(localProfileId)
        check(userRegistry.getActiveProfileIdOrDefault() == localProfileId) {
            "Cloud session restored for an inactive profile"
        }
        val existingLink = profileLinkRepository.getForLocalProfile(localProfileId)
        if (authState is CloudAuthState.SignedIn && existingLink == null) {
            pendingAuthenticationRepository.save(
                PendingCloudAuthentication(
                    localProfileId = localProfileId,
                    cloudUserId = authState.account.id,
                    email = authState.account.email,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
        return authState
    }
}
