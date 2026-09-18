package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class GetCloudProfileLinkUseCase(
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(): CloudProfileLink? {
        return profileLinkRepository.getForLocalProfile(
            userRegistry.getActiveProfileIdOrDefault(),
        )
    }
}
