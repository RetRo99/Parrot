package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState.Adding
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Downloading
import com.retro99.catalogue.domain.AcquisitionState.Failed
import com.retro99.catalogue.domain.AcquisitionState.Interrupted
import com.retro99.catalogue.domain.AcquisitionState.Waiting
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcquisitionQueueFencingTest {

    @Test
    fun `switching profile stops its downloads and stores them as interrupted`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        (1..3).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()

        // When
        harness.switchProfile("p2")
        runCurrent()

        // Then
        assertEquals(mapOf("book-1" to Interrupted, "book-2" to Interrupted, "book-3" to Waiting), harness.states("p1"))
        assertEquals(listOf("book-1", "book-2"), harness.source.cancelled.sorted())
        assertTrue(harness.files.files.isEmpty())
        assertTrue(harness.database.peek("p2").isEmpty())
        assertTrue(harness.queue.observeAcquisitions().first().isEmpty(), "the other profile's downloads are not shown")

        // When: the old profile's network calls come back late
        harness.source.releaseAll()
        runCurrent()

        // Then
        assertEquals(mapOf("book-1" to Interrupted, "book-2" to Interrupted, "book-3" to Waiting), harness.states("p1"))
        assertTrue(harness.database.peek("p2").isEmpty())
        assertTrue(harness.adder.calls.isEmpty())
    }

    @Test
    fun `each profile has its own queue and coming back starts nothing`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        (1..3).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()
        harness.switchProfile("p2")

        // When
        harness.source.releaseAll()
        harness.queue.request(bookRequest(9))
        runCurrent()

        // Then
        assertEquals(mapOf("book-9" to Done), harness.states("p2"))
        assertEquals("p2", harness.adder.calls.single().profileId)
        assertEquals("p2", harness.source.calls.last().profileId)

        // When
        harness.switchProfile("p1")
        val listed = harness.queue.observeAcquisitions().first()
        runCurrent()

        // Then
        assertEquals(listOf(Interrupted, Interrupted, Waiting), listed.map { it.state })
        assertEquals(mapOf("book-1" to Interrupted, "book-2" to Interrupted, "book-3" to Waiting), harness.states("p1"))
        assertEquals(mapOf("book-9" to Done), harness.states("p2"))
    }

    @Test
    fun `a profile that changed without notice gets nothing written into it`() = runTest {
        // Given: the open profile changes and nobody told the queue
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        harness.queue.request(bookRequest(1))
        harness.queue.request(bookRequest(2))
        runCurrent()
        harness.world.activeProfile = "p2"

        // When: the downloads finish and try to store their result
        harness.source.releaseAll()
        runCurrent()

        // Then
        assertTrue(harness.database.peek("p2").isEmpty())
        assertEquals(mapOf("book-1" to Downloading, "book-2" to Downloading), harness.states("p1"), "p1 was not written either")
        assertTrue(harness.adder.calls.isEmpty())
        assertTrue(harness.files.files.isEmpty(), "the orphaned files are removed")

        // When: p1 is opened again
        harness.world.activeProfile = "p1"
        harness.restart().restoreAfterRestart()

        // Then
        assertEquals(mapOf("book-1" to Interrupted, "book-2" to Interrupted), harness.states("p1"))
    }

    @Test
    fun `a profile closed while a book is being added does not finish the request`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.adder.gate = CompletableDeferred()
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Adding, harness.state("book-1"))

        // When: the profile is deleted or switched mid-finalization
        harness.switchProfile("p2")
        harness.adder.gate!!.complete(Unit)
        runCurrent()

        // Then
        assertEquals(Interrupted, harness.state("book-1", "p1"))
        assertTrue(harness.database.peek("p2").isEmpty())
        assertTrue(harness.files.files.isEmpty())
        assertEquals(null, harness.row("book-1", "p1")!!.libraryBookId)
    }

    @Test
    fun `a request is refused for a profile that is closed`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.profileWork.cancel("p1")

        // When
        val outcome = runCatching { harness.queue.request(bookRequest(1)) }

        // Then
        assertTrue(outcome.isFailure)
        assertTrue(harness.database.peek("p1").isEmpty())
        assertTrue(harness.source.calls.isEmpty())
    }

    @Test
    fun `turning a source off or removing it cancels its unfinished requests only`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(1))
        runCurrent()
        harness.source.failNext("book-2", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Connection))
        harness.queue.request(bookRequest(2))
        runCurrent()
        harness.source.holdAll()
        harness.queue.request(bookRequest(3))
        harness.queue.request(bookRequest(4, sourceId = "source-2"))
        harness.queue.request(bookRequest(5))
        harness.queue.request(bookRequest(6, sourceId = "source-2"))
        runCurrent()
        assertEquals(
            mapOf(
                "book-1" to Done, "book-2" to Failed(AcquisitionFailureReason.Connection), "book-3" to Downloading,
                "book-4" to Downloading, "book-5" to Waiting, "book-6" to Waiting,
            ),
            harness.states(),
        )

        // When
        harness.queue.cancel("p1", "source-1")
        runCurrent()

        // Then: the finished book's row stays, and the freed slot goes to the other catalogue.
        assertEquals(mapOf("book-1" to Done, "book-4" to Downloading, "book-6" to Downloading), harness.states())
        assertEquals(listOf("book-3"), harness.source.cancelled)
        assertEquals(2, harness.files.files.size)
    }

    @Test
    fun `changed account details keep the request that was waiting for them`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.failNext("book-1", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.SignInNeeded))
        harness.queue.request(bookRequest(1))
        runCurrent()
        harness.source.holdAll()
        harness.queue.request(bookRequest(2))
        runCurrent()

        // When: saving account details invalidates the source's work, then reports the sign-in
        harness.queue.cancel("p1", "source-1")
        harness.source.releaseAll()
        harness.queue.signedIn("source-1")
        runCurrent()

        // Then
        assertEquals(mapOf("book-1" to Done), harness.states())
    }

    @Test
    fun `a source cancelled for another profile touches nothing here`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        harness.queue.request(bookRequest(1))
        runCurrent()

        // When
        harness.queue.cancel("p2", "source-1")
        runCurrent()

        // Then
        assertEquals(mapOf("book-1" to Downloading), harness.states())
        assertTrue(harness.source.cancelled.isEmpty())
    }

    @Test
    fun `every download carries the profile it was requested in`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()
        harness.switchProfile("p2")
        harness.queue.request(bookRequest(2))
        runCurrent()

        // Then
        assertEquals(listOf("p1", "p2"), harness.source.calls.map { it.profileId })
        assertEquals(listOf("p1", "p2"), harness.adder.calls.map { it.profileId })
        assertEquals(listOf("/staging/p1/generated-1.epub.part", "/staging/p2/generated-2.epub.part"), harness.files.created)
        assertEquals(mapOf("book-1" to Done), harness.states("p1"))
        assertEquals(mapOf("book-2" to Done), harness.states("p2"))
        assertEquals(Waiting.key, "waiting")
    }
}
