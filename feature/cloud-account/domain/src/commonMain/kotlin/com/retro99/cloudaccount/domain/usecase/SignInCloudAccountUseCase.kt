package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import com.retro99.user.api.UserRegistry
import kotlin.time.Clock
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class SignInCloudAccountUseCase(
    @Provided private val accountRepository: CloudAccountRepository,
    @Provided private val pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(email: String, password: String): CloudAccount {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        return accountRepository.withProfileSession(localProfileId) {
            val account = accountRepository.signIn(localProfileId, email, password)
            check(userRegistry.getActiveProfileIdOrDefault() == localProfileId) {
                "Cloud sign-in completed for an inactive profile"
            }
            pendingAuthenticationRepository.save(
                PendingCloudAuthentication(
                    localProfileId = localProfileId,
                    cloudUserId = account.id,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            account
        }
    }
}
