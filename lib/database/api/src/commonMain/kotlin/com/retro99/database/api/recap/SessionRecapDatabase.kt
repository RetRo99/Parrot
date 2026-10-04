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

    /** Last update of the oldest RUNNING row, to know when it goes stale. */
    suspend fun getOldestRunningUpdate(): Long?

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

    /**
     * A FAILED_PERMANENT row with text back to PENDING with fresh attempts;
     * a next attempt time still in the future is kept.
     */
    suspend fun requeue(sessionId: String, now: Long): Boolean

    /**
     * Consent withdrawn: drops all queued or captured text. Waiting rows
     * become FAILED_PERMANENT, capturing rows are marked CONSENT_WITHDRAWN.
     */
    suspend fun withdrawText(now: Long)

    /** Applies retention; returns the number of rows deleted. */
    suspend fun applyRetention(excerptCutoff: Long, rowCutoff: Long, now: Long): Long

    suspend fun deleteRecap(sessionId: String)

    suspend fun deleteAllRecaps()

    suspend fun getCloudRows(accountId: String): List<SessionRecapEntity>
    suspend fun cacheCloudRecap(entity: SessionRecapEntity)
    suspend fun markCloudQueued(sessionId: String, accountId: String, cloudBookId: String?, running: Boolean, now: Long): Boolean
    /** Durable privacy commands. Process only with this account's credentials. */
    suspend fun queueCloudDeletion(accountId: String, sessionId: String)
    suspend fun getCloudDeletions(accountId: String): List<String>
    suspend fun acknowledgeCloudDeletion(accountId: String, sessionId: String)

    /**
     * Withdrawal for this account: stores the write fence, drops queued text
     * and deletes the account's recaps and cursors. Rows still being written
     * go too; their late conditional updates no-op on the missing row.
     */
    suspend fun queueCloudWithdrawal(accountId: String, now: Long)
    suspend fun hasCloudWithdrawal(accountId: String): Boolean
    suspend fun acknowledgeCloudWithdrawal(accountId: String)
    suspend fun enableCloudConsent(accountId: String)
    suspend fun getCloudCursor(accountId: String, bookId: String): Long
    suspend fun setCloudCursor(accountId: String, bookId: String, cursor: Long)
    suspend fun bindCloudIdentity(sessionId: String, cloudBookId: String?): Boolean

    override suspend fun clearAllData() {
        deleteAllRecaps()
    }
}
