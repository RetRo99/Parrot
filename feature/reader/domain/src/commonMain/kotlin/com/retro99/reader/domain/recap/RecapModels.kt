package com.retro99.reader.domain.recap

/** Lifecycle of one session's recap row. Stored by name; don't rename. */
enum class RecapStatus {
    /** The session is open and read text is still being collected. */
    CAPTURING,
    /** Ended and eligible; waits for the job runner. */
    PENDING,
    /** Claimed by the job runner; a request may be in flight. */
    RUNNING,
    CLOUD_QUEUED,
    CLOUD_RUNNING,
    SUCCEEDED,
    /** The model said too little happened to summarise. */
    NOT_ENOUGH,
    /** Will be retried automatically at nextAttemptAt. */
    FAILED_RETRYABLE,
    /** Won't be retried automatically; retry() may still requeue it. */
    FAILED_PERMANENT,
    /** Too short, a reread, or a repeat of the previous session. */
    SKIPPED_INELIGIBLE,
    ;

    companion object {
        fun fromName(name: String): RecapStatus =
            entries.firstOrNull { it.name == name } ?: FAILED_PERMANENT
    }
}

/** Why a recap failed or was skipped. Codes only, never server or book text. */
enum class RecapErrorCode {
    AUTH_REQUIRED,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    PROVIDER_ERROR,
    TIMEOUT,
    NETWORK,
    EXCERPT_TOO_SHORT,
    UNSUPPORTED_LANGUAGE,
    BAD_REQUEST,
    BAD_RESPONSE,
    EXCERPT_EXPIRED,
    MAX_ATTEMPTS,
    UNKNOWN,

    // Skip reasons (status SKIPPED_INELIGIBLE).
    TOO_LITTLE_READING,
    REREAD_ONLY,
    DUPLICATE_OF_PREVIOUS,

    /** Cloud recaps was turned off; the session's text was dropped. */
    CONSENT_WITHDRAWN,
    ;

    /** Retrying the same input can't succeed. */
    val isInputError: Boolean
        get() = this == EXCERPT_TOO_SHORT || this == UNSUPPORTED_LANGUAGE || this == BAD_REQUEST

    companion object {
        fun fromName(name: String?): RecapErrorCode? =
            name?.let { value -> entries.firstOrNull { it.name == value } ?: UNKNOWN }
    }
}

/** A reading position as the reader session tracks it (Readium locator). */
data class RecapPosition(
    val href: String? = null,
    /** Progression within [href], 0..1. */
    val progression: Double? = null,
    /** Progression within the book, 0..1. */
    val totalProgression: Double? = null,
)

/** Chapter info for display only; titles never leave the device. */
data class RecapChapter(
    val index: Int? = null,
    val title: String? = null,
)

/** Where appended text came from; drives the eligibility counters. */
enum class RecapTextSource {
    /** A page the reader settled on (manual reading). */
    PAGE,
    /** One sentence heard from device TTS or narration. */
    TTS_SENTENCE,
}

/** A session's recap as the UI sees it. No excerpt text is exposed. */
data class SessionRecap(
    val sessionId: String,
    val serverId: String,
    val bookId: String,
    val status: RecapStatus,
    val summary: String?,
    val lastError: RecapErrorCode?,
    val startPosition: RecapPosition,
    val endPosition: RecapPosition,
    val startChapter: RecapChapter,
    val endChapter: RecapChapter,
    val attemptCount: Int,
    val nextAttemptAt: Long?,
    val engineId: String?,
    val model: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long?,
    val generatedAt: Long?,
    /** A failed recap whose text is still stored and wasn't rejected. */
    val canRetry: Boolean = false,
) {
    /** True while a result may still arrive without user action. */
    val isInProgress: Boolean
        get() = status == RecapStatus.PENDING ||
            status == RecapStatus.CLOUD_QUEUED || status == RecapStatus.CLOUD_RUNNING ||
            status == RecapStatus.RUNNING ||
            status == RecapStatus.FAILED_RETRYABLE
}

/** Outcome of [RecapRepository.retry]. */
enum class RecapRetryResult {
    QUEUED,
    NOT_FOUND,
    /** Not failed, no text left, or the input itself was rejected. */
    NOT_RETRYABLE,
}
