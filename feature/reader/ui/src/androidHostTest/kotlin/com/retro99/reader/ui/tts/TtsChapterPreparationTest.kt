package com.retro99.reader.ui.tts

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TtsChapterPreparationTest {
    private val id = PreparedChapterId("book", "server", "chapter.xhtml")
    private val other = id.copy(chapterHref = "other.xhtml")
    private val settings = PreparedVoiceSettings("system", "v1", 1f, 1f)
    private val texts = listOf("One.", "Two.", "Three.")
    private val store = FakeStore()
    private val source = FakeSource(store)
    private val events = mutableListOf<TtsChapterPreparationAnalyticsEvent>()
    private var clock = 10_000L
    private var usable = 1_000L * 1_024 * 1_024

    private fun input(chapter: PreparedChapterId = id, texts: List<String> = this.texts) =
        TtsChapterPreparationInput(chapter, settings, TtsPreparationVoiceKind.SYSTEM, texts)

    @Test fun `every sentence is prepared once and completion marks the manifest and enforces the limit`() = runTest {
        val core = core(this)
        assertEquals(TtsChapterPreparationRequest.STARTED, core.start(input()))
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Completed(id.chapterHref), core.state.value)
        assertEquals(texts, source.prepared)
        assertEquals(listOf(id), store.completed)
        assertEquals(listOf(id), store.enforced)
        assertEquals(texts.map { source.key(it, settings) }, store.begun[id])
    }

    @Test fun `already prepared sentences are skipped so a second press resumes the chapter`() = runTest {
        store.prepared += source.key("One.", settings)
        store.prepared += source.key("Two.", settings)
        val core = core(this)
        core.start(input())
        advanceUntilIdle()
        assertEquals(listOf("Three."), source.prepared)
        assertEquals(TtsChapterPreparationState.Completed(id.chapterHref), core.state.value)
        assertEquals(listOf(id), store.completed)
    }

    @Test fun `a second request while one runs is refused and never queued`() = runTest {
        source.block = CompletableDeferred()
        val core = core(this)
        core.start(input())
        runCurrent()
        assertEquals(TtsChapterPreparationRequest.ALREADY_PREPARING, core.start(input(other, listOf("Elsewhere."))))
        assertEquals(TtsChapterPreparationState.Running(id.chapterHref, 0, 3), core.state.value)
        source.block?.complete(Unit)
        advanceUntilIdle()
        assertEquals(texts, source.prepared)
        assertFalse(store.begun.containsKey(other))
        assertEquals(1, events.count { it is TtsChapterPreparationAnalyticsEvent.Started })
    }

    @Test fun `progress counts each prepared sentence for the chapter on screen`() = runTest {
        val core = core(this)
        val seen = mutableListOf<TtsChapterPreparationState>()
        source.onPrepare = { seen += core.state.value }
        core.start(input())
        advanceUntilIdle()
        assertEquals(
            listOf<TtsChapterPreparationState>(
                TtsChapterPreparationState.Running(id.chapterHref, 0, 3),
                TtsChapterPreparationState.Running(id.chapterHref, 1, 3),
                TtsChapterPreparationState.Running(id.chapterHref, 2, 3),
            ),
            seen,
        )
    }

    @Test fun `a failing sentence is retried once and then the job fails and keeps what it has`() = runTest {
        source.failing += "Two."
        val core = core(this)
        core.start(input())
        advanceUntilIdle()
        assertEquals(
            TtsChapterPreparationState.Failed(id.chapterHref, TtsChapterPreparationFailure.SENTENCE_FAILED),
            core.state.value,
        )
        assertEquals(listOf("One.", "Two.", "Two."), source.attempted)
        assertEquals(emptyList(), store.completed)
        assertTrue(source.key("One.", settings) in store.prepared)
    }

    @Test fun `a sentence that succeeds on its retry does not stop the chapter`() = runTest {
        source.failOnce += "Two."
        val core = core(this)
        core.start(input())
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Completed(id.chapterHref), core.state.value)
        assertEquals(listOf("One.", "Two.", "Two.", "Three."), source.attempted)
    }

    @Test fun `cancel stops after the sentence in flight and leaves a valid partial chapter`() = runTest {
        source.block = CompletableDeferred()
        val core = core(this)
        core.start(input())
        runCurrent()
        core.cancel()
        source.block?.complete(Unit)
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Cancelled(id.chapterHref), core.state.value)
        assertEquals(listOf("One."), source.prepared)
        assertEquals(emptyList(), store.completed)
        assertTrue(source.key("One.", settings) in store.prepared)
    }

    @Test fun `not enough free disk space fails before anything is written`() = runTest {
        usable = 1_024L
        val core = core(this)
        assertEquals(TtsChapterPreparationRequest.NOT_ENOUGH_SPACE, core.start(input()))
        advanceUntilIdle()
        assertEquals(
            TtsChapterPreparationState.Failed(id.chapterHref, TtsChapterPreparationFailure.NOT_ENOUGH_SPACE),
            core.state.value,
        )
        assertTrue(store.begun.isEmpty())
        assertEquals(emptyList(), source.attempted)
        assertEquals(emptyList(), events)
    }

    @Test fun `a chapter with no sentences is not started`() = runTest {
        val core = core(this)
        assertEquals(TtsChapterPreparationRequest.UNAVAILABLE, core.start(input(texts = emptyList())))
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Idle, core.state.value)
        assertTrue(store.begun.isEmpty())
    }

    @Test fun `one start and one end event carry outcome sentence count duration and voice kind`() = runTest {
        val core = core(this)
        source.onPrepare = { clock += 1_000 }
        core.start(input())
        advanceUntilIdle()
        assertEquals(
            listOf(
                TtsChapterPreparationAnalyticsEvent.Started(TtsPreparationVoiceKind.SYSTEM, 3),
                TtsChapterPreparationAnalyticsEvent.Ended(TtsPreparationVoiceKind.SYSTEM, 3, "completed", 3_000),
            ),
            events,
        )
    }

    @Test fun `a cancelled and a failed run each end with their own outcome`() = runTest {
        val cancelled = core(this)
        source.block = CompletableDeferred()
        cancelled.start(input())
        runCurrent(); cancelled.cancel(); source.block?.complete(Unit); advanceUntilIdle()
        assertEquals("cancelled", assertIs<TtsChapterPreparationAnalyticsEvent.Ended>(events.last()).outcome)
        events.clear(); source.reset(); store.reset()
        source.failing += "One."
        val failed = core(this)
        failed.start(input())
        advanceUntilIdle()
        assertEquals("failed", assertIs<TtsChapterPreparationAnalyticsEvent.Ended>(events.last()).outcome)
    }

    @Test fun `a finished run releases the job for the next chapter`() = runTest {
        val core = core(this)
        core.start(input())
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationRequest.STARTED, core.start(input(other, listOf("Elsewhere."))))
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Completed(other.chapterHref), core.state.value)
    }

    private fun core(scope: kotlinx.coroutines.CoroutineScope) = TtsChapterPreparationCore(
        sentences = source,
        chapters = store,
        scope = scope,
        analytics = { event -> events += event },
        usableBytes = { usable },
        now = { clock },
    )

    private class FakeStore : TtsPreparationChapterStore {
        val begun = mutableMapOf<PreparedChapterId, List<String>>()
        val prepared = mutableSetOf<String>()
        val completed = mutableListOf<PreparedChapterId>()
        val enforced = mutableListOf<PreparedChapterId>()

        override fun begin(id: PreparedChapterId, settings: PreparedVoiceSettings, keys: List<String>) {
            begun[id] = keys
        }

        override fun isPrepared(id: PreparedChapterId, key: String) = key in prepared
        override fun markComplete(id: PreparedChapterId) { completed += id }
        override fun enforceLimit(active: PreparedChapterId) { enforced += active }
        fun reset() { begun.clear(); prepared.clear(); completed.clear(); enforced.clear() }
    }

    private class FakeSource(private val store: FakeStore) : TtsPreparationSentenceSource {
        val attempted = mutableListOf<String>()
        val prepared = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        val failOnce = mutableSetOf<String>()
        var block: CompletableDeferred<Unit>? = null
        var onPrepare: () -> Unit = {}

        override fun key(text: String, settings: PreparedVoiceSettings) = "key-$text-${settings.voiceId}"

        override suspend fun prepare(
            id: PreparedChapterId,
            text: String,
            settings: PreparedVoiceSettings,
        ): PreparedAudioEncoding {
            onPrepare()
            attempted += text
            block?.await()
            if (text in failing) return PreparedAudioEncoding.Failure
            if (failOnce.remove(text)) return PreparedAudioEncoding.Failure
            prepared += text
            store.prepared += key(text, settings)
            return PreparedAudioEncoding.Success(File("$text.m4a"), 1_000)
        }

        fun reset() {
            attempted.clear(); prepared.clear(); failing.clear(); failOnce.clear()
            block = null; onPrepare = {}
        }
    }
}
