package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState.Adding
import com.retro99.catalogue.domain.AcquisitionState.Checking
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Downloading
import com.retro99.catalogue.domain.AcquisitionState.Failed
import com.retro99.catalogue.domain.AcquisitionState.Interrupted
import com.retro99.catalogue.domain.AcquisitionState.Waiting
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.epub.api.EpubFileCheck
import com.retro99.epub.api.EpubFileProblem
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcquisitionQueuePipelineTest {

    @Test
    fun `a download ends checked hashed and staged ready to add`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = null

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        val row = harness.row("book-1")!!
        val stagedPath = assertNotNull(row.stagingPath)
        assertEquals(Adding, harness.state("book-1"))
        assertTrue(stagedPath.endsWith(".epub"))
        assertEquals(setOf(stagedPath), harness.files.files.keys, "the checked file is in staging and the part file is gone")
        assertEquals("sha-3000-3000", row.localHash)
        assertEquals(3000L, row.bytesSoFar)
        assertEquals(3000L, row.expectedSizeBytes)
        assertNull(row.libraryBookId)
        assertNull(row.completedAt)
        assertEquals(1, row.attempts)

        val listed = harness.queue.observeAcquisitions().first().single()
        assertEquals(stagedPath, listed.stagedFilePath)
        assertEquals(row.localHash, listed.localHash)

        val handedOver = harness.adder.calls.single()
        assertEquals("p1", handedOver.profileId)
        assertEquals(stagedPath, handedOver.book.path)
        assertEquals("sha-3000-3000", handedOver.book.contentHash)
        assertEquals(3000L, handedOver.book.sizeBytes)
        assertEquals("book-1", handedOver.book.publicationKey)
    }

    @Test
    fun `the file is checked by its bytes before anything is handed on`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.checker.gate = CompletableDeferred()

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Checking, harness.state("book-1"))
        assertTrue(harness.checker.checked.single().endsWith(".epub.part"))
        assertTrue(harness.adder.calls.isEmpty())

        // When
        harness.checker.gate!!.complete(Unit)
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
    }

    @Test
    fun `an added book finishes the request and forgets the listing address`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.world.time = 5_000

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        val row = harness.row("book-1")!!
        assertEquals(Done, harness.state("book-1"))
        assertEquals("lib-book-1", row.libraryBookId)
        assertEquals(5_000L, row.completedAt)
        assertNull(row.stagingPath)
        assertNull(row.detailUrl)
        assertTrue(harness.files.files.isEmpty())
    }

    @Test
    fun `the staging name is generated and nothing from the catalogue reaches the path`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = null
        val hostile = bookRequest(1).copy(title = "../../etc/passwd", author = "..\\evil", catalogueName = "/root")

        // When
        harness.queue.request(hostile)
        runCurrent()

        // Then
        val part = harness.files.created.single()
        assertEquals("/staging/p1/generated-1.epub.part", part)
        assertEquals("/staging/p1/generated-1.epub", harness.row("book-1")!!.stagingPath)
    }

    @Test
    fun `the link is resolved again on every attempt and the file starts from zero`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = null
        harness.source.failNext("book-1", CatalogueDownloadOutcome.Complete(bytes = 3000, declaredLength = 9000))
        val request = harness.queue.requestQueued(bookRequest(1))
        runCurrent()
        assertEquals(Failed(AcquisitionFailureReason.Connection), harness.state("book-1"))
        assertTrue(harness.files.files.isEmpty(), "the partial file is deleted")

        // When
        assertTrue(harness.queue.retry(request.requestId))
        runCurrent()

        // Then
        assertEquals(Adding, harness.state("book-1"))
        assertEquals(2, harness.source.calls.size)
        harness.source.calls.forEach { call ->
            assertEquals("https://books.example/opds/new", call.locator.documentUrl)
            assertEquals("book-1", call.locator.publicationKey)
            assertEquals("application/epub+zip#1", call.locator.representationKey)
            assertEquals("source-1", call.sourceId)
            assertEquals("p1", call.profileId)
        }
        assertEquals(2, harness.files.created.toSet().size, "each attempt writes a new file")
        assertEquals(3000L, harness.files.files.values.single().size)
        assertEquals(2, harness.row("book-1")!!.attempts)
    }

    @Test
    fun `transport results map to the failure reasons`() = runTest {
        val cases = listOf(
            CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.SignInNeeded) to AcquisitionFailureReason.SignIn,
            CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused) to AcquisitionFailureReason.Refused,
            CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.TooLarge) to AcquisitionFailureReason.TooLarge,
            CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Connection) to AcquisitionFailureReason.Connection,
            // A declared length that does not match the bytes received.
            CatalogueDownloadOutcome.Complete(bytes = 3000, declaredLength = 4000) to AcquisitionFailureReason.Connection,
        )
        cases.forEachIndexed { index, (outcome, expected) ->
            // Given
            val harness = QueueHarness(backgroundScope)
            harness.source.failNext("book-$index", outcome)

            // When
            harness.queue.request(bookRequest(index))
            runCurrent()

            // Then
            assertEquals(Failed(expected), harness.state("book-$index"), "$outcome")
            assertNull(harness.row("book-$index")!!.stagingPath)
            assertTrue(harness.files.files.isEmpty(), "nothing is left in staging after $outcome")
            assertTrue(harness.checker.checked.isEmpty())
        }
    }

    @Test
    fun `a full disk during the download is a storage failure`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.files.failWritesAt = 1500

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertTrue(harness.files.files.isEmpty())
        assertEquals(3000L, harness.row("book-1")!!.expectedSizeBytes, "the size needed is kept for the message")
    }

    @Test
    fun `too little free space stops the download before the network is used`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.files.freeSpace = AcquisitionWorker.MIN_FREE_BYTES - 1

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertTrue(harness.source.calls.isEmpty())
    }

    @Test
    fun `a declared size that does not fit is a storage failure before any byte is written`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.files.freeSpace = 100L * 1024 * 1024
        harness.source.declared["book-1"] = 200L * 1024 * 1024

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertEquals(200L * 1024 * 1024, harness.row("book-1")!!.expectedSizeBytes)
        assertEquals(0L, harness.row("book-1")!!.bytesSoFar)
    }

    @Test
    fun `space running out while downloading is noticed during the download`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        val megabyte = 1024 * 1024
        harness.source.undeclared += "book-1"
        harness.source.chunks["book-1"] = List(12) { megabyte }
        harness.files.freeSpaceAfterWrites = 4L * megabyte to 1L * megabyte

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertEquals(4L * megabyte, harness.row("book-1")!!.bytesSoFar)
        assertTrue(harness.files.files.isEmpty())
    }

    @Test
    fun `unknown free space does not block a download`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.files.freeSpace = null

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
    }

    @Test
    fun `a staging file that cannot be created is a storage failure`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.files.failOpen = true

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertTrue(harness.source.calls.isEmpty())
    }

    @Test
    fun `what the file check finds decides the failure reason`() = runTest {
        val cases = listOf(
            EpubFileCheck.Protected to AcquisitionFailureReason.Protected,
            EpubFileCheck.NotAnEpub(EpubFileProblem.NotAZip) to AcquisitionFailureReason.Invalid,
            EpubFileCheck.NotAnEpub(EpubFileProblem.Empty) to AcquisitionFailureReason.Invalid,
            EpubFileCheck.NotAnEpub(EpubFileProblem.UnsafeEntryPath) to AcquisitionFailureReason.Invalid,
            EpubFileCheck.NotAnEpub(EpubFileProblem.TooLargeUncompressed) to AcquisitionFailureReason.Invalid,
            EpubFileCheck.NotAnEpub(EpubFileProblem.Unreadable) to AcquisitionFailureReason.Storage,
        )
        cases.forEachIndexed { index, (check, expected) ->
            // Given
            val harness = QueueHarness(backgroundScope)
            harness.checker.result = check

            // When
            harness.queue.request(bookRequest(index))
            runCurrent()

            // Then
            assertEquals(Failed(expected), harness.state("book-$index"), "$check")
            assertTrue(harness.files.files.isEmpty(), "the rejected file is deleted")
            assertTrue(harness.adder.calls.isEmpty(), "a rejected file is never handed to the library")
            assertNull(harness.row("book-$index")!!.localHash)
        }
    }

    @Test
    fun `a file that cannot be given its staged name is a storage failure`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.files.failRename = true

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Storage), harness.state("book-1"))
        assertTrue(harness.files.files.isEmpty())
    }

    @Test
    fun `a book the library refuses fails the request and clears staging`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = { CatalogueBookAddResult.Failed(AcquisitionFailureReason.Invalid) }

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Invalid), harness.state("book-1"))
        assertTrue(harness.files.files.isEmpty())
    }

    @Test
    fun `cancel removes the request and its file from every unfinished state`() = runTest {
        // Waiting and downloading
        run {
            val harness = QueueHarness(backgroundScope)
            harness.source.holdAll()
            val requests = (1..3).map { number -> harness.queue.requestQueued(bookRequest(number)) }
            runCurrent()
            assertEquals(Waiting, harness.state("book-3"))
            assertTrue(harness.queue.cancel(requests[2].requestId))
            assertTrue(harness.queue.cancel(requests[0].requestId))
            runCurrent()
            assertEquals(mapOf("book-2" to Downloading), harness.states())
            assertEquals(listOf("book-1"), harness.source.cancelled)
            assertEquals(1, harness.files.files.size, "only the running download keeps a part file")
        }
        // Checking
        run {
            val harness = QueueHarness(backgroundScope)
            harness.checker.gate = CompletableDeferred()
            val request = harness.queue.requestQueued(bookRequest(1))
            runCurrent()
            assertEquals(Checking, harness.state("book-1"))
            assertTrue(harness.queue.cancel(request.requestId))
            runCurrent()
            assertTrue(harness.states().isEmpty())
            assertTrue(harness.files.files.isEmpty())
        }
        // Adding, while the library is working and while it is staged
        listOf(true, false).forEach { adderBusy ->
            val harness = QueueHarness(backgroundScope)
            if (adderBusy) harness.adder.gate = CompletableDeferred() else harness.adder.result = null
            val request = harness.queue.requestQueued(bookRequest(1))
            runCurrent()
            assertEquals(Adding, harness.state("book-1"))
            assertTrue(harness.queue.cancel(request.requestId))
            runCurrent()
            assertTrue(harness.states().isEmpty())
            assertTrue(harness.files.files.isEmpty(), "the staged file goes with the request")
        }
        // Failed
        run {
            val harness = QueueHarness(backgroundScope)
            harness.source.failNext("book-1", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.SignInNeeded))
            val request = harness.queue.requestQueued(bookRequest(1))
            runCurrent()
            assertTrue(harness.queue.cancel(request.requestId))
            assertTrue(harness.states().isEmpty())
        }
        // Interrupted
        run {
            val harness = QueueHarness(backgroundScope)
            harness.source.holdAll()
            val request = harness.queue.requestQueued(bookRequest(1))
            runCurrent()
            val restarted = harness.restart()
            restarted.restoreAfterRestart()
            assertEquals(Interrupted, harness.state("book-1"))
            assertTrue(restarted.cancel(request.requestId))
            assertTrue(harness.states().isEmpty())
        }
    }

    @Test
    fun `a finished request cannot be cancelled and a cancelled slot goes to the next in line`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        val done = harness.queue.requestQueued(bookRequest(1))
        runCurrent()
        harness.source.holdAll()
        val running = (2..4).map { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()

        // When
        assertFalse(harness.queue.cancel(done.requestId))
        assertFalse(harness.queue.cancel("no-such-request"))
        assertTrue(harness.queue.cancel(running[0].requestId))
        runCurrent()

        // Then
        assertEquals(mapOf("book-1" to Done, "book-3" to Downloading, "book-4" to Downloading), harness.states())
    }

    @Test
    fun `after a restart every running state becomes interrupted and its file is removed`() = runTest {
        // Given: one request being added, one checking, one failed, one done, one waiting
        val harness = QueueHarness(backgroundScope)
        harness.queue.request(bookRequest(5))
        runCurrent()
        harness.adder.result = null
        // Other bytes than book 5, or the library would rightly say it already has this file.
        harness.source.chunks["book-3"] = listOf(500)
        harness.queue.request(bookRequest(3))
        runCurrent()
        harness.source.failNext("book-4", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.TooLarge))
        harness.queue.request(bookRequest(4))
        runCurrent()
        harness.checker.gate = CompletableDeferred()
        harness.queue.request(bookRequest(2))
        harness.queue.request(bookRequest(6))
        runCurrent()
        assertEquals(
            mapOf(
                "book-5" to Done, "book-3" to Adding, "book-4" to Failed(AcquisitionFailureReason.TooLarge),
                "book-2" to Checking, "book-6" to Waiting,
            ),
            harness.states(),
        )
        assertEquals(2, harness.files.files.size)
        val callsBefore = harness.source.calls.size

        // When
        harness.restart().restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(
            mapOf(
                "book-5" to Done, "book-3" to Interrupted, "book-4" to Failed(AcquisitionFailureReason.TooLarge),
                "book-2" to Interrupted, "book-6" to Waiting,
            ),
            harness.states(),
        )
        assertTrue(harness.files.files.isEmpty(), "interrupted downloads start again from zero")
        assertTrue(harness.database.peek("p1").none { it.stagingPath != null })
        assertNull(harness.row("book-3")!!.localHash)
        assertEquals(callsBefore, harness.source.calls.size)
    }

    @Test
    fun `after a restart a request that was downloading is interrupted and its part file is removed`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.hold("book-1")
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Downloading, harness.state("book-1"))
        assertEquals(1, harness.files.files.size)

        // When
        harness.restart().restoreAfterRestart()
        runCurrent()

        // Then
        assertEquals(Interrupted, harness.state("book-1"))
        assertTrue(harness.files.files.isEmpty())
        assertNull(harness.row("book-1")!!.stagingPath)
        assertEquals(1, harness.source.calls.size, "nothing starts by itself")
    }

    @Test
    fun `finished requests are listed for 24 hours and can be purged`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.world.time = 1_000
        harness.queue.request(bookRequest(1))
        runCurrent()
        harness.world.time = 1_000 + CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS / 2
        harness.queue.request(bookRequest(2))
        harness.source.failNext("book-3", CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused))
        harness.queue.request(bookRequest(3))
        runCurrent()

        // Then
        assertEquals(listOf("book-2", "book-1"), harness.queue.finishedInLast24Hours().map { it.publicationKey })

        // When
        harness.world.time = 1_000 + CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS + 1
        val recent = harness.queue.finishedInLast24Hours()
        harness.queue.purgeExpired()

        // Then
        assertEquals(listOf("book-2"), recent.map { it.publicationKey })
        assertEquals(listOf("book-2", "book-3"), harness.database.peek("p1").map { it.publicationKey })

        // When
        harness.queue.purgeFinished()

        // Then
        assertEquals(listOf("book-3"), harness.database.peek("p1").map { it.publicationKey })
        assertEquals(listOf("book-3"), harness.queue.observeAcquisitions().first().map { it.publicationKey })
    }

    @Test
    fun `progress is throttled and is not written to the database on every chunk`() = runTest {
        // Given: 400 chunks arriving 50 ms apart, the last one 19.95 seconds after the start
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = null
        harness.source.chunks["book-1"] = List(400) { 100 }
        harness.source.afterChunk = { _, _ -> harness.world.time += 50 }

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Adding, harness.state("book-1"))
        assertEquals(2_000L, CatalogueAcquisitionQueue.STORED_PROGRESS_INTERVAL_MILLIS)
        assertEquals(9, harness.database.progressWrites, "one write every two seconds")
        assertTrue(harness.database.writes < 20, "400 chunks cost ${harness.database.writes} writes")
    }

    @Test
    fun `a quick download writes no progress rows at all`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.chunks["book-1"] = List(1000) { 10 }

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
        assertEquals(0, harness.database.progressWrites)
    }

    @Test
    fun `an unknown length is reported as bytes so far with no total`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.adder.result = null
        harness.source.undeclared += "book-1"
        harness.source.chunks["book-1"] = listOf(700, 700, 700)
        val midway = CompletableDeferred<Unit>()
        val carryOn = CompletableDeferred<Unit>()
        harness.source.afterChunk = { _, index ->
            harness.world.time += 1_000
            if (index == 1) {
                midway.complete(Unit)
                carryOn.await()
            }
        }

        // When
        harness.queue.request(bookRequest(1))
        runCurrent()
        midway.await()
        val listed = harness.queue.observeAcquisitions().first().single()

        // Then
        assertEquals(Downloading, listed.state)
        assertNull(listed.expectedSizeBytes)
        assertEquals(1400L, listed.bytesSoFar)

        // When
        carryOn.complete(Unit)
        runCurrent()

        // Then: once every byte is in, the size is known.
        assertEquals(Adding, harness.state("book-1"))
        assertEquals(2100L, harness.row("book-1")!!.expectedSizeBytes)
    }

    @Test
    fun `a known length is reported with its total while downloading`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.hold("book-1")

        // When
        harness.queue.request(bookRequest(1).copy(expectedSizeBytes = 2_900))
        runCurrent()
        val listed = harness.queue.observeAcquisitions().first().single()

        // Then: the server's Content-Length replaces the size the catalogue listed.
        assertEquals(Downloading, listed.state)
        assertEquals(3000L, listed.expectedSizeBytes)
        assertEquals(0L, listed.bytesSoFar)
    }

    @Test
    fun `the stored snapshot is bounded`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        val huge = bookRequest(1).copy(
            title = "t".repeat(10_000),
            author = "a".repeat(10_000),
            catalogueName = "c".repeat(10_000),
            coverReference = "data:image/png;base64," + "A".repeat(100_000),
            detailIdentity = "i".repeat(10_000),
        )

        // When
        harness.queue.request(huge)

        // Then
        val row = harness.row("book-1")!!
        assertEquals(CatalogueAcquisitionLimits.MAX_TITLE_LENGTH, row.title.length)
        assertEquals(CatalogueAcquisitionLimits.MAX_AUTHOR_LENGTH, row.author!!.length)
        assertEquals(CatalogueAcquisitionLimits.MAX_CATALOGUE_NAME_LENGTH, row.catalogueName.length)
        assertNull(row.coverReference)
        assertNull(row.detailIdentity)
        assertFailsWith<IllegalArgumentException> {
            harness.queue.request(bookRequest(2).copy(listingUrl = "https://books.example/" + "x".repeat(5_000)))
        }
        assertFailsWith<IllegalArgumentException> { harness.queue.request(bookRequest(3).copy(publicationKey = " ")) }
        assertEquals(1, harness.database.peek("p1").size)
    }

    @Test
    fun `a session closed under a download leaves a failure that can be retried`() = runTest {
        // Given: the catalogue source cancels the transfer itself, as it does when it is disposed
        val harness = QueueHarness(backgroundScope)
        harness.source.afterChunk = { _, index -> if (index == 0) throw CancellationException("Catalogue session invalidated") }

        // When
        val request = harness.queue.requestQueued(bookRequest(1))
        runCurrent()

        // Then
        assertEquals(Failed(AcquisitionFailureReason.Connection), harness.state("book-1"))
        assertTrue(harness.files.files.isEmpty())

        // When
        harness.source.afterChunk = { _, _ -> }
        assertTrue(harness.queue.retry(request.requestId))
        runCurrent()

        // Then
        assertEquals(Done, harness.state("book-1"))
    }

    @Test
    fun `the database lock is never held across the network a file check or the library`() = runTest {
        // Given
        val harness = QueueHarness(backgroundScope)
        harness.source.chunks["book-1"] = List(50) { 100 }
        harness.source.afterChunk = { _, _ -> harness.world.time += 1_000 }

        // When
        (1..3).forEach { number -> harness.queue.requestQueued(bookRequest(number)) }
        runCurrent()

        // Then
        assertEquals(setOf(Done), harness.states().values.toSet())
        assertTrue(harness.database.progressWrites > 0)
        assertEquals(emptyList(), harness.world.lockViolations)
    }
}
