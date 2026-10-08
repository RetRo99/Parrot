package com.retro99.server.parrotcloud

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.sync.domain.ProgressAccountResolver
import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ProgressAccountResolver::class])
class ParrotCloudProgressAccountResolver(
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val userRegistry: UserRegistry,
) : ProgressAccountResolver {
    override suspend fun accountId(serverId: String): String? =
        if (serverId == LOCAL_SERVER_ID || serverId == PARROT_CLOUD_SERVER_ID) {
            profileLinkRepository.getForLocalProfile(userRegistry.getActiveProfileIdOrDefault())?.cloudUserId
        } else {
            serverId
        }
}
