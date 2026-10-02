package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapRetryResult
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** Reads are plain database flows; only [retry] wakes the job runner. */
class SessionRecapDataRepository(
    private val database: SessionRecapDatabase,
    private val runner: RecapJobRunner,
    private val clock: Clock = Clock.System,
) : RecapRepository {

    override fun observeRecap(sessionId: String): Flow<SessionRecap?> =
        database.observeRecap(sessionId).map { it?.toDomain() }.distinctUntilChanged()

    override fun observeLatestForBook(bookId: String): Flow<SessionRecap?> =
        database.observeLatestForBook(bookId).map { it?.toDomain() }.distinctUntilChanged()

    override fun observeHistory(bookId: String): Flow<List<SessionRecap>> =
        database.observeForBook(bookId).map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()

    override suspend fun retry(sessionId: String): RecapRetryResult {
        val row = database.getRecap(sessionId) ?: return RecapRetryResult.NOT_FOUND
        val status = RecapStatus.fromName(row.status)
        val failed = status == RecapStatus.FAILED_RETRYABLE || status == RecapStatus.FAILED_PERMANENT
        val inputRejected = RecapErrorCode.fromName(row.lastError)?.isInputError == true
        if (!failed || inputRejected || row.excerpt == null) return RecapRetryResult.NOT_RETRYABLE
        if (!database.requeue(sessionId, clock.now().toEpochMilliseconds())) {
            return RecapRetryResult.NOT_RETRYABLE
        }
        runner.trigger(RecapTrigger.USER_RETRY)
        return RecapRetryResult.QUEUED
    }
}
