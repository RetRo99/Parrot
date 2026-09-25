package com.retro99.login.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.flatMap
import com.github.michaelbull.result.onFailure
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
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
    @Provided private val analytics: Analytics,
    @Provided private val serverRegistry: ServerRegistry,
) : LoginRepository {

    override suspend fun login(
        serverType: ServerType,
        serverUrl: String,
        username: String,
        password: String,
        existingServerId: String?,
    ): CompletableResult {
        val existingServer = existingServerId?.let { serverId ->
            serverRegistry.getServer(serverId)
                ?: return Err(AppError.AuthError("Saved server connection was not found"))
        }
        if (existingServer != null && existingServer.type != ServerType.Storyteller) {
            return Err(
                AppError.AuthError("Only Storyteller connections can be reauthenticated here"),
            )
        }
        if (existingServer != null && serverType != existingServer.type) {
            return Err(AppError.AuthError("Server type does not match the saved connection"))
        }

        val loginUrl = existingServer?.baseUrl ?: serverUrl
        val authenticator = authenticatorFactory.create(existingServer?.type ?: serverType)

        return authenticator.login(loginUrl, username, password)
            .onFailure { error ->
                // Never log serverUrl for privacy - only log error type
                analytics.logException(
                    error.toThrowable(),
                    "LoginRepository: Login failed | errorType=${error::class.simpleName}"
                )
            }
            .flatMap { credentials ->
                val serverId = if (existingServer != null) {
                    if (serverRegistry.getServer(existingServer.id) != existingServer) {
                        return@flatMap Err(
                            AppError.AuthError("Saved server connection changed during login"),
                        )
                    }
                    existingServer.id
                } else {
                    serverRegistry.addServer(
                        name = serverType.displayName,
                        type = serverType,
                        baseUrl = loginUrl,
                    ).id
                }
                serverRegistry.saveCredentials(credentials.copy(serverId = serverId))

                Ok(Unit)
            }
    }

    override suspend fun loginWithOAuth(
        serverType: ServerType,
        serverUrl: String,
    ): CompletableResult {
        if (serverType != ServerType.Storyteller) {
            return Err(AppError.AuthError("OAuth login is only supported for Storyteller servers"))
        }

        val authenticator = authenticatorFactory.create(serverType)

        return storytellerOAuthSessionLauncher.requestAppToken(serverUrl)
            .flatMap { appToken ->
                authenticator.loginWithAppToken(serverUrl, appToken)
            }
            .onFailure { error ->
                analytics.logEvent(
                    AuthAnalyticsEvent.OAuthLoginStepFailed(
                        step = "oauth_flow",
                        errorType = error::class.simpleName ?: "AppError",
                    )
                )
                analytics.logException(
                    error.toThrowable(),
                    "LoginRepository: OAuth login failed | errorType=${error::class.simpleName}"
                )
            }
            .flatMap { credentials ->
                val serverConfig = serverRegistry.addServer(
                    name = serverType.displayName,
                    type = serverType,
                    baseUrl = serverUrl,
                )
                serverRegistry.saveCredentials(credentials.copy(serverId = serverConfig.id))

                Ok(Unit)
            }
    }
}
