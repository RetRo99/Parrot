package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cancel answers at once, and the sentence in flight is abandoned rather than waited for. */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsPreparationCancelTest {
    private val id = PreparedChapterId("book", "server", "chapter.xhtml")
    private val other = id.copy(chapterHref = "other.xhtml")
    private val settings = PreparedVoiceSettings("voice-a", "v1", 1f, 1f)
    private val texts = listOf("One.", "Two.", "Three.")
    private val chapters = PreparationTestChapters()
    private val source = PreparationTestSource(chapters)
    private val events = mutableListOf<TtsChapterPreparationAnalyticsEvent>()
    private val hold = CompletableDeferred<Unit>()

    private val root = Files.createTempDirectory("prepared-cancel").toFile()
    private val cache = TtsAudioCacheStore(File(root, "cache").apply { mkdirs() })
    private val store = TtsPreparedStore(File(root, "prepared"))
    private val encoder = TtsPreparedAudioEncoder { _, out ->
        out.writeBytes(byteArrayOf(1, 2, 3))
        PreparedAudioEncoding.Success(out, 1_020)
    }
    private fun key(text: String) =
        cache.key(settings.voiceId.orEmpty(), settings.modelVersion, settings.rate, settings.pitch, text)

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun core(scope: CoroutineScope) = TtsChapterPreparationCore(
        sentences = source,
        chapters = chapters,
        scope = scope,
        analytics = { event -> events += event },
        usableBytes = { Long.MAX_VALUE },
    )

    private fun input(chapter: PreparedChapterId = id) =
        TtsChapterPreparationInput(chapter, settings, TtsPreparationVoiceKind.NEURAL, texts)

    private fun cancelling(done: Int) =
        TtsChapterPreparationState.Running(id.chapterHref, done, texts.size, isCancelling = true)

    @Test fun `cancel shows cancelling at once and cancelled when the sentence in flight has been abandoned`() = runTest {
        source.onPrepare = { text -> if (text == "Two.") hold.await() }
        val core = core(this)
        core.start(input())
        runCurrent()
        core.cancel()
        // Before the job has had any chance to run again.
        assertEquals(cancelling(done = 1), core.state.value)
        runCurrent()
        // The sentence in flight was never let go: it was abandoned, not waited for.
        assertEquals(false, hold.isCompleted)
        assertEquals(TtsChapterPreparationState.Cancelled(id.chapterHref), core.state.value)
        assertEquals(listOf("One."), source.prepared)
        assertEquals(listOf("One.", "Two."), source.attempted)
        assertEquals(emptyList(), chapters.completed)
    }

    @Test fun `a cancelled chapter is a partly prepared one that Continue resumes`() = runTest {
        source.onPrepare = { text -> if (text == "Two.") hold.await() }
        val core = core(this)
        core.start(input())
        runCurrent()
        core.cancel()
        runCurrent()
        assertEquals(setOf(source.key("One.", settings)), chapters.prepared)

        source.onPrepare = {}
        assertEquals(TtsChapterPreparationRequest.STARTED, core.start(input()))
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Completed(id.chapterHref), core.state.value)
        assertEquals(listOf("One.", "Two.", "Three."), source.prepared)
        assertEquals(listOf(id), chapters.completed)
    }

    @Test fun `pressing cancel twice does no harm`() = runTest {
        source.onPrepare = { text -> if (text == "Two.") hold.await() }
        val core = core(this)
        core.start(input())
        runCurrent()
        core.cancel()
        core.cancel()
        assertEquals(cancelling(done = 1), core.state.value)
        runCurrent()
        core.cancel()
        assertEquals(TtsChapterPreparationState.Cancelled(id.chapterHref), core.state.value)
        assertEquals(1, events.count { it is TtsChapterPreparationAnalyticsEvent.Ended })
        assertEquals("cancelled", events.filterIsInstance<TtsChapterPreparationAnalyticsEvent.Ended>().single().outcome)
    }

    @Test fun `cancel with nothing running changes nothing and does not stop the next chapter`() = runTest {
        val core = core(this)
        core.cancel()
        assertEquals(TtsChapterPreparationState.Idle, core.state.value)
        core.start(input())
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Completed(id.chapterHref), core.state.value)
    }

    @Test fun `a voice that cannot be interrupted keeps the row cancelling until its sentence returns`() = runTest {
        source.onPrepare = { text -> if (text == "Two.") withContext(NonCancellable) { hold.await() } }
        val core = core(this)
        core.start(input())
        runCurrent()
        core.cancel()
        runCurrent()
        try {
            assertEquals(cancelling(done = 1), core.state.value)
            assertEquals(TtsChapterPreparationRequest.ALREADY_PREPARING, core.start(input(other)))
        } finally {
            // Whatever the assertions say, the uninterruptible sentence must be let go.
            hold.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Cancelled(id.chapterHref), core.state.value)
        // It finished, so it is a success and is kept; nothing after it was started.
        assertEquals(listOf("One.", "Two."), source.prepared)
        assertEquals(listOf("One.", "Two."), source.attempted)
    }

    @Test fun `an interrupted sentence that reports a failure is a cancel and is not tried again`() = runTest {
        source.failing += "Two."
        source.onPrepare = { text -> if (text == "Two.") withContext(NonCancellable) { hold.await() } }
        val core = core(this)
        core.start(input())
        runCurrent()
        core.cancel()
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Cancelled(id.chapterHref), core.state.value)
        assertEquals(listOf("One.", "Two."), source.attempted)
    }

    @Test fun `an abandoned sentence leaves nothing in the prepared store or the sentence cache`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val synth = PreparationTestSynth { entered.complete(Unit); awaitCancellation() }
        val generator = TtsAudioGeneratorCore(synth, cache, store, encoder, StandardTestDispatcher(testScheduler))
        store.begin(id, settings, listOf(key("Sentence.")))
        val preparing = launch { generator.prepareSentenceMeasured(id, "Sentence.", "voice-a", 1f, 1f) }
        entered.await()
        preparing.cancelAndJoin()
        assertEquals(emptyList(), cache.fileFor(key("Sentence.")).parentFile!!.list()!!.toList())
        assertNull(store.lookup(key("Sentence.")))
        assertNull(cache.get(key("Sentence.")))
        assertEquals(PreparedChapterState.Partial(0, 1), store.state(id, settings))
    }

    @Test fun `a sentence whose voice finished after the cancel leaves nothing behind either`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val synth = PreparationTestSynth { entered.complete(Unit); withContext(NonCancellable) { hold.await() } }
        val generator = TtsAudioGeneratorCore(synth, cache, store, encoder, StandardTestDispatcher(testScheduler))
        store.begin(id, settings, listOf(key("Sentence.")))
        val preparing = launch { generator.prepareSentenceMeasured(id, "Sentence.", "voice-a", 1f, 1f) }
        entered.await()
        preparing.cancel()
        hold.complete(Unit)
        preparing.join()
        assertEquals(emptyList(), cache.fileFor(key("Sentence.")).parentFile!!.list()!!.toList())
        assertNull(store.lookup(key("Sentence.")))
        assertEquals(PreparedChapterState.Partial(0, 1), store.state(id, settings))
    }

    @Test fun `a live read aloud request waiting behind the cancelled sentence is served next`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val synth = PreparationTestSynth { text -> if (text == "first") { entered.complete(Unit); awaitCancellation() } }
        val generator = TtsAudioGeneratorCore(synth, cache, store, encoder, StandardTestDispatcher(testScheduler))
        store.begin(id, settings, listOf(key("first"), key("second")))
        val preparing = launch { generator.prepareSentenceMeasured(id, "first", "voice-a", 1f, 1f) }
        entered.await()
        launch { generator.prepareSentenceMeasured(id, "second", "voice-a", 1f, 1f) }
        var live: TtsSynthesisResult? = null
        launch { live = generator.synthesize("live", "voice-a", 1f, 1f) }
        runCurrent()
        assertEquals(listOf("first"), synth.order)
        preparing.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(listOf("first", "live", "second"), synth.order)
        assertEquals(TtsSynthesisStatus.SUCCESS, live?.status)
    }

    @Test fun `the notification says cancelling while the job is stopping`() {
        val text = chapterPreparationNotificationText(cancelling(done = 1).copy(remainingMs = 600_000))
        assertEquals(TtsChapterPreparationNotificationLine.CANCELLING, text.line)
        assertEquals(emptyList(), text.args)
    }
}
