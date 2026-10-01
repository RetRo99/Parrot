package com.retro99.server.parrotcloud

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.isActiveFor
import com.retro99.server.api.ParrotCloudLibraryState
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Parrot Cloud is active when the signed-in account is the one linked to this profile. */
@Single(binds = [ParrotCloudLibraryState::class])
class ParrotCloudLibraryStateImpl(
    @Provided private val cloudAccountRepository: CloudAccountRepository,
    @Provided private val cloudProfileLinkRepository: CloudProfileLinkRepository,
    @Provided private val userRegistry: UserRegistry,
) : ParrotCloudLibraryState {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeIsActive(): Flow<Boolean> = userRegistry.observeActiveProfile()
        .map { profile -> profile?.id ?: userRegistry.getActiveProfileIdOrDefault() }
        .distinctUntilChanged()
        .flatMapLatest { localProfileId ->
            combine(
                cloudAccountRepository.observeAuthState(),
                cloudProfileLinkRepository.observeForLocalProfile(localProfileId),
            ) { authState, profileLink -> profileLink.isActiveFor(authState) }
        }
        .distinctUntilChanged()
}
