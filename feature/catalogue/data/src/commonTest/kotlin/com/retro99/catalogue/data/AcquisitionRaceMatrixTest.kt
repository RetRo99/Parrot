package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.AcquisitionState.Adding
import com.retro99.catalogue.domain.AcquisitionState.Checking
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Downloading
import com.retro99.catalogue.domain.AcquisitionState.Interrupted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Five things that can happen to a download from outside, each landing in each of the three
 * running stages. Fifteen combinations, one set of rules:
 *
 * - catalogue removed, turned off, or given other account details: the request is gone, its
 *   file is gone, no book reaches the library, and the freed slot is used;
 * - profile switched or deleted: the request is stored as interrupted in its own profile,
 *   its file is gone, nothing is written into the profile that is now open, and nothing
 *   finishes behind the user's back when the held step lets go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionRaceMatrixTest {
    private enum class Stage(val state: AcquisitionState) { Download(Downloading), Check(Checking), Add(Adding) }

    private enum class SourceEvent { Removed, TurnedOff, AccountChanged }

    /** Holds book-1 in [stage] and returns the way to let the held step go. */
    private suspend fun TestScope.holdIn(harness: QueueHarness, stage: Stage): () -> Unit {
        val gate = CompletableDeferred<Unit>()
        when (stage) {
            Stage.Download -> harness.source.hold("book-1")
            Stage.Check -> harness.checker.gate = gate
            Stage.Add -> harness.adder.gate = gate
        }
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(stage.state, harness.state("book-1"), "held in $stage")
        return {
            gate.complete(Unit)
            harness.checker.gate = null
            harness.adder.gate = null
            harness.source.release("book-1")
        }
    }

    private suspend fun TestScope.sourceEventLands(event: SourceEvent, stage: Stage) {
        val label = "$event during $stage"
        val harness = QueueHarness(backgroundScope)
        // Another catalogue's download waits behind the two slots: book-1 and a held book-2.
        harness.source.hold("book-2")
        harness.queue.request(bookRequest(2, sourceId = "source-2"))
        val release = holdIn(harness, stage)
        harness.source.hold("book-3")
        harness.queue.request(bookRequest(3, sourceId = "source-2"))
        runCurrent()
        assertEquals(AcquisitionState.Waiting, harness.state("book-3"), label)

        // When
        when (event) {
            SourceEvent.Removed -> {
                harness.sourceAddresses.remove("source-1")
                harness.queue.forget("p1", "source-1")
            }
            SourceEvent.TurnedOff, SourceEvent.AccountChanged -> harness.queue.cancel("p1", "source-1")
        }
        runCurrent()

        // Then: the request is gone at once, and its slot went to the other catalogue
        assertNull(harness.row("book-1"), label)
        assertEquals(mapOf("book-2" to Downloading, "book-3" to Downloading), harness.states(), label)

        // And when the step that was held lets go, nothing of it arrives
        release()
        runCurrent()
        assertNull(harness.row("book-1"), label)
        assertTrue(harness.world.libraryBooks["p1"].orEmpty().isEmpty(), "$label: no book reached the library")
        assertTrue(harness.sources.peek("p1").isEmpty(), "$label: no provenance")
        assertEquals(2, harness.files.files.size, "$label: only the two running downloads have files")
        assertTrue(harness.world.lockViolations.isEmpty(), "$label: ${harness.world.lockViolations}")

        // And the queue still works: the others finish, and the same book can be asked for again
        harness.source.releaseAll()
        runCurrent()
        assertEquals(mapOf("book-2" to Done, "book-3" to Done), harness.states(), label)
        harness.sourceAddresses["source-1"] = "https://books.example/opds/"
        harness.queue.request(bookRequest(1))
        runCurrent()
        assertEquals(Done, harness.state("book-1"), label)
        assertEquals(1, harness.sources.peek("p1").count { it.publicationKey == "book-1" }, label)
        assertTrue(harness.files.files.isEmpty(), "$label: nothing left in staging")
    }

    private suspend fun TestScope.profileEventLands(deleted: Boolean, stage: Stage) {
        val label = "${if (deleted) "profile deleted" else "profile switched"} during $stage"
        val harness = QueueHarness(backgroundScope)
        val release = holdIn(harness, stage)
        val requestId = harness.row("book-1")!!.requestId

        // When: the registry closes p1's work and opens p2
        harness.switchProfile("p2")
        if (deleted) harness.files.deleteFoldersExcept(setOf("p2"))
        runCurrent()

        // Then: stored as interrupted where it was asked for, with no file
        assertEquals(Interrupted, harness.state("book-1", "p1"), label)
        assertNull(harness.row("book-1", "p1")!!.stagingPath, label)
        assertNull(harness.row("book-1", "p1")!!.libraryBookId, label)
        assertTrue(harness.files.files.isEmpty(), "$label: ${harness.files.files.keys}")
        assertTrue(harness.database.peek("p2").isEmpty(), label)

        // And when the held step lets go, nothing arrives anywhere
        release()
        runCurrent()
        assertEquals(Interrupted, harness.state("book-1", "p1"), label)
        assertTrue(harness.database.peek("p2").isEmpty(), "$label: nothing written into the open profile")
        assertTrue(harness.world.libraryBooks.values.all { it.isEmpty() }, "$label: no book in any library")
        assertTrue(harness.sources.peek("p1").isEmpty() && harness.sources.peek("p2").isEmpty(), label)
        assertTrue(harness.files.files.isEmpty(), label)
        assertTrue(harness.world.lockViolations.isEmpty(), "$label: ${harness.world.lockViolations}")

        // And the open profile's own queue works, with its own slots
        harness.queue.request(bookRequest(1))
        harness.queue.request(bookRequest(7))
        runCurrent()
        assertEquals(mapOf("book-1" to Done, "book-7" to Done), harness.states("p2"), label)
        assertTrue(harness.source.calls.takeLast(2).all { it.profileId == "p2" }, label)

        if (!deleted) {
            // Coming back starts nothing; "Start again" finishes the book once
            val callsBefore = harness.source.calls.size
            harness.switchProfile("p1")
            harness.queue.restoreAfterRestart()
            runCurrent()
            assertEquals(Interrupted, harness.state("book-1", "p1"), label)
            assertEquals(callsBefore, harness.source.calls.size, "$label: nothing started by itself")
            assertTrue(harness.queue.startAgain(requestId), label)
            runCurrent()
            assertEquals(Done, harness.state("book-1", "p1"), label)
            assertEquals(1, harness.sources.peek("p1").size, label)
            assertEquals(listOf("book-1", "book-7"), harness.database.peek("p2").map { it.publicationKey }, "$label: p2 untouched")
        }
    }

    @Test fun `the catalogue is removed while a file is downloading - being checked - being added`() = runTest {
        Stage.entries.forEach { stage -> sourceEventLands(SourceEvent.Removed, stage) }
    }

    @Test fun `the catalogue is turned off while a file is downloading - being checked - being added`() = runTest {
        Stage.entries.forEach { stage -> sourceEventLands(SourceEvent.TurnedOff, stage) }
    }

    @Test fun `the account details change while a file is downloading - being checked - being added`() = runTest {
        Stage.entries.forEach { stage -> sourceEventLands(SourceEvent.AccountChanged, stage) }
    }

    @Test fun `the profile is switched while a file is downloading - being checked - being added`() = runTest {
        Stage.entries.forEach { stage -> profileEventLands(deleted = false, stage) }
    }

    @Test fun `the profile is deleted while a file is downloading - being checked - being added`() = runTest {
        Stage.entries.forEach { stage -> profileEventLands(deleted = true, stage) }
    }

    @Test fun `every event at once on a busy queue leaves each profile with only its own rows`() = runTest {
        // Given: p1 with one request in each running stage is not possible with two slots, so
        // two running and two waiting, across two catalogues.
        val harness = QueueHarness(backgroundScope)
        harness.source.holdAll()
        harness.queue.request(bookRequest(1))
        harness.queue.request(bookRequest(2, sourceId = "source-2"))
        harness.queue.request(bookRequest(3))
        harness.queue.request(bookRequest(4, sourceId = "source-2"))
        runCurrent()

        // When: a catalogue is turned off, the other removed, and the profile switched, with no pause between
        harness.queue.cancel("p1", "source-1")
        harness.queue.forget("p1", "source-2")
        harness.switchProfile("p2")
        harness.source.releaseAll()
        runCurrent()

        // Then
        assertTrue(harness.database.peek("p1").isEmpty())
        assertTrue(harness.database.peek("p2").isEmpty())
        assertTrue(harness.files.files.isEmpty())
        assertTrue(harness.world.libraryBooks.values.all { it.isEmpty() })
        assertTrue(harness.world.lockViolations.isEmpty())
    }
}
