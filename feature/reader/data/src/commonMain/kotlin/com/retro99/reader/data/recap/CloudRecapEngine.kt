package com.retro99.reader.data.recap

import com.retro99.reader.domain.recap.RecapEngine
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapInput
import com.retro99.reader.domain.recap.RecapLanguages
import com.retro99.reader.domain.recap.RecapLimits
import com.retro99.reader.domain.recap.RecapResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.fromHttpToGmtDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Calls the generate-recap Edge Function as the signed-in user. All auth,
 * HTTP and API error mapping stays here; callers only see [RecapResult].
 * Never logs or keeps the excerpt or the summary.
 */
class CloudRecapEngine(
    private val endpoint: RecapEndpoint,
    private val auth: RecapAuthTokens,
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.System,
) : RecapEngine {

    override val id: String = RecapEngine.CLOUD_ENGINE_ID

    override suspend fun generate(input: RecapInput): RecapResult {
        if (!endpoint.isConfigured) return RecapResult.Retryable(RecapErrorCode.SERVICE_UNAVAILABLE)
        val excerpt = input.excerpt.take(RecapLimits.MAX_EXCERPT_CHARS)
        // The server answers 422 below this; don't spend a request on it.
        if (excerpt.trim().length < MIN_EXCERPT_CHARS) {
            return RecapResult.Permanent(RecapErrorCode.EXCERPT_TOO_SHORT)
        }
        val body = requestBody(excerpt, input)

        val token = auth.accessToken() ?: return RecapResult.AuthRequired
        return try {
            var response = post(body, token)
            if (response.status.value == 401) {
                // Expired access token: refresh once, then give up until sign-in.
                val refreshed = auth.refreshedAccessToken() ?: return RecapResult.AuthRequired
                response = post(body, refreshed)
                if (response.status.value == 401) return RecapResult.AuthRequired
            }
            map(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            RecapResult.Retryable(RecapErrorCode.TIMEOUT)
        } catch (e: Exception) {
            if (e.isTimeout()) {
                RecapResult.Retryable(RecapErrorCode.TIMEOUT)
            } else {
                RecapResult.Retryable(RecapErrorCode.NETWORK)
            }
        }
    }

    private suspend fun post(body: String, token: String): HttpResponse =
        httpClient.post(endpoint.url) {
            header("apikey", endpoint.publishableKey)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(TextContent(body, ContentType.Application.Json))
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }

    private suspend fun map(response: HttpResponse): RecapResult {
        val status = response.status.value
        return when (status) {
            200 -> parseSuccess(response.bodyAsText())
            400, 405, 413 -> RecapResult.Permanent(RecapErrorCode.BAD_REQUEST)
            403 -> RecapResult.AuthRequired
            422 -> RecapResult.Permanent(unprocessableCode(response.bodyAsText()))
            429 -> RecapResult.Retryable(RecapErrorCode.RATE_LIMITED, retryAfter(response))
            502 -> RecapResult.Retryable(RecapErrorCode.PROVIDER_ERROR, retryAfter(response))
            503 -> RecapResult.Retryable(RecapErrorCode.SERVICE_UNAVAILABLE, retryAfter(response))
            504 -> RecapResult.Retryable(RecapErrorCode.TIMEOUT, retryAfter(response))
            in 400..499 -> RecapResult.Permanent(RecapErrorCode.BAD_REQUEST)
            else -> RecapResult.Retryable(RecapErrorCode.UNKNOWN, retryAfter(response))
        }
    }

    private fun parseSuccess(text: String): RecapResult {
        val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return RecapResult.Retryable(RecapErrorCode.BAD_RESPONSE)
        val summary = body.string("summary")?.trim()
        // The function doesn't report a model yet; tolerate it missing.
        val model = body.string("model")?.takeIf { it.isNotBlank() && it.length <= 100 }
        return when (body.string("kind")) {
            "recap" -> if (summary.isNullOrEmpty()) {
                RecapResult.Retryable(RecapErrorCode.BAD_RESPONSE)
            } else {
                RecapResult.Success(summary, model)
            }
            "not_enough" -> RecapResult.NotEnough
            else -> RecapResult.Retryable(RecapErrorCode.BAD_RESPONSE)
        }
    }

    // Only a code is kept; the server's message is matched, never stored.
    private fun unprocessableCode(text: String): RecapErrorCode =
        if (text.contains("language", ignoreCase = true)) {
            RecapErrorCode.UNSUPPORTED_LANGUAGE
        } else {
            RecapErrorCode.EXCERPT_TOO_SHORT
        }

    /** Retry-After as delta-seconds or an HTTP date; capped at a day. */
    private fun retryAfter(response: HttpResponse): Duration? {
        val raw = response.headers[HttpHeaders.RetryAfter]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val delay = raw.toLongOrNull()?.seconds
            ?: runCatching {
                val at = raw.fromHttpToGmtDate().timestamp
                (at - clock.now().toEpochMilliseconds()).milliseconds
            }.getOrNull()
            ?: return null
        return delay.coerceIn(Duration.ZERO, MAX_RETRY_AFTER)
    }

    private fun requestBody(excerpt: String, input: RecapInput): String = buildJsonObject {
        put("excerpt", excerpt)
        supportedLanguage(input.language)?.let { put("language", it) }
        input.lastSentence?.trim()?.takeIf { it.isNotEmpty() }?.let {
            put("lastSentence", it.take(RecapLimits.MAX_LAST_SENTENCE_CHARS))
        }
    }.toString()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun Throwable.isTimeout(): Boolean =
        generateSequence(this) { it.cause }.take(8).any { cause ->
            cause::class.simpleName?.contains("Timeout") == true
        }

    companion object {
        /** Server latency reaches ~60 s; leave room for one provider retry. */
        const val REQUEST_TIMEOUT_MS = 90_000L

        /** The server's minimum after trim. */
        const val MIN_EXCERPT_CHARS = 80

        private val MAX_RETRY_AFTER = 24.hours

        /** Languages the function writes in; others fall back to its default. */
        val SUPPORTED_LANGUAGES: Set<String> = RecapLanguages.SUPPORTED

        /** "sl-SI" → "sl"; null when unsupported. */
        fun supportedLanguage(tag: String?): String? = RecapLanguages.normalize(tag)
    }
}
