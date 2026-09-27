package com.retro99.login.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.flatMap
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.login.data.oauth.StorytellerOAuthSessionLauncher
import com.retro99.login.domain.LoginRepository
import com.retro99.server.api.ServerAuthenticatorFactory
import com.retro99.server.api.ServerRegistry
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LoginRepository::class])
internal class LoginDataRepository(
    @Provided private val authenticatorFactory: ServerAuthenticatorFactory,
    @Provided private val storytellerOAuthSessionLauncher: StorytellerOAuthSessionLauncher,
    @Provided private val serverRegistry: ServerRegistry,
) : LoginRepository {

    override suspend fun login(
        serverType: ServerType,
        serverUrl: String,
        username: String,
        password: String,
        existingServerId: String?,
    ): CompletableResult {
        val existingServerError = validateExistingServer(
            serverId = existingServerId,
            serverType = serverType,
            serverUrl = serverUrl,
        )
        if (existingServerError != null) return Err(existingServerError)
        val authenticator = authenticatorFactory.create(serverType)

        return authenticator.login(serverUrl, username, password)
            .flatMap { credentials ->
                persistLoginCredentials(
                    credentials = credentials,
                    addServer = {
                        serverRegistry.addServer(
                            name = serverType.displayName,
                            type = serverType,
                            baseUrl = serverUrl,
                        )
                    },
                    saveCredentials = serverRegistry::saveCredentials,
                    removeServer = serverRegistry::removeServer,
                    existingServerId = existingServerId,
                )
            }
    }

    override suspend fun loginWithOAuth(
        serverType: ServerType,
        serverUrl: String,
        existingServerId: String?,
    ): CompletableResult {
        if (serverType != ServerType.Storyteller) {
            return Err(AppError.AuthError("OAuth login is only supported for Storyteller servers"))
        }

        val existingServerError = validateExistingServer(
            serverId = existingServerId,
            serverType = serverType,
            serverUrl = serverUrl,
        )
        if (existingServerError != null) return Err(existingServerError)

        val authenticator = authenticatorFactory.create(serverType)

        return storytellerOAuthSessionLauncher.requestAppToken(serverUrl)
            .flatMap { appToken ->
                authenticator.loginWithAppToken(serverUrl, appToken)
            }
            .flatMap { credentials ->
                persistLoginCredentials(
                    credentials = credentials,
                    addServer = {
                        serverRegistry.addServer(
                            name = serverType.displayName,
                            type = serverType,
                            baseUrl = serverUrl,
                        )
                    },
                    saveCredentials = serverRegistry::saveCredentials,
                    removeServer = serverRegistry::removeServer,
                    existingServerId = existingServerId,
                )
            }
    }

    override suspend fun getServerConfig(serverId: String) = serverRegistry.getServer(serverId)

    private suspend fun validateExistingServer(
        serverId: String?,
        serverType: ServerType,
        serverUrl: String,
    ): AppError.AuthError? {
        if (serverId == null) return null
        val existingServer = serverRegistry.getServer(serverId)
            ?: return AppError.AuthError("This server is no longer available. Return and try again.")
        return if (
            existingServer.type == serverType &&
            existingServer.baseUrl.trimEnd('/') == serverUrl.trimEnd('/')
        ) {
            null
        } else {
            AppError.AuthError("This server has changed. Return and try again.")
        }
    }
}
