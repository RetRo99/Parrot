package com.retro99.database.implementation.dao.recap

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.retro99.database.api.recap.SessionRecapCapture
import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.SessionRecapQueries
import com.retro99.database.implementation.Session_recap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class SessionRecapSqlDelightDao(
    private val databaseManager: DatabaseManager,
) : SessionRecapDatabase {
    private val database get() = databaseManager.getDatabase()

    override suspend fun insertCapturing(entity: SessionRecapEntity): Boolean = io {
        database.insertCapturingRow(entity)
    }

    override suspend fun updateCapture(
        sessionId: String,
        capture: SessionRecapCapture,
        now: Long,
    ): Boolean = io {
        database.changedOne {
            updateCapture(
                excerpt = capture.excerpt,
                endHref = capture.endHref,
                endProgression = capture.endProgression,
                endTotalProgression = capture.endTotalProgression,
                endChapterIndex = capture.endChapterIndex?.toLong(),
                endChapterTitle = capture.endChapterTitle,
                furthestTotalProgression = capture.furthestTotalProgression,
                pageAdvances = capture.pageAdvances.toLong(),
                ttsSentences = capture.ttsSentences.toLong(),
                updatedAt = now,
                sessionId = sessionId,
            )
        }
    }

    override suspend fun finishCapture(
        sessionId: String,
        status: String,
        capture: SessionRecapCapture,
        excerptHash: String?,
        lastSentence: String?,
        activeReadingMs: Long,
        lastError: String?,
        endedAt: Long,
    ): Boolean = io {
        database.changedOne {
            finishCapture(
                status = status,
                excerpt = capture.excerpt,
                excerptHash = excerptHash,
                lastSentence = lastSentence,
                endHref = capture.endHref,
                endProgression = capture.endProgression,
                endTotalProgression = capture.endTotalProgression,
                endChapterIndex = capture.endChapterIndex?.toLong(),
                endChapterTitle = capture.endChapterTitle,
                furthestTotalProgression = capture.furthestTotalProgression,
                pageAdvances = capture.pageAdvances.toLong(),
                ttsSentences = capture.ttsSentences.toLong(),
                activeReadingMs = activeReadingMs,
                lastError = lastError,
                endedAt = endedAt,
                sessionId = sessionId,
            )
        }
    }

    override suspend fun getRecap(sessionId: String): SessionRecapEntity? = io {
        database.sessionRecapQueries.getRecap(sessionId).executeAsOneOrNull()?.toEntity()
    }

    override fun observeRecap(sessionId: String): Flow<SessionRecapEntity?> =
        database.sessionRecapQueries.getRecap(sessionId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { row -> row?.toEntity() }

    override fun observeLatestForBook(bookUuid: String): Flow<SessionRecapEntity?> =
        database.sessionRecapQueries.observeLatestForBook(bookUuid)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { row -> row?.toEntity() }

    override fun observeForBook(bookUuid: String): Flow<List<SessionRecapEntity>> =
        database.sessionRecapQueries.observeForBook(bookUuid)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEntity() } }

    override suspend fun getCapturing(): List<SessionRecapEntity> = io {
        database.sessionRecapQueries.getCapturing().executeAsList().map { it.toEntity() }
    }

    override suspend fun getPreviousEnded(
        bookUuid: String,
        sessionId: String,
        createdAt: Long,
    ): SessionRecapEntity? = io {
        database.sessionRecapQueries.getPreviousEnded(bookUuid, sessionId, createdAt)
            .executeAsOneOrNull()
            ?.toEntity()
    }

    override suspend fun getNextDue(now: Long): SessionRecapEntity? = io {
        val queries = database.sessionRecapQueries
        queries.getNextDueId(now).executeAsOneOrNull()
            ?.let { id -> queries.getRecap(id).executeAsOneOrNull()?.toEntity() }
    }

    override suspend fun getEarliestScheduled(now: Long): Long? = io {
        database.sessionRecapQueries.getEarliestScheduled(now).executeAsOneOrNull()?.MIN
    }

    override suspend fun getOldestRunningUpdate(): Long? = io {
        database.sessionRecapQueries.getOldestRunningUpdate().executeAsOneOrNull()?.MIN
    }

    override suspend fun claim(sessionId: String, engineId: String, now: Long): Boolean = io {
        database.changedOne { claim(engineId, now, sessionId) }
    }

    override suspend fun complete(
        sessionId: String,
        status: String,
        summary: String?,
        model: String?,
        now: Long,
    ): Boolean = io {
        database.changedOne { complete(status, summary, model, now, sessionId) }
    }

    override suspend fun fail(
        sessionId: String,
        status: String,
        attemptCount: Int,
        nextAttemptAt: Long?,
        lastError: String?,
        now: Long,
        dropText: Boolean,
    ): Boolean = io {
        database.changedOne {
            if (dropText) {
                failDroppingText(
                    status = status,
                    attemptCount = attemptCount.toLong(),
                    lastError = lastError,
                    now = now,
                    sessionId = sessionId,
                )
                return@changedOne
            }
            fail(
                status = status,
                attemptCount = attemptCount.toLong(),
                nextAttemptAt = nextAttemptAt,
                lastError = lastError,
                now = now,
                sessionId = sessionId,
            )
        }
    }

    override suspend fun recoverStaleRunning(staleBefore: Long, now: Long, maxAttempts: Int): Long = io {
        database.changed {
            recoverStaleRunning(maxAttempts = maxAttempts.toLong(), now = now, staleBefore = staleBefore)
        }
    }

    override suspend fun requeue(sessionId: String, now: Long): Boolean = io {
        database.changedOne { requeue(now, sessionId) }
    }

    override suspend fun withdrawText(now: Long) {
        io { database.sessionRecapQueries.withdrawText(now) }
    }

    override suspend fun applyRetention(excerptCutoff: Long, rowCutoff: Long, now: Long): Long = io {
        database.applyRecapRetention(excerptCutoff, rowCutoff, now)
    }

    override suspend fun deleteRecap(sessionId: String) {
        io { database.sessionRecapQueries.deleteRecap(sessionId) }
    }

    override suspend fun deleteAllRecaps() {
        io { database.sessionRecapQueries.deleteAllRecaps() }
    }

    override suspend fun getCloudRows(accountId: String) = io {
        database.sessionRecapQueries.getCloudRows(accountId).executeAsList().map { it.toEntity() }
    }

    override suspend fun cacheCloudRecap(entity: SessionRecapEntity) = io {
        database.transaction {
        database.sessionRecapQueries.insertCloudRecap(
            entity.sessionId, entity.serverId, entity.bookUuid, entity.status,
            entity.endHref, entity.endProgression, entity.endTotalProgression,
            entity.language, entity.summary, entity.model, entity.createdAt, entity.updatedAt,
            entity.endedAt, entity.generatedAt, entity.cloudAccountId, entity.cloudBookId,
            entity.cloudChangeId, entity.cloudExpiresAt,
        )
        database.sessionRecapQueries.updateCloudRecap(
            status = entity.status, summary = entity.summary, model = entity.model, lastError = entity.lastError,
            endHref = entity.endHref, endProgression = entity.endProgression, endTotalProgression = entity.endTotalProgression,
            endedAt = entity.endedAt, language = entity.language, updatedAt = entity.updatedAt, generatedAt = entity.generatedAt,
            cloudBookId = entity.cloudBookId, cloudChangeId = entity.cloudChangeId, cloudExpiresAt = entity.cloudExpiresAt,
            sessionId = entity.sessionId, cloudAccountId = entity.cloudAccountId,
        )
        }
    }

    override suspend fun markCloudQueued(sessionId: String, accountId: String, cloudBookId: String?, running: Boolean, now: Long) = io {
        database.changedOne { markCloudQueued(if (running) "CLOUD_RUNNING" else "CLOUD_QUEUED", accountId, cloudBookId, now, sessionId) }
    }
    override suspend fun queueCloudDeletion(accountId: String, sessionId: String) = io {
        database.transaction {
            database.recapCloudSyncQueries.queueDelete(accountId, sessionId)
            database.sessionRecapQueries.deleteRecap(sessionId)
        }
    }
    override suspend fun getCloudDeletions(accountId: String) = io { database.recapCloudSyncQueries.getDeletes(accountId).executeAsList() }
    override suspend fun acknowledgeCloudDeletion(accountId: String, sessionId: String) = io { database.recapCloudSyncQueries.acknowledgeDelete(accountId, sessionId) }
    override suspend fun queueCloudWithdrawal(accountId: String, now: Long) = io {
        database.transaction {
            database.recapCloudSyncQueries.queueWithdrawal(accountId)
            database.recapCloudSyncQueries.clearCursors(accountId)
            database.sessionRecapQueries.withdrawText(now)
            database.sessionRecapQueries.purgeCloudAccount(accountId)
        }
    }
    override suspend fun hasCloudWithdrawal(accountId: String) = io { database.recapCloudSyncQueries.getWithdrawal(accountId).executeAsOneOrNull() != null }
    override suspend fun acknowledgeCloudWithdrawal(accountId: String) = io { database.recapCloudSyncQueries.acknowledgeWithdrawal(accountId) }
    override suspend fun enableCloudConsent(accountId: String) = io { database.recapCloudSyncQueries.enableConsent(accountId) }
    override suspend fun getCloudCursor(accountId: String, bookId: String) = io { database.recapCloudSyncQueries.getCursor(accountId, bookId).executeAsOneOrNull() ?: 0L }
    override suspend fun setCloudCursor(accountId: String, bookId: String, cursor: Long) = io { database.recapCloudSyncQueries.setCursor(accountId, bookId, cursor) }
    override suspend fun bindCloudIdentity(sessionId: String, cloudBookId: String?) = io { database.changedOne { bindCloudIdentity(cloudBookId, sessionId) } }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

/** INSERT OR IGNORE plus a read, so "already exists" is driver-independent. */
internal fun AppDatabase.insertCapturingRow(entity: SessionRecapEntity): Boolean =
    transactionWithResult {
        val queries = sessionRecapQueries
        if (queries.getRecap(entity.sessionId).executeAsOneOrNull() != null) {
            return@transactionWithResult false
        }
        queries.insertCapturing(
            session_id = entity.sessionId,
            server_id = entity.serverId,
            book_uuid = entity.bookUuid,
            start_href = entity.startHref,
            start_progression = entity.startProgression,
            start_total_progression = entity.startTotalProgression,
            start_chapter_index = entity.startChapterIndex?.toLong(),
            start_chapter_title = entity.startChapterTitle,
            furthest_total_progression = entity.furthestTotalProgression,
            language = entity.language,
            created_at = entity.createdAt,
            updated_at = entity.updatedAt,
            cloud_account_id = entity.cloudAccountId,
            consent_version = entity.consentVersion.toLong(),
        )
        true
    }

/** Retention in one transaction; returns the number of deleted rows. */
internal fun AppDatabase.applyRecapRetention(
    excerptCutoff: Long,
    rowCutoff: Long,
    now: Long,
): Long = transactionWithResult {
    val queries = sessionRecapQueries
    queries.clearFinishedExcerpts()
    queries.expireExcerpts(now = now, cutoff = excerptCutoff)
    queries.deleteOlderThan(cutoff = rowCutoff, now = now)
    val old = queries.rowsChanged().executeAsOne()
    queries.deleteOrphans()
    old + queries.rowsChanged().executeAsOne()
}

/** Runs one statement and returns SQLite's changes() on the same connection. */
internal fun AppDatabase.changed(statement: SessionRecapQueries.() -> Unit): Long =
    transactionWithResult {
        sessionRecapQueries.statement()
        sessionRecapQueries.rowsChanged().executeAsOne()
    }

internal fun AppDatabase.changedOne(statement: SessionRecapQueries.() -> Unit): Boolean =
    changed(statement) == 1L

internal fun Session_recap.toEntity(): SessionRecapEntity = SessionRecapEntity(
    sessionId = session_id,
    serverId = server_id,
    bookUuid = book_uuid,
    status = status,
    startHref = start_href,
    startProgression = start_progression,
    startTotalProgression = start_total_progression,
    endHref = end_href,
    endProgression = end_progression,
    endTotalProgression = end_total_progression,
    startChapterIndex = start_chapter_index?.toInt(),
    startChapterTitle = start_chapter_title,
    endChapterIndex = end_chapter_index?.toInt(),
    endChapterTitle = end_chapter_title,
    furthestTotalProgression = furthest_total_progression,
    pageAdvances = page_advances.toInt(),
    ttsSentences = tts_sentences.toInt(),
    activeReadingMs = active_reading_ms,
    excerpt = excerpt,
    excerptHash = excerpt_hash,
    lastSentence = last_sentence,
    language = language,
    attemptCount = attempt_count.toInt(),
    nextAttemptAt = next_attempt_at,
    lastError = last_error,
    summary = summary,
    engineId = engine_id,
    model = model,
    createdAt = created_at,
    updatedAt = updated_at,
    endedAt = ended_at,
    generatedAt = generated_at,
    cloudAccountId = cloud_account_id,
    cloudBookId = cloud_book_id,
    consentVersion = consent_version.toInt(),
    cloudChangeId = cloud_change_id,
    cloudExpiresAt = cloud_expires_at,
    cloudIdentityBound = cloud_identity_bound != 0L,
)
