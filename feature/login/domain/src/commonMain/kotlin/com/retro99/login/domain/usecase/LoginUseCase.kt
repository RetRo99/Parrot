package com.retro99.login.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.login.domain.LoginRepository
import com.retro99.login.domain.ServerProbeResult
import com.retro99.server.api.ServerConfig
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class LoginUseCase(
    @Provided private val loginRepository: LoginRepository,
) {
    suspend operator fun invoke(
        serverType: ServerType,
        serverUrl: String,
        username: String,
        password: String,
        existingServerId: String? = null,
    ): CompletableResult {
        return loginRepository.login(serverType, serverUrl, username, password, existingServerId)
    }

    suspend fun withOAuth(
        serverType: ServerType,
        serverUrl: String,
        existingServerId: String? = null,
    ): CompletableResult {
        return loginRepository.loginWithOAuth(serverType, serverUrl, existingServerId)
    }

    suspend fun probeServer(
        serverUrl: String,
        preferredType: ServerType,
    ): ServerProbeResult = loginRepository.probeServer(serverUrl, preferredType)

    suspend fun getServerConfig(serverId: String): ServerConfig? =
        loginRepository.getServerConfig(serverId)
}
