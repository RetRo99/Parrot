package com.retro99.login.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.login.domain.LoginRepository
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
        return loginRepository.login(
            serverType = serverType,
            serverUrl = serverUrl,
            username = username,
            password = password,
            existingServerId = existingServerId,
        )
    }

    suspend fun withOAuth(
        serverType: ServerType,
        serverUrl: String,
    ): CompletableResult {
        return loginRepository.loginWithOAuth(serverType, serverUrl)
    }
}
