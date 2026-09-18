package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import com.retro99.user.api.UserRegistry
import kotlin.time.Clock
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class RegisterCloudAccountUseCase(
    @Provided private val accountRepository: CloudAccountRepository,
    @Provided private val pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(email: String, password: String): CloudRegistrationResult {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        return accountRepository.withProfileSession(localProfileId) {
            val result = accountRepository.register(localProfileId, email, password)
            if (result is CloudRegistrationResult.SignedIn) {
                pendingAuthenticationRepository.save(
                    PendingCloudAuthentication(
                        localProfileId = localProfileId,
                        cloudUserId = result.account.id,
                        createdAt = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
            result
        }
    }
}
