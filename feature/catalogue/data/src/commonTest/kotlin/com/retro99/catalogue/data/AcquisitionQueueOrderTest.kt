package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.AcquisitionState.Adding
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Downloading
import com.retro99.catalogue.domain.AcquisitionState.Failed
import com.retro99.catalogue.domain.AcquisitionState.Interrupted
import com.retro99.catalogue.domain.AcquisitionState.Waiting
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcquisitionQueueOrderTest {

    @Test
    fun `at most two run and the rest start in request order`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()

        // When
        (1..4).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()

        // Then
        assertEquals(2, CatalogueAcquisitionLimits.MAX_RUNNING)
        assertEquals(
            mapOf("book-1" to Downloading, "book-2" to Downloading, "book-3" to Waiting, "book-4" to Waiting),
            harness.states(),
        )

        // When
        harness.source.release("book-2")
        runCurrent()

        // Then
        assertEquals(
            mapOf("book-1" to Downloading, "book-2" to Done, "book-3" to Downloading, "book-4" to Waiting),
            harness.states(),
        )

        // When
        harness.source.releaseAll()
        runCurrent()

        // Then
        assertTrue(harness.states().values.all { state -> state == Done })
        assertEquals(listOf("book-1", "book-2", "book-3", "book-4"), harness.source.calls.map { it.locator.publicationKey })
        assertEquals(2, harness.source.mostRunningAtOnce)
    }

    @Test
    fun `a second identical request returns the existing one`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()

        // When
        val first = harness.queue.requestQueued(bookRequest(1))
        val second = harness.queue.requestQueued(bookRequest(1))
        val together = listOf(
            async { harness.queue.requestQueued(bookRequest(2)) },
            async { harness.queue.requestQueued(bookRequest(2)) },
        ).awaitAll()
        runCurrent()

        // Then
        assertEquals(first.requestId, second.requestId)
        assertEquals(together[0].requestId, together[1].requestId)
        assertEquals(listOf("book-1", "book-2"), harness.database.peek("p1").map { it.publicationKey })
        assertEquals(2, harness.source.calls.size)
    }

    @Test
    fun `another file of the same book or the same book in another catalogue is its own request`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()

        // When
        val epub = harness.queue.requestQueued(bookRequest(1))
        val secondFile = harness.queue.requestQueued(bookRequest(1, representationKey = "application/epub+zip#2"))
        val elsewhere = harness.queue.requestQueued(bookRequest(1, sourceId = "source-2"))

        // Then
        assertEquals(3, setOf(epub.requestId, secondFile.requestId, elsewhere.requestId).size)
    }

    @Test
    fun `a failed request for the same file is returned instead of a second one`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.failNext("book-1", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused))
        val first = harness.queue.requestQueued(bookRequest(1))
        runCurrent()
        assertEquals(Failed(AcquisitionFailureReason.Refused), harness.state("book-1"))

        // When
        val again = harness.queue.requestQueued(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(first.requestId, again.requestId)
        assertEquals(Failed(AcquisitionFailureReason.Refused), again.state)
        assertEquals(1, harness.source.calls.size, "asking again does not retry behind the user's back")
    }

    @Test
    fun `a finished book that was removed from the library can be requested again as a new request`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        val first = harness.queue.requestQueued(bookRequest(1))
        runCurrent()
        assertEquals(Done, harness.state("book-1"))
        harness.world.libraryBooks.getValue("p1").clear()

        // When
        val again = harness.queue.requestQueued(bookRequest(1))
        runCurrent()

        // Then
        assertNotEquals(first.requestId, again.requestId)
        assertEquals(2, harness.database.peek("p1").size)
    }

    @Test
    fun `the order is stored and after a restart nothing starts by itself`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        (1..4).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()
        val positions = harness.database.peek("p1").map { it.publicationKey to it.queuePosition }

        // When
        val restarted = harness.restart()
        restarted.restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(
            mapOf("book-1" to Interrupted, "book-2" to Interrupted, "book-3" to Waiting, "book-4" to Waiting),
            harness.states(),
        )
        assertEquals(positions, harness.database.peek("p1").map { it.publicationKey to it.queuePosition })
        assertEquals(2, harness.source.calls.size, "no download started after the restart")
        assertEquals(listOf(Interrupted, Interrupted, Waiting, Waiting), restarted.observeAcquisitions().first().map { it.state })
    }

    @Test
    fun `an explicit start resumes waiting requests in order and leaves interrupted ones alone`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        (1..5).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()
        val restarted = harness.restart()
        restarted.restoreAfterRestart()

        // When
        restarted.start()
        runCurrent()

        // Then
        assertEquals(
            mapOf(
                "book-1" to Interrupted, "book-2" to Interrupted,
                "book-3" to Downloading, "book-4" to Downloading, "book-5" to Waiting,
            ),
            harness.states(),
        )
    }

    @Test
    fun `start again puts an interrupted download behind the ones already waiting`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        (1..4).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()
        val restarted = harness.restart()
        restarted.restoreAfterRestart()
        val first = harness.row("book-1")!!

        // When
        assertTrue(restarted.startAgain(first.requestId))
        runCurrent()

        // Then: starting again is a user action, so the queue runs; book 1 is last in line.
        assertEquals(
            mapOf("book-2" to Interrupted, "book-3" to Downloading, "book-4" to Downloading, "book-1" to Waiting),
            harness.states(),
        )
        assertEquals(0L, harness.row("book-1")!!.bytesSoFar)

        // When
        harness.source.releaseAll()
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertEquals(2, harness.row("book-1")!!.attempts)
        assertEquals(Interrupted, harness.state("book-2"))
    }

    @Test
    fun `a retried download goes behind the ones already waiting`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.failNext("book-1", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Connection))
        val failed = harness.queue.requestQueued(bookRequest(1))
        runCurrent()
        harness.source.holdAll()
        (2..4).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()

        // When
        assertTrue(harness.queue.retry(failed.requestId))
        runCurrent()

        // Then
        assertEquals(listOf("book-2", "book-3", "book-4", "book-1"), harness.database.peek("p1").map { it.publicationKey })
        assertEquals(Waiting, harness.state("book-1"))
        assertNull(harness.row("book-1")!!.failureReason)
    }

    @Test
    fun `retry and dismiss are each offered for their own failures only`() = runTest {
        val retryable = setOf(AcquisitionFailureReason.Connection, AcquisitionFailureReason.Storage, AcquisitionFailureReason.Refused)
        val dismissible = setOf(AcquisitionFailureReason.TooLarge, AcquisitionFailureReason.Invalid, AcquisitionFailureReason.Protected)
        AcquisitionFailureReason.entries.forEachIndexed { index, reason ->
            // Given
            val harness = QueueHarness(backgroundScope)
            harness.source.holdAll()
            val request = harness.queue.requestQueued(bookRequest(index))
            runCurrent()
            harness.restart().restoreAfterRestart()
            harness.world.databaseLocked = true
            harness.database.update(harness.row("book-$index")!!.copy(state = "failed", failureReason = reason.key))
            harness.world.databaseLocked = false
            val queue = harness.restart()

            // When / Then
            assertEquals(reason in dismissible, queue.dismiss(request.requestId), "dismiss $reason")
            if (reason in dismissible) {
                assertNull(harness.row("book-$index"))
            } else {
                assertEquals(reason in retryable, queue.retry(request.requestId), "retry $reason")
                assertFalse(queue.startAgain(request.requestId), "start again $reason")
            }
        }
    }

    @Test
    fun `saved account details requeue the sign in failures of that catalogue only`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        val signIn = CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.SignInNeeded)
        harness.source.failNext("book-1", signIn)
        harness.source.failNext("book-2", signIn)
        harness.source.failNext("book-3", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused))
        val first = harness.queue.requestQueued(bookRequest(1))
        harness.queue.request(bookRequest(2, sourceId = "source-2"))
        harness.queue.request(bookRequest(3))
        runCurrent()
        assertFalse(harness.queue.retry(first.requestId), "a sign-in failure is not retried without signing in")

        // When
        harness.queue.signedIn("source-1")
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertEquals(Failed(AcquisitionFailureReason.SignIn), harness.state("book-2"))
        assertEquals(Failed(AcquisitionFailureReason.Refused), harness.state("book-3"))
    }

    @Test
    fun `a request that is being added keeps its slot until it is added`() = runTest {
        // Given: the library import does not come back yet
        val harness = QueueHarness(backgroundScope)
        harness.adder.gate = CompletableDeferred()

        // When
        (1..3).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()

        // Then
        assertEquals(mapOf<String, AcquisitionState?>("book-1" to Adding, "book-2" to Adding, "book-3" to Waiting), harness.states())

        // When
        harness.adder.gate!!.complete(Unit)
        runCurrent()

        // Then
        assertEquals(mapOf<String, AcquisitionState?>("book-1" to Done, "book-2" to Done, "book-3" to Done), harness.states())
    }
}
