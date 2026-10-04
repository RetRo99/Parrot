package com.retro99.statistics.ui

import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapEngine
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapRequestResult
import com.retro99.reader.domain.recap.RecapRetryResult
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class FakeRecapRepository(
    recaps: Map<String, SessionRecap> = emptyMap(),
    var retryResult: RecapRetryResult = RecapRetryResult.QUEUED,
) : RecapRepository {
    var requestResult = RecapRequestResult.QUEUED
    var requestHandler: (suspend () -> RecapRequestResult)? = null
    val requested = mutableListOf<String>()
    override suspend fun request(sessionId: String): RecapRequestResult {
        requested += sessionId
        return requestHandler?.invoke() ?: requestResult
    }
    override fun observeProgression(bookId: String): Flow<Double?> = flowOf(null)
    override suspend fun delete(sessionId: String) { recaps.value = recaps.value - sessionId }
    val recaps = MutableStateFlow(recaps)
    val observed = mutableListOf<String>()
    val retried = mutableListOf<String>()

    override fun observeRecap(sessionId: String): Flow<SessionRecap?> {
        observed += sessionId
        return recaps.map { it[sessionId] }
    }

    override fun observeLatestForBook(bookId: String): Flow<SessionRecap?> = flowOf(null)

    override fun observeHistory(bookId: String): Flow<List<SessionRecap>> = flowOf(emptyList())

    override suspend fun retry(sessionId: String): RecapRetryResult {
        retried += sessionId
        return retryResult
    }
}

internal class FakeRecapSettings(enabled: Boolean = false) : RecapSettings {
    override fun observeFeatureAvailable(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
    override fun observeSignedIn(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
    val enabled = MutableStateFlow(enabled)

    /** Consent alone; defaults to [enabled], point it elsewhere to model kept consent without a session. */
    var consent: MutableStateFlow<Boolean> = this.enabled

    override fun observeCloudRecapsEnabled(): Flow<Boolean> = enabled

    override fun observeConsentGiven(): Flow<Boolean> = consent

    override suspend fun isCloudRecapsEnabled(): Boolean = enabled.value

    override suspend fun setCloudRecapsEnabled(enabled: Boolean) {
        this.enabled.value = enabled
        consent.value = enabled
    }
}

/** Never selects an engine: viewing must not generate. */
internal class FakeRecapEngineSelector(available: Boolean = false) : RecapEngineSelector {
    val available = MutableStateFlow(available)
    var selectCalls = 0

    override suspend fun select(): RecapEngine? {
        selectCalls++
        return null
    }

    override fun observeAvailable(): Flow<Boolean> = available
}

internal fun sessionRecap(
    status: RecapStatus,
    sessionId: String = "recap-1",
    summary: String? = null,
    lastError: RecapErrorCode? = null,
    engineId: String? = null,
    model: String? = null,
    canRetry: Boolean = false,
) = SessionRecap(
    sessionId = sessionId,
    serverId = "server",
    bookId = "book",
    status = status,
    summary = summary,
    lastError = lastError,
    startPosition = RecapPosition(),
    endPosition = RecapPosition(),
    startChapter = RecapChapter(),
    endChapter = RecapChapter(),
    attemptCount = 0,
    nextAttemptAt = null,
    engineId = engineId,
    model = model,
    createdAt = 1,
    updatedAt = 1,
    endedAt = 2,
    generatedAt = null,
    canRetry = canRetry,
)
