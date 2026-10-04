package com.retro99.reader.data.recap

import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapEligibility
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapTextSource
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecapSessionRecorderTest {

    private val clock = TestClock()
    private val database = FakeSessionRecapDatabase()
    private val settings = FakeRecapSettings(enabled = true)
    private var readyCount = 0

    private fun TestScope.recorder() = RecapSessionRecorderImpl(
        database = database,
        settings = settings,
        diagnostics = RecapDiagnostics(RecordingAnalytics()),
        onSessionReady = { readyCount++ },
        clock = clock,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private val page = "The reader turned the page and kept going through the story. ".repeat(4)

    private fun RecapSessionRecorderImpl.readPages(sessionId: String, pages: Int, from: Double = 0.10) {
        repeat(pages) { index ->
            appendReadText(
                sessionId,
                "$page Page $index.",
                RecapChapter(3, "Chapter 3"),
                RecapPosition("ch3.xhtml", index / 10.0, from + index * 0.01),
            )
        }
    }

    @Test
    fun capturingThenPendingWhenEligible() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition("ch3.xhtml", 0.0, 0.10), RecapChapter(3, "Chapter 3"), "sl")
        recorder.readPages("s1", pages = 3)
        testScheduler.advanceUntilIdle()
        assertEquals("CAPTURING", database["s1"]!!.status)
        assertTrue(database["s1"]!!.excerpt!!.contains("Page 2."))

        recorder.onSessionEnded("s1", RecapPosition("ch3.xhtml", 0.3, 0.13), "  Last line.  ", 60_000)
        testScheduler.advanceUntilIdle()

        val row = database["s1"]!!
        assertEquals("PENDING", row.status)
        // The first page is the start position; two forward moves follow.
        assertEquals(2, row.pageAdvances)
        assertEquals("Last line.", row.lastSentence)
        assertEquals("sl", row.language)
        assertEquals("Chapter 3", row.startChapterTitle)
        assertEquals(0.13, row.endTotalProgression)
        assertEquals(RecapEligibility.fingerprint(row.excerpt!!), row.excerptHash)
        assertEquals(1, readyCount)
    }

    @Test
    fun withdrawingConsentMidSessionKeepsNoText() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition("ch3.xhtml", 0.0, 0.10), null, "sl")
        recorder.readPages("s1", pages = 3)
        testScheduler.advanceUntilIdle()

        settings.enabled.value = false
        database.withdrawText(clock.nowMs)
        // Consent back on before the session ends: old text still can't go.
        settings.enabled.value = true
        recorder.readPages("s1", pages = 2)
        recorder.onSessionEnded("s1", null, "Last line.", 600_000)
        testScheduler.advanceUntilIdle()

        val row = database["s1"]!!
        assertEquals("SKIPPED_INELIGIBLE", row.status)
        assertEquals("CONSENT_WITHDRAWN", row.lastError)
        assertNull(row.excerpt)
        assertNull(row.lastSentence)
        assertEquals(0, readyCount)
    }

    @Test
    fun withdrawingConsentDropsQueuedText() = runTest {
        database.put(pendingRow("queued"))
        database.put(pendingRow("waiting", status = "FAILED_RETRYABLE", nextAttemptAt = clock.nowMs + 1))
        database.put(pendingRow("done", status = "SUCCEEDED", excerpt = null).copy(lastSentence = null))

        database.withdrawText(clock.nowMs)

        listOf("queued", "waiting").forEach { id ->
            val row = database[id]!!
            assertEquals("FAILED_PERMANENT", row.status)
            assertEquals("CONSENT_WITHDRAWN", row.lastError)
            assertNull(row.excerpt)
            assertNull(row.lastSentence)
        }
        assertEquals("SUCCEEDED", database["done"]!!.status)
    }

    @Test
    fun anAbandonedSessionIsDroppedWhenConsentIsOff() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition("ch3.xhtml", 0.0, 0.10), null, "sl")
        recorder.readPages("s1", pages = 3)
        testScheduler.advanceUntilIdle()
        settings.enabled.value = false

        // A new process finds the row still CAPTURING.
        val next = recorder()
        next.recoverAbandoned()

        val row = database["s1"]!!
        assertEquals("SKIPPED_INELIGIBLE", row.status)
        assertNull(row.excerpt)
    }

    @Test
    fun shortSessionIsSkippedAndKeepsNoText() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition(totalProgression = 0.1), null)
        recorder.appendReadText("s1", "Too short.", null, RecapPosition(totalProgression = 0.1))
        recorder.onSessionEnded("s1", null, "Too short.", 10_000)
        testScheduler.advanceUntilIdle()

        val row = database["s1"]!!
        assertEquals("SKIPPED_INELIGIBLE", row.status)
        assertEquals("TOO_LITTLE_READING", row.lastError)
        assertNull(row.excerpt)
        assertNull(row.lastSentence)
        assertEquals(0, readyCount)
    }

    @Test
    fun ttsSentencesCountTowardsEligibility() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition(totalProgression = 0.1), null)
        repeat(25) { index ->
            recorder.appendReadText("s1", "Spoken sentence number $index is here.", null, null, RecapTextSource.TTS_SENTENCE)
        }
        recorder.onSessionEnded("s1", null, null, 30_000)
        testScheduler.advanceUntilIdle()

        assertEquals(25, database["s1"]!!.ttsSentences)
        assertEquals("PENDING", database["s1"]!!.status)
    }

    @Test
    fun repeatOfThePreviousSessionIsSkipped() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition(totalProgression = 0.1), null)
        recorder.readPages("s1", pages = 3)
        recorder.onSessionEnded("s1", null, null, 60_000)
        testScheduler.advanceUntilIdle()
        clock.nowMs += 1_000

        recorder.onSessionStarted("s2", "server", "book", RecapPosition(totalProgression = 0.1), null)
        recorder.readPages("s2", pages = 3)
        recorder.onSessionEnded("s2", null, null, 60_000)
        testScheduler.advanceUntilIdle()

        assertEquals("PENDING", database["s1"]!!.status)
        assertEquals("SKIPPED_INELIGIBLE", database["s2"]!!.status)
        assertEquals("DUPLICATE_OF_PREVIOUS", database["s2"]!!.lastError)
    }

    @Test
    fun nothingIsStoredWithoutConsent() = runTest {
        settings.enabled.value = false
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition(), null)
        recorder.readPages("s1", pages = 3)
        recorder.onSessionEnded("s1", null, null, 600_000)
        testScheduler.advanceUntilIdle()

        assertTrue(database.rows.value.isEmpty())
    }

    @Test
    fun aLongSessionKeepsTheOpeningAndEndWithinBudget() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition(totalProgression = 0.0), null)
        recorder.readPages("s1", pages = 300, from = 0.0)
        recorder.onSessionEnded("s1", null, null, 600_000)
        testScheduler.advanceUntilIdle()

        val row = database["s1"]!!
        assertEquals("PENDING", row.status)
        assertTrue(row.excerpt!!.length <= 8_000)
        assertTrue(row.excerpt!!.startsWith(page.trim()))
        assertTrue(row.excerpt!!.contains("Page 0."))
        assertTrue(row.excerpt!!.endsWith("Page 299."))
    }

    @Test
    fun boundedExcerptsAreSavedAfterEveryAppend() = runTest {
        val recorder = recorder()
        recorder.onSessionStarted("s1", "server", "book", RecapPosition(totalProgression = 0.0), null)
        // Reading more than the budget must not leave an old tail on disk.
        recorder.readPages("s1", pages = 100, from = 0.0)
        testScheduler.advanceUntilIdle()
        val saved = database["s1"]!!.excerpt!!
        assertTrue(saved.length <= 8_000, "length ${saved.length}")
        assertTrue(saved.endsWith("Page 99."))

        clock.nowMs += 30_000
        recorder.readPages("s1", pages = 1, from = 0.5)
        testScheduler.advanceUntilIdle()
        assertTrue(database["s1"]!!.excerpt!!.endsWith("Page 0."))
        assertTrue(database["s1"]!!.excerpt!!.length <= 8_000)
    }

    @Test
    fun abandonedSessionsFromADeadProcessAreFinished() = runTest {
        database.put(
            pendingRow("dead", status = "CAPTURING", excerpt = page.repeat(3)).copy(
                pageAdvances = 4,
                createdAt = 0,
                updatedAt = 400_000,
            ),
        )
        val recorder = recorder()
        recorder.onSessionStarted("live", "server", "book2", RecapPosition(), null)
        testScheduler.advanceUntilIdle()

        recorder.recoverAbandoned()

        assertEquals("PENDING", database["dead"]!!.status)
        assertEquals(400_000, database["dead"]!!.activeReadingMs)
        assertEquals("CAPTURING", database["live"]!!.status)
    }
}
