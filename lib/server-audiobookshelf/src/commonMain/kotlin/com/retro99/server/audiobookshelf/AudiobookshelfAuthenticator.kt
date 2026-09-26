package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.network.implementation.classifyNetworkFailure
import com.retro99.server.api.ServerAuthenticator
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerType
import com.retro99.server.api.ServerValidationResult
import com.retro99.server.audiobookshelf.model.AudiobookshelfLoginRequest
import com.retro99.server.audiobookshelf.model.AudiobookshelfLoginResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.koin.core.annotation.Factory
import kotlin.coroutines.cancellation.CancellationException

@Factory
class AudiobookshelfAuthenticator(
    private val httpClient: HttpClient,
) : ServerAuthenticator {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerCredentials> {
        return try {
            val response = httpClient.post("${baseUrl.trimEnd('/')}/login") {
                contentType(ContentType.Application.Json)
                setBody(AudiobookshelfLoginRequest(username, password))
            }

            if (response.status.isSuccess()) {
                val loginResponse = response.body<AudiobookshelfLoginResponse>()
                Ok(
                    ServerCredentials(
                        serverId = "",
                        username = username,
                        accessToken = loginResponse.user.token,
                        refreshToken = null,
                        expiresAt = null,
                    ),
                )
            } else {
                Err(AppError.AuthError("Login failed: ${response.status}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Err(mapException(e))
        }
    }

    override suspend fun refreshToken(
        baseUrl: String,
        refreshToken: String,
    ): AppResult<ServerCredentials> {
        return Err(AppError.AuthError("Token refresh not supported for Audiobookshelf"))
    }

    override suspend fun validateServer(baseUrl: String): AppResult<ServerValidationResult> {
        return try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/ping")

            if (response.status.isSuccess()) {
                Ok(
                    ServerValidationResult(
                        isValid = true,
                        serverVersion = null,
                        serverName = "Audiobookshelf",
                        errorMessage = null,
                    ),
                )
            } else {
                Ok(
                    ServerValidationResult(
                        isValid = false,
                        serverVersion = null,
                        serverName = null,
                        errorMessage = "Server returned ${response.status}",
                    ),
                )
            }
        } catch (e: Exception) {
            Ok(
                ServerValidationResult(
                    isValid = false,
                    serverVersion = null,
                    serverName = null,
                    errorMessage = e.message,
                ),
            )
        }
    }

    private fun mapException(e: Exception): AppError {
        val networkFailure = classifyNetworkFailure(e)
        return when {
            e.message?.contains("401") == true -> AppError.AuthError("Invalid credentials")
            networkFailure.isExpectedFailure -> AppError.NetworkError(
                throwable = e,
                isConnectivity = networkFailure.isConnectivity,
                isTimeout = networkFailure.isTimeout,
                isExpectedFailure = true,
            )
            else -> AppError.UnknownError(e)
        }
    }
}
