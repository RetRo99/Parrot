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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.decodeFromString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
        if (input.sessionId == null || input.endedAt == null) return RecapResult.Permanent(RecapErrorCode.BAD_REQUEST)
        if (input.accountId != null && auth.accountId() != input.accountId) return RecapResult.AuthRequired
        val excerpt = input.excerpt
        // The server answers 422 below this; don't spend a request on it.
        if (excerpt.trim().length < MIN_EXCERPT_CHARS) {
            return RecapResult.Permanent(RecapErrorCode.EXCERPT_TOO_SHORT)
        }
        val parts = splitForUpload(excerpt)
        val token = auth.accessToken() ?: return RecapResult.AuthRequired
        val session = Session(token)
        return try {
            // Lookup first: response loss after admission or completion must not
            // cause another upload or provider call. A definitive empty page is
            // the ONLY condition under which this session is submitted.
            val page = fetch(input.accountId, sessionId = input.sessionId)
            page.items.firstOrNull()?.let { return remoteResult(it) }
            if (parts.size == 1) {
                val response = send(requestBody(input, excerpt = excerpt), session)
                    ?: return RecapResult.AuthRequired
                return map(response)
            }
            // Long excerpt: earlier parts are stored, the last one generates.
            val upload = UploadRef(newUploadId(), parts.size)
            parts.dropLast(1).forEachIndexed { index, part ->
                val response = send(partBody(upload, index, part, input), session)
                    ?: return RecapResult.AuthRequired
                if (response.status.value != 202) return map(response)
            }
            val response = send(requestBody(input, upload = upload, last = parts.last()), session)
                ?: return RecapResult.AuthRequired
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

    private inner class Session(var token: String, val accountId: String? = auth.accountId())

    private class UploadRef(val id: String, val total: Int)

    /** Posts with the session's token; null once a refresh can't help. */
    private suspend fun send(body: String, session: Session): HttpResponse? {
        if (auth.accountId() != session.accountId) return null
        val response = post(body, session.token)
        if (response.status.value != 401) return response
        // Expired access token: refresh once, then give up until sign-in.
        val refreshed = auth.refreshedAccessToken() ?: return null
        if (auth.accountId() != session.accountId) return null
        session.token = refreshed
        return post(body, refreshed).takeIf { it.status.value != 401 }
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
            200, 202 -> parseSuccess(response.bodyAsText())
            400, 405, 413 -> RecapResult.Permanent(RecapErrorCode.BAD_REQUEST)
            // A stored part expired or was lost; the next attempt re-uploads.
            409 -> if (response.bodyAsText().contains("upload_incomplete")) RecapResult.Retryable(RecapErrorCode.NETWORK)
                else RecapResult.Permanent(RecapErrorCode.BAD_REQUEST)
            // Not a token problem (the function never sends it): back off.
            403 -> RecapResult.Retryable(RecapErrorCode.UNKNOWN, retryAfter(response))
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
        if (body.string("state") != null) {
            val record = runCatching { wireJson.decodeFromString<CloudRecapRecord>(text) }.getOrNull()
                ?: return RecapResult.Retryable(RecapErrorCode.BAD_RESPONSE)
            return remoteResult(record)
        }
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

    private fun requestBody(
        input: RecapInput,
        excerpt: String? = null,
        upload: UploadRef? = null,
        last: String? = null,
    ): String = buildJsonObject {
        metadata(input)
        excerpt?.let { put("excerpt", it) }
        if (upload != null && last != null) {
            putUpload(upload, upload.total - 1)
            put("text", last)
        }
        supportedLanguage(input.language)?.let { put("language", it) }
        input.lastSentence?.trim()?.takeIf { it.isNotEmpty() }?.let {
            put("lastSentence", it.take(RecapLimits.MAX_LAST_SENTENCE_CHARS))
        }
    }.toString()

    private fun partBody(upload: UploadRef, index: Int, text: String, input: RecapInput): String =
        buildJsonObject {
            metadata(input)
            putUpload(upload, index)
            put("text", text)
        }.toString()

    private fun JsonObjectBuilder.metadata(input: RecapInput) {
        put("consentVersion", 2)
        put("sessionId", input.sessionId)
        put("cloudBookId", input.cloudBookId)
        put("endedAt", input.endedAt)
        put("position", buildJsonObject {
            put("href", input.position.href)
            put("progression", input.position.progression)
            put("totalProgression", input.position.totalProgression)
        })
    }

    suspend fun fetch(accountId: String?, sessionId: String? = null, cloudBookId: String? = null, cursor: Long = 0): CloudRecapPage {
        val text = operation(accountId, buildJsonObject {
            put("operation", "fetch"); put("sessionId", sessionId); put("cloudBookId", cloudBookId); put("cursor", cursor)
        })
        return wireJson.decodeFromString(text)
    }

    suspend fun consent(accountId: String, enabled: Boolean) {
        operation(accountId, buildJsonObject { put("operation", "consent"); put("enabled", enabled) })
    }

    suspend fun delete(accountId: String, sessionId: String) {
        operation(accountId, buildJsonObject { put("operation", "delete"); put("sessionId", sessionId) })
    }

    private suspend fun operation(accountId: String?, body: JsonObject): String {
        if (!endpoint.isConfigured || auth.accountId() != accountId) throw IllegalStateException("recap_account_unavailable")
        val token = auth.accessToken() ?: throw IllegalStateException("recap_signed_out")
        if (auth.accountId() != accountId) throw IllegalStateException("recap_account_changed")
        val response = send(body.toString(), Session(token, accountId)) ?: throw IllegalStateException("recap_signed_out")
        if (response.status.value != 200 || auth.accountId() != accountId) throw IllegalStateException("recap_transport_failure")
        return response.bodyAsText()
    }

    private fun remoteResult(record: CloudRecapRecord): RecapResult = when (record.state) {
        "queued" -> RecapResult.Queued()
        "running" -> RecapResult.Queued(running = true)
        "completed" -> record.summary?.takeIf { it.isNotBlank() }?.let { RecapResult.Success(it, record.model) }
            ?: RecapResult.Retryable(RecapErrorCode.BAD_RESPONSE)
        "not_enough" -> RecapResult.NotEnough
        "deleted" -> RecapResult.Permanent(RecapErrorCode.CONSENT_WITHDRAWN)
        "failed" -> RecapResult.Permanent(RecapErrorCode.fromName(record.errorCode) ?: RecapErrorCode.PROVIDER_ERROR)
        else -> RecapResult.Retryable(RecapErrorCode.BAD_RESPONSE)
    }

    private fun JsonObjectBuilder.putUpload(upload: UploadRef, index: Int) {
        put(
            "upload",
            buildJsonObject {
                put("id", upload.id)
                put("index", index)
                put("total", upload.total)
            },
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun Throwable.isTimeout(): Boolean =
        generateSequence(this) { it.cause }.take(8).any { cause ->
            cause::class.simpleName?.contains("Timeout") == true
        }

    companion object {
        /**
         * The server answers within its 135 s budget (long sessions are
         * summarised in parts; the Edge gateway gives up at 150 s). The client
         * does not wait that long: a submission whose response is lost is
         * recovered by the lookup at the start of the next attempt, so a
         * short timeout can delay a recap but never lose one.
         */
        const val REQUEST_TIMEOUT_MS = 30_000L

        /** The server's minimum after trim. */
        const val MIN_EXCERPT_CHARS = 80

        /**
         * Bodies of ~1 MB+ fail at the Edge gateway, so a request carries
         * at most this much excerpt (as a JSON string, UTF-8); the server
         * accepts 256 KiB per body.
         */
        const val MAX_PART_BYTES = 180_000

        /** The server's limits per upload part and per upload. */
        const val MAX_PART_CHARS = 200_000
        const val MAX_UPLOAD_PARTS = 64

        /**
         * Splits [text] into parts whose JSON-escaped UTF-8 size is at most
         * [maxBytes]; joined, they are exactly [text]. Over [MAX_UPLOAD_PARTS]
         * (~11M chars) only the newest parts go: the server reads 2M at most.
         */
        fun splitForUpload(text: String, maxBytes: Int = MAX_PART_BYTES): List<String> {
            val parts = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(text.length, start + minOf(maxBytes, MAX_PART_CHARS))
                while (true) {
                    // Never split a surrogate pair.
                    if (end < text.length && end > start + 1 && text[end - 1].isHighSurrogate()) end--
                    val size = jsonBytes(text.substring(start, end))
                    if (size <= maxBytes) break
                    end = start + ((end - start).toLong() * maxBytes / size * 95 / 100).toInt()
                        .coerceAtLeast(1)
                }
                parts += text.substring(start, end)
                start = end
            }
            return if (parts.size > MAX_UPLOAD_PARTS) parts.takeLast(MAX_UPLOAD_PARTS) else parts
        }

        private fun jsonBytes(text: String): Int =
            JsonPrimitive(text).toString().encodeToByteArray().size

        @OptIn(ExperimentalUuidApi::class)
        private fun newUploadId(): String = Uuid.random().toString()

        private val MAX_RETRY_AFTER = 24.hours
        private val wireJson = Json { ignoreUnknownKeys = true }

        /** Languages the function writes in; others fall back to its default. */
        val SUPPORTED_LANGUAGES: Set<String> = RecapLanguages.SUPPORTED

        /** "sl-SI" → "sl"; null when unsupported. */
        fun supportedLanguage(tag: String?): String? = RecapLanguages.normalize(tag)
    }
}
