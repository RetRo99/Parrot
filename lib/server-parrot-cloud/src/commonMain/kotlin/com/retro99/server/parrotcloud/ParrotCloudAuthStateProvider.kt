package com.retro99.server.parrotcloud

import com.retro99.base.server.ServerType
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.server.api.AuthError
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerAuthStateProvider
import com.retro99.server.api.ServerConfig
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

@Single(binds = [ServerAuthStateProvider::class])
class ParrotCloudAuthStateProvider(
    @Provided private val accountRepository: CloudAccountRepository,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val userRegistry: UserRegistry,
) : ServerAuthStateProvider {
    override val serverType: ServerType = ServerType.ParrotCloud

    override fun observeAuthState(server: ServerConfig): Flow<ServerAuthState> {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        return combine(
            accountRepository.observeAuthState(),
            profileLinkRepository.observeForLocalProfile(localProfileId),
        ) { authState, link ->
            authState.toServerAuthState(server.id, link?.cloudUserId)
        }
    }

    override suspend fun isAuthenticated(server: ServerConfig): Boolean {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val link = profileLinkRepository.getForLocalProfile(localProfileId)
        val authState = accountRepository.currentAuthState()
        return authState is CloudAuthState.SignedIn && link?.cloudUserId == authState.account.id
    }

    override suspend fun clearAuthentication(server: ServerConfig) {
        accountRepository.signOut(userRegistry.getActiveProfileIdOrDefault())
    }
}

private fun CloudAuthState.toServerAuthState(
    serverId: String,
    linkedCloudUserId: String?,
): ServerAuthState {
    val authenticatedAt = Clock.System.now().toEpochMilliseconds()
    return when (this) {
        is CloudAuthState.SignedIn -> {
            if (account.id != linkedCloudUserId) {
                ServerAuthState.NotAuthenticated(serverId)
            } else {
                ServerAuthState.Authenticated(
                    serverId = serverId,
                    username = account.email ?: account.id,
                    authenticatedAt = authenticatedAt,
                )
            }
        }

        is CloudAuthState.ReauthenticationRequired,
        is CloudAuthState.RefreshUnavailable,
        -> ServerAuthState.AuthenticationFailed(
            serverId = serverId,
            error = AuthError.TokenRefreshFailed,
            failedAt = authenticatedAt,
        )

        else -> ServerAuthState.NotAuthenticated(serverId)
    }
}
