package com.retro99.statistics.ui

import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapEngine
import com.retro99.reader.domain.recap.RecapEngineSelector
import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapRepository
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
    val enabled = MutableStateFlow(enabled)

    override fun observeCloudRecapsEnabled(): Flow<Boolean> = enabled

    override suspend fun isCloudRecapsEnabled(): Boolean = enabled.value

    override suspend fun setCloudRecapsEnabled(enabled: Boolean) {
        this.enabled.value = enabled
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
