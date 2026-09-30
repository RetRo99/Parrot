package com.retro99.server.storyteller

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.network.implementation.NetworkFailureClassification
import com.retro99.network.implementation.classifyNetworkFailure
import com.retro99.server.api.ServerAuthenticator
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerProbeOutcome
import com.retro99.server.api.ServerType
import com.retro99.server.api.ServerValidationResult
import com.retro99.server.storyteller.model.StorytellerAppTokenRequest
import com.retro99.server.storyteller.model.StorytellerTokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class StorytellerAuthenticator(
    private val httpClient: HttpClient,
    @Provided private val analytics: Analytics,
) : ServerAuthenticator {

    override val serverType: ServerType = ServerType.Storyteller

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerCredentials> {
        return try {
            val response = httpClient.submitForm(
                url = "${baseUrl.trimEnd('/')}/api/v2/token",
                formParameters = Parameters.build {
                    append("usernameOrEmail", username)
                    append("password", password)
                }
            )

            if (response.status.isSuccess()) {
                val tokenResponse = response.body<StorytellerTokenResponse>()
                val now = Clock.System.now().toEpochMilliseconds()
                val expiresAt = tokenResponse.expiresIn?.let { now + it }

                Ok(
                    ServerCredentials(
                        serverId = "", // Will be set by caller
                        username = username,
                        accessToken = tokenResponse.accessToken,
                        refreshToken = null, // Storyteller doesn't use refresh tokens currently
                        expiresAt = expiresAt,
                    )
                )
            } else {
                Err(loginFailure(response.status))
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
        // Storyteller doesn't support token refresh currently
        return Err(AppError.AuthError("Token refresh not supported for Storyteller"))
    }

    override suspend fun loginWithAppToken(
        baseUrl: String,
        appToken: String,
    ): AppResult<ServerCredentials> {
        return try {
            val tokenResponse = httpClient.post("${baseUrl.trimEnd('/')}/api/v2/token/app") {
                contentType(ContentType.Application.Json)
                setBody(StorytellerAppTokenRequest(appToken))
            }

            if (!tokenResponse.status.isSuccess()) {
                logOAuthFailure(
                    step = OAuthStep.AppTokenExchange,
                    errorType = "HttpStatus",
                    statusCode = tokenResponse.status.value,
                )
                return Err(AppError.AuthError("OAuth login failed: ${tokenResponse.status}"))
            }

            val username = appToken.decodeJwtSubject()
                ?: return Err(
                    AppError.AuthError("OAuth login succeeded, but the app token did not include a username")
                ).also {
                    logOAuthFailure(
                        step = OAuthStep.DecodeCallbackToken,
                        errorType = "MissingSubject",
                        statusCode = null,
                    )
                }
            val sessionToken = tokenResponse.body<StorytellerTokenResponse>()

            Ok(
                ServerCredentials(
                    serverId = "", // Will be set by caller
                    username = username,
                    accessToken = sessionToken.accessToken,
                    refreshToken = null,
                    expiresAt = null,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logOAuthFailure(
                step = OAuthStep.AppTokenLogin,
                errorType = e::class.simpleName ?: "Exception",
                statusCode = null,
            )
            Err(mapException(e))
        }
    }

    override suspend fun validateServer(baseUrl: String): AppResult<ServerValidationResult> {
        return try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/api/v2/info")

            if (response.status.isSuccess()) {
                Ok(
                    ServerValidationResult(
                        isValid = true,
                        serverVersion = null, // Could parse from response if available
                        serverName = "Storyteller",
                        errorMessage = null,
                    )
                )
            } else {
                Ok(
                    ServerValidationResult(
                        isValid = false,
                        serverVersion = null,
                        serverName = null,
                        errorMessage = "Server returned ${response.status}",
                    )
                )
            }
        } catch (e: Exception) {
            Ok(
                ServerValidationResult(
                    isValid = false,
                    serverVersion = null,
                    serverName = null,
                    errorMessage = e.message,
                )
            )
        }
    }

    override suspend fun probe(baseUrl: String): ServerProbeOutcome {
        return try {
            // Unauthenticated NextAuth endpoint; always lists a "credentials" provider.
            val response = httpClient.get("${baseUrl.trimEnd('/')}/api/v2/auth/providers")
            val body = if (response.status.isSuccess()) response.bodyAsText() else ""
            if (body.contains("\"credentials\"")) {
                ServerProbeOutcome.Match(supportsBrowserSignIn = body.hasOAuthProvider())
            } else {
                ServerProbeOutcome.Mismatch
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (classifyNetworkFailure(e).isUnreachable()) {
                ServerProbeOutcome.Unreachable
            } else {
                ServerProbeOutcome.Mismatch
            }
        }
    }

    private fun loginFailure(status: HttpStatusCode): AppError.AuthError {
        return if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            AppError.AuthError("Invalid credentials", isInvalidCredentials = true)
        } else {
            AppError.AuthError("Login failed: $status")
        }
    }

    private fun mapException(e: Exception): AppError {
        val networkFailure = classifyNetworkFailure(e)
        return when {
            e.message?.contains("401") == true ->
                AppError.AuthError("Invalid credentials", isInvalidCredentials = true)
            networkFailure.isExpectedFailure -> AppError.NetworkError(
                throwable = e,
                isConnectivity = networkFailure.isConnectivity,
                isTimeout = networkFailure.isTimeout,
                isExpectedFailure = true,
            )
            else -> AppError.UnknownError(e)
        }
    }

    private fun logOAuthFailure(
        step: OAuthStep,
        errorType: String,
        statusCode: Int?,
    ) {
        analytics.logEvent(
            AuthAnalyticsEvent.OAuthLoginStepFailed(
                step = step.analyticsName,
                errorType = errorType,
                statusCode = statusCode,
            )
        )
    }

    private enum class OAuthStep(val analyticsName: String) {
        AppTokenExchange("app_token_exchange"),
        DecodeCallbackToken("decode_callback_token"),
        AppTokenLogin("app_token_login"),
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.decodeJwtSubject(): String? {
        return runCatching {
            val payload = split('.').getOrNull(1) ?: return null
            val paddedPayload = payload.padEnd(payload.length + ((4 - payload.length % 4) % 4), '=')
            val payloadJson = Base64.UrlSafe.decode(paddedPayload).decodeToString()

            Json.parseToJsonElement(payloadJson)
                .jsonObject["sub"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

private fun NetworkFailureClassification.isUnreachable(): Boolean = isConnectivity || isTimeout

/** True when the NextAuth providers JSON lists an OAuth/OIDC provider next to credentials. */
private fun String.hasOAuthProvider(): Boolean = runCatching {
    Json.parseToJsonElement(this).jsonObject.values.any { provider ->
        provider.jsonObject["type"]?.jsonPrimitive?.contentOrNull in setOf("oauth", "oidc")
    }
}.getOrDefault(false)
