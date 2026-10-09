package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Downloading
import com.retro99.catalogue.domain.AcquisitionState.Failed
import com.retro99.catalogue.domain.AcquisitionState.Interrupted
import com.retro99.catalogue.domain.AcquisitionState.Waiting
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionQueueStressTest {

    @Test
    fun `twenty downloads asked for at once - two run - the order survives a restart - nothing is lost or doubled`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        val books = (1..20).map { number -> "book-$number" }

        // When: twenty at once, and every one of them asked for twice
        val queued = (1..20).flatMap { number ->
            listOf(async { harness.queue.requestQueued(bookRequest(number)) }, async { harness.queue.requestQueued(bookRequest(number)) })
        }.awaitAll()
        runCurrent()

        // Then: twenty rows in the order they were asked for, two running
        assertEquals(20, queued.map { it.requestId }.distinct().size)
        assertEquals(books, harness.database.peek("p1").map { it.publicationKey })
        assertEquals(books.take(2), harness.states().filterValues { it == Downloading }.keys.toList())
        assertEquals(books.drop(2), harness.states().filterValues { it == Waiting }.keys.toList())
        assertEquals(CatalogueAcquisitionLimits.MAX_RUNNING, harness.source.mostRunningAtOnce)

        // When: three finish, so the queue has moved on, and Parrot is closed
        listOf("book-1", "book-2", "book-3").forEach { book ->
            harness.source.release(book)
            runCurrent()
        }
        assertEquals(books.take(3), harness.states().filterValues { it == Done }.keys.toList())
        assertEquals(listOf("book-4", "book-5"), harness.states().filterValues { it == Downloading }.keys.toList())
        val startedBeforeRestart = harness.source.calls.size
        val positions = harness.database.peek("p1").map { it.publicationKey to it.queuePosition }
        val restarted = harness.restart()
        restarted.restoreAfterRestart()
        runCurrent()

        // Then: the same twenty in the same order; what was running is interrupted; nothing started
        assertEquals(positions, harness.database.peek("p1").map { it.publicationKey to it.queuePosition })
        assertEquals(books.take(3), harness.states().filterValues { it == Done }.keys.toList())
        assertEquals(listOf("book-4", "book-5"), harness.states().filterValues { it == Interrupted }.keys.toList())
        assertEquals(books.drop(5), harness.states().filterValues { it == Waiting }.keys.toList())
        assertEquals(startedBeforeRestart, harness.source.calls.size, "nothing starts by itself after a restart")

        // When: the user starts the queue and starts the two interrupted ones again
        harness.source.releaseAll()
        restarted.start()
        listOf("book-4", "book-5").forEach { book -> assertTrue(restarted.startAgain(harness.row(book)!!.requestId)) }
        runCurrent()

        // Then: all twenty are done, the waiting ones in their order with the restarted two behind them
        assertTrue(harness.states().values.all { it == Done }, harness.states().toString())
        assertEquals(20, harness.database.peek("p1").size)
        assertEquals(books.drop(5) + listOf("book-4", "book-5"), harness.source.calls.drop(startedBeforeRestart).map { it.locator.publicationKey })
        assertEquals(CatalogueAcquisitionLimits.MAX_RUNNING, harness.source.mostRunningAtOnce)

        // And each book is in the library once, with one record of where it came from
        assertEquals(books.map { "lib-$it" }.toSet(), harness.world.libraryBooks.getValue("p1"))
        assertEquals(20, harness.adder.calls.size)
        assertEquals(books.sorted(), harness.sources.peek("p1").map { it.publicationKey }.sorted())
        assertTrue(harness.files.files.isEmpty(), "nothing is left in staging")
        assertTrue(harness.world.lockViolations.isEmpty(), harness.world.lockViolations.toString())
    }

    @Test
    fun `twenty downloads that fail in every way leave twenty rows - no file - and both slots free`() = runTest {
        // Given: every failure a download can meet, in turn
        val harness = QueueHarness(backgroundScope)
        val failures = listOf(
            CatalogueDownloadFailure.Connection to AcquisitionFailureReason.Connection,
            CatalogueDownloadFailure.Refused to AcquisitionFailureReason.Refused,
            CatalogueDownloadFailure.TooLarge to AcquisitionFailureReason.TooLarge,
            CatalogueDownloadFailure.SignInNeeded to AcquisitionFailureReason.SignIn,
        )
        (1..20).forEach { number -> harness.source.failNext("book-$number", CatalogueDownloadOutcome.Failed(failures[number % failures.size].first)) }

        // When
        (1..20).map { number -> async { harness.queue.requestQueued(bookRequest(number)) } }.awaitAll()
        runCurrent()

        // Then
        assertEquals((1..20).associate { number -> "book-$number" to Failed(failures[number % failures.size].second) }, harness.states())
        assertTrue(harness.files.files.isEmpty())
        assertTrue(harness.world.libraryBooks["p1"].orEmpty().isEmpty())

        // And a new request runs at once: no slot was left taken
        harness.queue.request(bookRequest(21))
        runCurrent()
        assertEquals(Done, harness.state("book-21"))
    }

    @Test
    fun `the disk fills up while the book is moved into the library - the request fails as storage and a retry adds it once`() = runTest {
        // Given: the library has no room for the file
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = { CatalogueBookAddResult.Failed(AcquisitionFailureReason.Storage, neededBytes = 3_000) }

        // When
        val requestId = harness.queue.requestQueued(bookRequest(1)).requestId
        runCurrent()

        // Then: a storage failure that says how much was needed, and no file left behind
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        val failed = harness.row("book-1")!!
        assertEquals(3_000L, failed.neededBytes)
        assertNull(failed.stagingPath)
        assertNull(failed.libraryBookId)
        assertTrue(harness.files.files.isEmpty(), "the staged file is removed, not left for a later sweep")
        assertTrue(harness.sources.peek("p1").isEmpty())

        // When: there is room again and the user retries
        harness.adder.result = { book -> CatalogueBookAddResult.Added("lib-${book.publicationKey}") }
        assertTrue(harness.queue.retry(requestId))
        runCurrent()

        // Then: downloaded from zero, added once
        assertEquals(Done, harness.state("book-1"))
        assertEquals(2, harness.source.calls.size)
        assertEquals(setOf("lib-book-1"), harness.world.libraryBooks.getValue("p1"))
        assertEquals(1, harness.sources.peek("p1").size)
        assertNull(harness.row("book-1")!!.neededBytes)
    }

    @Test
    fun `the disk fills up at every point of a download and never leaves a file or a taken slot`() = runTest {
        // A 3000-byte file; the disk refuses the write that would pass each of these sizes.
        for (fullAt in listOf(0L, 1L, 999L, 1000L, 1001L, 2999L)) {
            val harness = QueueHarness(backgroundScope)
            harness.files.failWritesAt = fullAt

            harness.queue.request(bookRequest(1))
            runCurrent()

            assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"), "full at $fullAt")
            assertTrue(harness.files.files.isEmpty(), "full at $fullAt")
            assertTrue(harness.adder.calls.isEmpty(), "full at $fullAt")
            // Room again: the next request is not stuck behind the failed one.
            harness.files.failWritesAt = null
            harness.queue.request(bookRequest(2))
            runCurrent()
            assertEquals(Done, harness.state("book-2"), "full at $fullAt")
        }
    }
}
