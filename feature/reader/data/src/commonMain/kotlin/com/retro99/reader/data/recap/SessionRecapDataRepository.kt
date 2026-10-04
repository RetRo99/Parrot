package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapRetryResult
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.RecapRequestResult
import com.retro99.reader.domain.recap.SessionRecap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/** Reads are plain database flows; only [retry] wakes the job runner. */
class SessionRecapDataRepository(
    private val database: SessionRecapDatabase,
    private val runner: RecapJobRunner,
    private val clock: Clock = Clock.System,
    private val auth: RecapAuthTokens? = null,
    private val identity: RecapIdentity? = null,
) : RecapRepository {
    override fun observeProgression(bookId: String) = identity?.observeProgression(bookId) ?: kotlinx.coroutines.flow.flowOf(null)

    override fun observeRecap(sessionId: String): Flow<SessionRecap?> =
        visible(database.observeRecap(sessionId).map { listOfNotNull(it) }).map { it.firstOrNull()?.toDomain() }.distinctUntilChanged()

    override fun observeLatestForBook(bookId: String): Flow<SessionRecap?> =
        observeHistory(bookId).map { rows -> rows.firstOrNull { it.status == RecapStatus.SUCCEEDED } ?: rows.firstOrNull() }.distinctUntilChanged()

    override fun observeHistory(bookId: String): Flow<List<SessionRecap>> =
        visible(database.observeForBook(bookId)).map { rows ->
            val account = auth?.accountId()
            val cloudBook = identity?.cloudId(bookId)
            val linked = if (account != null && cloudBook != null) database.getCloudRows(account)
                .filter { it.cloudBookId == cloudBook && it.cloudExpiresAt?.let { at -> at > clock.now().toEpochMilliseconds() } != false }
                else emptyList()
            (rows + linked).distinctBy { it.sessionId }.sortedByDescending { it.endedAt ?: it.createdAt }.map { it.toDomain() }
        }
            .distinctUntilChanged()

    private fun visible(rows: Flow<List<com.retro99.database.api.recap.SessionRecapEntity>>) =
        if (auth == null) rows else combine(rows, auth.observeAccountId()) { values, account ->
            values.filter { it.cloudAccountId != null && it.cloudAccountId == account &&
                it.cloudExpiresAt?.let { at -> at > clock.now().toEpochMilliseconds() } != false }
        }

    override suspend fun request(sessionId: String): RecapRequestResult {
        val row = database.getRecap(sessionId) ?: return RecapRequestResult.TEXT_UNAVAILABLE
        if (auth != null && (row.cloudAccountId == null || row.cloudAccountId != auth.accountId())) return RecapRequestResult.ACCOUNT_REQUIRED
        return when (RecapStatus.fromName(row.status)) {
            RecapStatus.SUCCEEDED, RecapStatus.NOT_ENOUGH -> RecapRequestResult.ALREADY_GENERATED
            RecapStatus.CLOUD_QUEUED, RecapStatus.CLOUD_RUNNING, RecapStatus.RUNNING, RecapStatus.CAPTURING -> {
                runner.trigger(RecapTrigger.USER_RETRY); RecapRequestResult.IN_PROGRESS
            }
            RecapStatus.PENDING, RecapStatus.FAILED_RETRYABLE -> {
                if (row.excerpt == null || row.consentVersion != 2) RecapRequestResult.TEXT_UNAVAILABLE
                else { runner.trigger(RecapTrigger.USER_RETRY); RecapRequestResult.QUEUED }
            }
            RecapStatus.FAILED_PERMANENT -> if (retry(sessionId) == RecapRetryResult.QUEUED) RecapRequestResult.QUEUED else RecapRequestResult.TEXT_UNAVAILABLE
            RecapStatus.SKIPPED_INELIGIBLE -> RecapRequestResult.TEXT_UNAVAILABLE
        }
    }

    override suspend fun delete(sessionId: String) {
        val row = database.getRecap(sessionId) ?: return
        if (auth != null && row.cloudAccountId != auth.accountId()) return
        val account = row.cloudAccountId
        if (account != null && row.consentVersion == 2) database.queueCloudDeletion(account, sessionId)
        else database.deleteRecap(sessionId)
        runner.trigger(RecapTrigger.USER_RETRY)
    }

    override suspend fun retry(sessionId: String): RecapRetryResult {
        val row = database.getRecap(sessionId) ?: return RecapRetryResult.NOT_FOUND
        if (auth != null && (row.cloudAccountId == null || row.cloudAccountId != auth.accountId() || row.consentVersion != 2)) return RecapRetryResult.NOT_RETRYABLE
        // FAILED_RETRYABLE already retries on its own schedule; a tap there
        // would skip the backoff and Retry-After.
        val stopped = RecapStatus.fromName(row.status) == RecapStatus.FAILED_PERMANENT
        val inputRejected = RecapErrorCode.fromName(row.lastError)?.isInputError == true
        if (!stopped || inputRejected || row.excerpt == null) return RecapRetryResult.NOT_RETRYABLE
        if (!database.requeue(sessionId, clock.now().toEpochMilliseconds())) {
            return RecapRetryResult.NOT_RETRYABLE
        }
        runner.trigger(RecapTrigger.USER_RETRY)
        return RecapRetryResult.QUEUED
    }
}
