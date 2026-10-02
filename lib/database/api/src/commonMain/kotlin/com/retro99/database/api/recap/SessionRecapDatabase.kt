package com.retro99.database.api.recap

import com.retro99.database.api.DataClearable
import kotlinx.coroutines.flow.Flow

/**
 * Storage for per-session reading recaps. Conditional updates return false
 * when the row was not in the expected state, so callers can't race.
 */
interface SessionRecapDatabase : DataClearable {

    /** Starts a CAPTURING row; false if the session already has one. */
    suspend fun insertCapturing(entity: SessionRecapEntity): Boolean

    /** Saves capture progress; only while the row is CAPTURING. */
    suspend fun updateCapture(sessionId: String, capture: SessionRecapCapture, now: Long): Boolean

    /** Ends capture with [status] (PENDING or SKIPPED_INELIGIBLE). */
    suspend fun finishCapture(
        sessionId: String,
        status: String,
        capture: SessionRecapCapture,
        excerptHash: String?,
        lastSentence: String?,
        activeReadingMs: Long,
        lastError: String?,
        endedAt: Long,
    ): Boolean

    suspend fun getRecap(sessionId: String): SessionRecapEntity?

    fun observeRecap(sessionId: String): Flow<SessionRecapEntity?>

    fun observeLatestForBook(bookUuid: String): Flow<SessionRecapEntity?>

    fun observeForBook(bookUuid: String): Flow<List<SessionRecapEntity>>

    suspend fun getCapturing(): List<SessionRecapEntity>

    suspend fun getPreviousEnded(
        bookUuid: String,
        sessionId: String,
        createdAt: Long,
    ): SessionRecapEntity?

    suspend fun getNextDue(now: Long): SessionRecapEntity?

    suspend fun getEarliestScheduled(now: Long): Long?

    /** Moves a due row to RUNNING and counts the attempt; false if lost. */
    suspend fun claim(sessionId: String, engineId: String, now: Long): Boolean

    /** RUNNING to a final result; drops the excerpt. */
    suspend fun complete(
        sessionId: String,
        status: String,
        summary: String?,
        model: String?,
        now: Long,
    ): Boolean

    /**
     * RUNNING to [status] with an error code and an optional retry time.
     * [dropText] nulls the excerpt and last sentence (rejected input).
     */
    suspend fun fail(
        sessionId: String,
        status: String,
        attemptCount: Int,
        nextAttemptAt: Long?,
        lastError: String?,
        now: Long,
        dropText: Boolean = false,
    ): Boolean

    /**
     * Returns RUNNING rows last touched before [staleBefore] to PENDING, or
     * to FAILED_PERMANENT once they have used [maxAttempts].
     */
    suspend fun recoverStaleRunning(staleBefore: Long, now: Long, maxAttempts: Int): Long

    /** A failed row back to PENDING with fresh attempts, if it has text. */
    suspend fun requeue(sessionId: String, now: Long): Boolean

    /** Applies retention; returns the number of rows deleted. */
    suspend fun applyRetention(excerptCutoff: Long, rowCutoff: Long, now: Long): Long

    suspend fun deleteRecap(sessionId: String)

    suspend fun deleteAllRecaps()

    override suspend fun clearAllData() {
        deleteAllRecaps()
    }
}
