package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class LinkCloudAccountUseCase(
    @Provided private val accountRepository: CloudAccountRepository,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val pendingAuthenticationRepository: PendingCloudAuthenticationRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(): CloudProfileLinkResult {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        return accountRepository.withProfileSession(localProfileId) {
            check(userRegistry.getActiveProfileIdOrDefault() == localProfileId) {
                "Cloud linking started for an inactive profile"
            }
            val state = accountRepository.currentAuthState()
            val account = (state as? CloudAuthState.SignedIn)?.account
                ?: error("A signed-in cloud account is required")
            val pendingAuthentication = pendingAuthenticationRepository.get(localProfileId)
            check(pendingAuthentication != null && pendingAuthentication.matches(account)) {
                "Cloud account authentication does not match the active profile"
            }
            val result = profileLinkRepository.link(localProfileId, account.id)
            if (result is CloudProfileLinkResult.Linked) {
                pendingAuthenticationRepository.clear(localProfileId)
            }
            result
        }
    }

    private fun PendingCloudAuthentication.matches(account: CloudAccount): Boolean {
        if (cloudUserId != null) return cloudUserId == account.id
        if (email == null) return true
        return account.email?.equals(email, ignoreCase = true) == true
    }
}
