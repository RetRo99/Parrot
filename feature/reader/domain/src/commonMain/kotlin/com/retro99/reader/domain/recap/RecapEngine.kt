package com.retro99.reader.domain.recap

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Turns read text into a short recap. Provider-neutral: the cloud engine
 * today, an on-device engine later. Implementations own all auth, transport
 * and error mapping and must never log [RecapInput] text or summaries.
 */
interface RecapEngine {
    /** Stable id stored with each recap, e.g. [CLOUD_ENGINE_ID]. */
    val id: String

    suspend fun generate(input: RecapInput): RecapResult

    companion object {
        const val CLOUD_ENGINE_ID = "cloud"
    }
}

data class RecapInput(
    /** Read text, already bounded to [RecapLimits.MAX_EXCERPT_CHARS]. */
    val excerpt: String,
    /** Output language (BCP-47); null lets the engine choose. */
    val language: String?,
    /** Where the reader stopped, at most [RecapLimits.MAX_LAST_SENTENCE_CHARS]. */
    val lastSentence: String?,
) {
    override fun toString(): String = "RecapInput(chars=${excerpt.length}, language=$language)"
}

sealed interface RecapResult {
    data class Success(val summary: String, val model: String?) : RecapResult {
        override fun toString(): String = "Success(chars=${summary.length}, model=$model)"
    }

    data object NotEnough : RecapResult

    /** Try again later; [retryAfter] is the server's hint when it sent one. */
    data class Retryable(val code: RecapErrorCode, val retryAfter: Duration? = null) : RecapResult

    /** The same input will fail again. */
    data class Permanent(val code: RecapErrorCode) : RecapResult

    /** No usable session; wait for sign-in. */
    data object AuthRequired : RecapResult
}

/** Picks the engine to use now, or null when recaps can't run. */
interface RecapEngineSelector {
    suspend fun select(): RecapEngine?

    /** Emits true whenever some engine becomes usable (consent and sign-in). */
    fun observeAvailable(): Flow<Boolean>
}
