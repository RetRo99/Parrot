package com.retro99.reader.domain.recap

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/** Size limits shared with the generate-recap API. */
object RecapLimits {
    /** The API's excerpt cap; the buffer keeps the most recent chars. */
    const val MAX_EXCERPT_CHARS = 8_000

    /** The API's lastSentence cap. */
    const val MAX_LAST_SENTENCE_CHARS = 300
}

/** Retry, recovery and retention rules for the job runner. */
object RecapJobPolicy {
    /** Billed attempts per session before giving up (user retry resets). */
    const val MAX_ATTEMPTS = 5

    val BASE_BACKOFF: Duration = 1.minutes
    val MAX_BACKOFF: Duration = 6.hours

    /** RUNNING longer than this means the process died mid-request. */
    val STALE_RUNNING_AFTER: Duration = 3.minutes

    /** Read text is kept at most this long, then nulled. */
    val EXCERPT_RETENTION: Duration = 14.days

    /** Whole recap rows are deleted after this long. */
    val ROW_RETENTION: Duration = 180.days

    /**
     * Quota and outage answers say nothing about the row, so they never
     * use up its attempts; backoff and excerpt expiry still bound them.
     */
    fun countsTowardCap(code: RecapErrorCode): Boolean =
        code != RecapErrorCode.RATE_LIMITED && code != RecapErrorCode.SERVICE_UNAVAILABLE

    /** 1, 2, 4, 8 ... minutes, capped; [attempt] starts at 1. */
    fun backoff(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceIn(0, 20)
        val delay = BASE_BACKOFF * (1L shl exponent).toDouble()
        return if (delay > MAX_BACKOFF) MAX_BACKOFF else delay
    }

    /** Honours the server's Retry-After when it asks for longer. */
    fun nextAttemptAt(nowMs: Long, attempt: Int, retryAfter: Duration?): Long {
        val delay = backoff(attempt).let { base ->
            if (retryAfter != null && retryAfter > base) retryAfter else base
        }
        return nowMs + delay.inWholeMilliseconds
    }
}
