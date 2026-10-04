package com.retro99.reader.domain.recap

import kotlinx.coroutines.flow.Flow

/**
 * Read side for the UI. Observing never starts generation; only the job
 * runner sends requests; [request] and [retry] are explicit user actions.
 */
interface RecapRepository {
    fun observeRecap(sessionId: String): Flow<SessionRecap?>

    /** The latest succeeded recap of a book, else its latest non-skipped one. */
    fun observeLatestForBook(bookId: String): Flow<SessionRecap?>

    /** Every non-skipped, ended session of a book, newest first. */
    fun observeHistory(bookId: String): Flow<List<SessionRecap>>

    /** Requeues a failed recap and wakes the job runner. */
    suspend fun retry(sessionId: String): RecapRetryResult

    /** Explicit button action; uses the SAME session key as automatic delivery. */
    suspend fun request(sessionId: String): RecapRequestResult
    suspend fun delete(sessionId: String)
    fun observeProgression(bookId: String): Flow<Double?>
}

enum class RecapRequestResult { QUEUED, IN_PROGRESS, ALREADY_GENERATED, TEXT_UNAVAILABLE, ACCOUNT_REQUIRED }
