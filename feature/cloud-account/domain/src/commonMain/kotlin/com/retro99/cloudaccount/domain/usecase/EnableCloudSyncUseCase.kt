package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class EnableCloudSyncUseCase(
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(): CloudProfileLink {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val profileLink = profileLinkRepository.getForLocalProfile(localProfileId)
            ?: error("A linked cloud profile is required")
        if (!profileLink.syncEnabled) {
            profileLinkRepository.setSyncEnabled(localProfileId, enabled = true)
        }
        return profileLinkRepository.getForLocalProfile(localProfileId)
            ?: profileLink.copy(syncEnabled = true)
    }
}
