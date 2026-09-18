package com.retro99.cloudaccount.domain.usecase

import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ActivateCloudProfileUseCase(
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(localProfileId: String) {
        checkNotNull(userRegistry.getProfile(localProfileId)) {
            "Cloud account is linked to a missing local profile"
        }
        userRegistry.setActiveProfile(localProfileId)
    }
}
