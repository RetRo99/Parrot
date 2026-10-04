package com.retro99.reader.data.recap

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.database.api.recap.SessionRecapCapture
import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.reader.domain.recap.RecapEngine
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapInput
import com.retro99.reader.domain.recap.RecapResult
import com.retro99.reader.domain.recap.RecapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

class TestClock(var nowMs: Long = 1_000_000L) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
}

/** In-memory twin of SessionRecap.sq, with the same conditional rules. */
class FakeSessionRecapDatabase : SessionRecapDatabase {
    val withdrawals = mutableSetOf<String>()
    val deletions = mutableSetOf<Pair<String, String>>()
    val cursors = mutableMapOf<Pair<String, String>, Long>()
    override suspend fun getCloudRows(accountId: String) = rows.value.values.filter { it.cloudAccountId == accountId }
    override suspend fun cacheCloudRecap(entity: SessionRecapEntity) {
        val old = rows.value[entity.sessionId]
        if (old == null || (old.cloudAccountId == entity.cloudAccountId && old.cloudChangeId <= entity.cloudChangeId)) put(entity)
    }
    override suspend fun markCloudQueued(sessionId: String, accountId: String, cloudBookId: String?, running: Boolean, now: Long) =
        update(sessionId, { it.status == "RUNNING" }) { it.copy(status = if (running) "CLOUD_RUNNING" else "CLOUD_QUEUED",
            cloudAccountId = accountId, cloudBookId = cloudBookId, excerpt = null, lastSentence = null, updatedAt = now) }
    override suspend fun queueCloudDeletion(accountId: String, sessionId: String) { deletions += accountId to sessionId; deleteRecap(sessionId) }
    override suspend fun getCloudDeletions(accountId: String) = deletions.filter { it.first == accountId }.map { it.second }
    override suspend fun acknowledgeCloudDeletion(accountId: String, sessionId: String) { deletions -= accountId to sessionId }
    override suspend fun queueCloudWithdrawal(accountId: String, now: Long) { withdrawals += accountId; rows.value = rows.value.filterValues { it.cloudAccountId != accountId } }
    override suspend fun hasCloudWithdrawal(accountId: String) = accountId in withdrawals
    override suspend fun acknowledgeCloudWithdrawal(accountId: String) { withdrawals -= accountId }
    override suspend fun enableCloudConsent(accountId: String) { withdrawals -= accountId }
    override suspend fun getCloudCursor(accountId: String, bookId: String) = cursors[accountId to bookId] ?: 0L
    override suspend fun setCloudCursor(accountId: String, bookId: String, cursor: Long) { cursors[accountId to bookId] = cursor }
    override suspend fun bindCloudIdentity(sessionId: String, cloudBookId: String?) =
        update(sessionId, { !it.cloudIdentityBound }) { it.copy(cloudBookId = cloudBookId, cloudIdentityBound = true) }
    val rows = MutableStateFlow<Map<String, SessionRecapEntity>>(emptyMap())
    var retentionCalls = mutableListOf<Triple<Long, Long, Long>>()

    operator fun get(id: String): SessionRecapEntity? = rows.value[id]

    fun put(entity: SessionRecapEntity) {
        rows.value = rows.value + (entity.sessionId to entity)
    }

    private fun update(id: String, check: (SessionRecapEntity) -> Boolean, change: (SessionRecapEntity) -> SessionRecapEntity): Boolean {
        val row = rows.value[id]?.takeIf(check) ?: return false
        put(change(row))
        return true
    }

    override suspend fun insertCapturing(entity: SessionRecapEntity): Boolean {
        if (entity.sessionId in rows.value) return false
        put(entity)
        return true
    }

    override suspend fun updateCapture(sessionId: String, capture: SessionRecapCapture, now: Long) =
        update(sessionId, { it.status == "CAPTURING" && it.lastError == null }) {
            it.withCapture(capture).copy(updatedAt = now)
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
    ) = update(sessionId, { it.status == "CAPTURING" }) {
        it.withCapture(capture).copy(
            status = status,
            excerptHash = excerptHash,
            lastSentence = lastSentence,
            activeReadingMs = activeReadingMs,
            lastError = lastError,
            endedAt = endedAt,
            updatedAt = endedAt,
        )
    }

    override suspend fun getRecap(sessionId: String) = rows.value[sessionId]

    override fun observeRecap(sessionId: String): Flow<SessionRecapEntity?> =
        rows.map { it[sessionId] }

    override fun observeLatestForBook(bookUuid: String): Flow<SessionRecapEntity?> = rows.map { all ->
        all.values.filter { it.bookUuid == bookUuid && it.status !in HIDDEN }
            .sortedWith(compareBy<SessionRecapEntity> { if (it.status == "SUCCEEDED") 0 else 1 }
                .thenByDescending { it.createdAt })
            .firstOrNull()
    }

    override fun observeForBook(bookUuid: String): Flow<List<SessionRecapEntity>> = rows.map { all ->
        all.values.filter { it.bookUuid == bookUuid && it.status !in HIDDEN }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun getCapturing() = rows.value.values.filter { it.status == "CAPTURING" }

    override suspend fun getPreviousEnded(bookUuid: String, sessionId: String, createdAt: Long) =
        rows.value.values
            .filter {
                it.bookUuid == bookUuid && it.sessionId != sessionId &&
                    it.status != "CAPTURING" && it.createdAt <= createdAt
            }
            .maxByOrNull { it.createdAt }

    private fun isDue(row: SessionRecapEntity, now: Long) =
        row.status in DUE && row.excerpt != null && (row.nextAttemptAt ?: Long.MIN_VALUE) <= now

    override suspend fun getNextDue(now: Long) =
        rows.value.values.filter { isDue(it, now) }.minByOrNull { it.createdAt }

    override suspend fun getEarliestScheduled(now: Long) =
        rows.value.values
            .filter { it.status in DUE && it.excerpt != null && (it.nextAttemptAt ?: 0) > now }
            .mapNotNull { it.nextAttemptAt }
            .minOrNull()

    override suspend fun getOldestRunningUpdate() =
        rows.value.values.filter { it.status == "RUNNING" }.minOfOrNull { it.updatedAt }

    override suspend fun claim(sessionId: String, engineId: String, now: Long) =
        update(sessionId, { isDue(it, now) }) {
            it.copy(status = "RUNNING", attemptCount = it.attemptCount + 1, engineId = engineId, updatedAt = now)
        }

    override suspend fun complete(sessionId: String, status: String, summary: String?, model: String?, now: Long) =
        update(sessionId, { it.status == "RUNNING" }) {
            it.copy(
                status = status, summary = summary, model = model, excerpt = null, lastSentence = null,
                lastError = null,
                nextAttemptAt = null, generatedAt = now, updatedAt = now,
            )
        }

    override suspend fun fail(
        sessionId: String,
        status: String,
        attemptCount: Int,
        nextAttemptAt: Long?,
        lastError: String?,
        now: Long,
        dropText: Boolean,
    ) = update(sessionId, { it.status == "RUNNING" }) {
        val failed = it.copy(
            status = status, attemptCount = attemptCount, nextAttemptAt = nextAttemptAt,
            lastError = lastError, updatedAt = now,
        )
        if (dropText) failed.copy(excerpt = null, lastSentence = null, nextAttemptAt = null) else failed
    }

    override suspend fun recoverStaleRunning(staleBefore: Long, now: Long, maxAttempts: Int): Long {
        val stale = rows.value.values.filter { it.status == "RUNNING" && it.updatedAt < staleBefore }
        stale.forEach { row ->
            val spent = row.attemptCount >= maxAttempts
            put(
                row.copy(
                    status = if (spent) "FAILED_PERMANENT" else "PENDING",
                    lastError = if (spent) "MAX_ATTEMPTS" else row.lastError,
                    updatedAt = now,
                ),
            )
        }
        return stale.size.toLong()
    }

    override suspend fun requeue(sessionId: String, now: Long) =
        update(sessionId, { it.status == "FAILED_PERMANENT" && it.excerpt != null }) {
            it.copy(
                status = "PENDING", attemptCount = 0, lastError = null, updatedAt = now,
                nextAttemptAt = it.nextAttemptAt?.takeIf { at -> at > now },
            )
        }

    override suspend fun withdrawText(now: Long) {
        rows.value.values
            .filter { it.status in WITHDRAWABLE }
            .filter { it.excerpt != null || it.lastSentence != null || it.status == "CAPTURING" }
            .forEach { row ->
                val waiting = row.status in setOf("PENDING", "FAILED_RETRYABLE", "FAILED_PERMANENT")
                put(
                    row.copy(
                        excerpt = null,
                        lastSentence = null,
                        status = if (waiting) "FAILED_PERMANENT" else row.status,
                        lastError = if (row.status == "RUNNING") row.lastError else "CONSENT_WITHDRAWN",
                        nextAttemptAt = null,
                        updatedAt = now,
                    ),
                )
            }
    }

    override suspend fun applyRetention(excerptCutoff: Long, rowCutoff: Long, now: Long): Long {
        retentionCalls += Triple(excerptCutoff, rowCutoff, now)
        return 0
    }

    override suspend fun deleteRecap(sessionId: String) {
        rows.value = rows.value - sessionId
    }

    override suspend fun deleteAllRecaps() {
        rows.value = emptyMap()
    }

    private fun SessionRecapEntity.withCapture(capture: SessionRecapCapture) = copy(
        excerpt = capture.excerpt,
        endHref = capture.endHref,
        endProgression = capture.endProgression,
        endTotalProgression = capture.endTotalProgression,
        endChapterIndex = capture.endChapterIndex,
        endChapterTitle = capture.endChapterTitle,
        furthestTotalProgression = capture.furthestTotalProgression,
        pageAdvances = capture.pageAdvances,
        ttsSentences = capture.ttsSentences,
    )

    private companion object {
        val HIDDEN = setOf("CAPTURING", "SKIPPED_INELIGIBLE")
        val DUE = setOf("PENDING", "FAILED_RETRYABLE")
        val WITHDRAWABLE = setOf("CAPTURING", "PENDING", "RUNNING", "FAILED_RETRYABLE", "FAILED_PERMANENT")
    }
}

class FakeRecapEngine(
    var next: suspend (RecapInput) -> RecapResult = { RecapResult.Success("A summary.", "hy3") },
) : RecapEngine {
    override val id: String = RecapEngine.CLOUD_ENGINE_ID
    val inputs = mutableListOf<RecapInput>()

    override suspend fun generate(input: RecapInput): RecapResult {
        inputs += input
        return next(input)
    }
}

class FakeRecapSelector(var engine: RecapEngine?) : RecapEngineSelector {
    val available = MutableStateFlow(engine != null)
    override suspend fun select(): RecapEngine? = engine
    override fun observeAvailable(): Flow<Boolean> = available
}

class FakeRecapSettings(enabled: Boolean = true) : RecapSettings {
    val enabled = MutableStateFlow(enabled)
    override fun observeCloudRecapsEnabled(): Flow<Boolean> = enabled
    override fun observeConsentGiven(): Flow<Boolean> = enabled
    override suspend fun isCloudRecapsEnabled(): Boolean = enabled.value
    override suspend fun setCloudRecapsEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }
}

class RecordingAnalytics : Analytics {
    val breadcrumbs = mutableListOf<DiagnosticContext>()
    val messages = mutableListOf<String?>()

    override fun logException(throwable: Throwable, message: String?) {
        messages += message
    }

    override fun logBreadcrumb(context: DiagnosticContext) {
        breadcrumbs += context
    }

    override fun logException(throwable: Throwable, context: DiagnosticContext) {
        breadcrumbs += context
    }

    override fun logEvent(event: AnalyticsEvent) = Unit

    override fun setUserId(userId: String?) = Unit
}

fun pendingRow(
    id: String,
    createdAt: Long = 1,
    excerpt: String? = "Read text ".repeat(50),
    status: String = "PENDING",
    attemptCount: Int = 0,
    nextAttemptAt: Long? = null,
    bookUuid: String = "book",
) = SessionRecapEntity(
    sessionId = id,
    serverId = "server",
    bookUuid = bookUuid,
    status = status,
    excerpt = excerpt,
    language = "sl",
    lastSentence = "She stopped here.",
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    createdAt = createdAt,
    updatedAt = createdAt,
)
