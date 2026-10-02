package com.retro99.database.api.recap

/**
 * One row of `session_recap`. [status] and [lastError] hold the names of the
 * reader domain's RecapStatus and RecapErrorCode; the database stays untyped.
 */
data class SessionRecapEntity(
    val sessionId: String,
    val serverId: String,
    val bookUuid: String,
    val status: String,
    val startHref: String? = null,
    val startProgression: Double? = null,
    val startTotalProgression: Double? = null,
    val endHref: String? = null,
    val endProgression: Double? = null,
    val endTotalProgression: Double? = null,
    val startChapterIndex: Int? = null,
    val startChapterTitle: String? = null,
    val endChapterIndex: Int? = null,
    val endChapterTitle: String? = null,
    val furthestTotalProgression: Double? = null,
    val pageAdvances: Int = 0,
    val ttsSentences: Int = 0,
    val activeReadingMs: Long = 0,
    val excerpt: String? = null,
    val excerptHash: String? = null,
    val lastSentence: String? = null,
    val language: String? = null,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null,
    val summary: String? = null,
    val engineId: String? = null,
    val model: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long? = null,
    val generatedAt: Long? = null,
)

/** Capture progress written while a session is still open. */
data class SessionRecapCapture(
    val excerpt: String?,
    val endHref: String?,
    val endProgression: Double?,
    val endTotalProgression: Double?,
    val endChapterIndex: Int?,
    val endChapterTitle: String?,
    val furthestTotalProgression: Double?,
    val pageAdvances: Int,
    val ttsSentences: Int,
)
