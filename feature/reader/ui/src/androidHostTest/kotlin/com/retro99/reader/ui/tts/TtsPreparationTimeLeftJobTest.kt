package com.retro99.reader.ui.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The time left as the job's running state carries it, and as the notification says it. */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsPreparationTimeLeftJobTest {
    private val id = PreparedChapterId("book", "server", "chapter.xhtml")
    private val settings = PreparedVoiceSettings("voice-a", "v1", 1f, 1f)
    private val chapters = PreparationTestChapters()
    // Every sentence is ten characters and takes 3 s: 300 ms per character.
    private val source = PreparationTestSource(chapters, workMs = { 3_000L })
    private val texts = List(6) { index -> "Sentence $index".padEnd(10, '.').take(10) }
    private var clock = 0L

    private fun core(scope: CoroutineScope) = TtsChapterPreparationCore(
        sentences = source,
        chapters = chapters,
        scope = scope,
        analytics = {},
        usableBytes = { Long.MAX_VALUE },
        now = { clock },
    )

    private fun input() = TtsChapterPreparationInput(id, settings, TtsPreparationVoiceKind.NEURAL, texts)

    @Test fun `the time left appears after the third generated sentence and follows the work left`() = runTest {
        val core = core(this)
        val seen = mutableListOf<Long?>()
        source.onPrepare = {
            seen += assertIs<TtsChapterPreparationState.Running>(core.state.value).remainingMs
            clock += 10_000
        }
        core.start(input())
        advanceUntilIdle()
        // Seen before sentences 1 to 6: nothing until three are done, then 30, 20 and 10
        // characters left at 300 ms each.
        assertEquals(listOf(null, null, null, 9_000L, 6_000L, 3_000L), seen)
    }

    @Test fun `sentences that were already prepared are no part of the time left`() = runTest {
        chapters.prepared += source.key(texts[4], settings)
        chapters.prepared += source.key(texts[5], settings)
        val core = core(this)
        val seen = mutableListOf<Long?>()
        source.onPrepare = {
            seen += assertIs<TtsChapterPreparationState.Running>(core.state.value).remainingMs
            clock += 10_000
        }
        core.start(input())
        advanceUntilIdle()
        assertEquals(listOf(null, null, null, 3_000L), seen)
    }

    @Test fun `while preparation waits for its turn the time left does not count down`() = runTest {
        val core = core(this)
        val waiting = CompletableDeferred<Unit>()
        source.onPrepare = { text ->
            clock += 10_000
            if (text == texts[3]) waiting.await()
        }
        core.start(input())
        runCurrent()
        val before = assertIs<TtsChapterPreparationState.Running>(core.state.value)
        assertEquals(9_000L, before.remainingMs)
        // Live listening holds the synthesis turn for ten minutes; no work is being done.
        clock += 600_000
        runCurrent()
        assertEquals(before, core.state.value)
        waiting.complete(Unit)
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Completed(id.chapterHref), core.state.value)
    }

    @Test fun `a new time left is not published more often than every five seconds`() = runTest {
        val core = core(this)
        val seen = mutableListOf<Long?>()
        source.onPrepare = {
            seen += assertIs<TtsChapterPreparationState.Running>(core.state.value).remainingMs
            clock += 2_000
        }
        core.start(input())
        advanceUntilIdle()
        // First shown after sentence 3 (9 s left). Sentence 4 ends 2 s later: too soon.
        // Sentence 5 ends 4 s later: still too soon.
        assertEquals(listOf(null, null, null, 9_000L, 9_000L, 9_000L), seen)
    }

    @Test fun `the notification says the same count and time left and names nothing`() {
        val counting = chapterPreparationNotificationText(TtsChapterPreparationState.Running("c1.xhtml", 2, 369))
        assertEquals(TtsChapterPreparationNotificationLine.COUNT, counting.line)
        assertEquals(listOf<Any>(2, 369), counting.args)

        val left = chapterPreparationNotificationText(
            TtsChapterPreparationState.Running("c1.xhtml", 42, 369, remainingMs = 7 * 60_000L),
        )
        assertEquals(TtsChapterPreparationNotificationLine.TIME_LEFT, left.line)
        assertEquals(listOf<Any>(42, 369, 7), left.args)

        val short = chapterPreparationNotificationText(
            TtsChapterPreparationState.Running("c1.xhtml", 360, 369, remainingMs = 20_000),
        )
        assertEquals(TtsChapterPreparationNotificationLine.TIME_LEFT_SHORT, short.line)
        assertEquals(listOf<Any>(360, 369), short.args)

        val long = chapterPreparationNotificationText(
            TtsChapterPreparationState.Running("c1.xhtml", 5, 2_000, remainingMs = 88 * 60_000L),
        )
        assertEquals(TtsChapterPreparationNotificationLine.TIME_LEFT_HOURS, long.line)
        assertEquals(listOf<Any>(5, 2_000, 1, 30), long.args)
        assertEquals(listOf<Any>(0, 0), chapterPreparationNotificationText(null).args)
    }
}
