package com.retro99.reader.data.recap

import com.retro99.reader.domain.recap.RecapRetryResult
import com.retro99.reader.domain.recap.RecapStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRecapDataRepositoryTest {

    private val clock = TestClock()
    private val database = FakeSessionRecapDatabase()
    private val engine = FakeRecapEngine()
    private val runner = RecapJobRunner(
        database, FakeRecapSelector(engine), RecapDiagnostics(RecordingAnalytics()), clock,
        StandardTestDispatcher(),
    )
    private val repository = SessionRecapDataRepository(database, runner, clock)

    @Test
    fun observingNeverGenerates() = runTest {
        database.put(pendingRow("s1"))

        assertEquals(RecapStatus.PENDING, repository.observeRecap("s1").first()!!.status)
        assertEquals("s1", repository.observeLatestForBook("book").first()!!.sessionId)
        assertEquals(1, repository.observeHistory("book").first().size)
        assertEquals(0, engine.inputs.size)
    }

    @Test
    fun latestPrefersSucceeded() = runTest {
        database.put(pendingRow("old", createdAt = 1, status = "SUCCEEDED", excerpt = null).copy(summary = "S"))
        database.put(pendingRow("new", createdAt = 2, status = "FAILED_RETRYABLE"))
        database.put(pendingRow("skip", createdAt = 3, status = "SKIPPED_INELIGIBLE"))

        assertEquals("old", repository.observeLatestForBook("book").first()!!.sessionId)
    }

    @Test
    fun retryRequeuesAFailedRow() = runTest {
        database.put(pendingRow("s1", status = "FAILED_PERMANENT", attemptCount = 5).copy(lastError = "PROVIDER_ERROR"))

        assertEquals(RecapRetryResult.QUEUED, repository.retry("s1"))
        assertEquals("PENDING", database["s1"]!!.status)
        assertEquals(0, database["s1"]!!.attemptCount)
    }

    @Test
    fun retryRefusesRejectedInputAndFinishedRows() = runTest {
        database.put(pendingRow("bad", status = "FAILED_PERMANENT").copy(lastError = "EXCERPT_TOO_SHORT"))
        database.put(pendingRow("done", status = "SUCCEEDED", excerpt = null))
        database.put(pendingRow("gone", status = "FAILED_PERMANENT", excerpt = null))

        assertEquals(RecapRetryResult.NOT_RETRYABLE, repository.retry("bad"))
        assertEquals(RecapRetryResult.NOT_RETRYABLE, repository.retry("done"))
        assertEquals(RecapRetryResult.NOT_RETRYABLE, repository.retry("gone"))
        assertEquals(RecapRetryResult.NOT_FOUND, repository.retry("missing"))
    }
}
