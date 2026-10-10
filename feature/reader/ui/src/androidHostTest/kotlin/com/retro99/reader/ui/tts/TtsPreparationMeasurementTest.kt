package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What the preparation job measures, and where it keeps it. */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsPreparationMeasurementTest {
    private val root = Files.createTempDirectory("prepared-measure").toFile()
    private val cache = TtsAudioCacheStore(File(root, "cache").apply { mkdirs() })
    private val store = TtsPreparedStore(File(root, "prepared"))
    private val id = PreparedChapterId("book", null, "chapter")
    private val settings = PreparedVoiceSettings("voice-a", "v1", 1f, 1f)
    private var nanos = 0L
    private val synth = PreparationTestSynth(onCall = { nanos += 2_000 * NANOS_PER_MS })
    private val encoder = TtsPreparedAudioEncoder { _, out ->
        nanos += 500 * NANOS_PER_MS
        out.writeBytes(byteArrayOf(1, 2, 3))
        PreparedAudioEncoding.Success(out, 1_020)
    }
    private fun key(text: String) =
        cache.key(settings.voiceId.orEmpty(), settings.modelVersion, settings.rate, settings.pitch, text)

    @AfterTest fun cleanup() { root.deleteRecursively() }

    @Test fun `a generated sentence reports its synthesis and encoding time`() = runTest {
        val generator = TtsAudioGeneratorCore(synth, cache, store, encoder, StandardTestDispatcher(testScheduler)) { nanos }
        store.begin(id, settings, listOf(key("Sentence.")))
        val work = generator.prepareSentenceMeasured(id, "Sentence.", "voice-a", 1f, 1f)
        assertEquals(2_500L, work.workMs)
    }

    @Test fun `a sentence that was already prepared or came from a cached WAV is no measurement`() = runTest {
        val generator = TtsAudioGeneratorCore(synth, cache, store, encoder, StandardTestDispatcher(testScheduler)) { nanos }
        store.begin(id, settings, listOf(key("Sentence."), key("Cached.")))
        generator.prepareSentenceMeasured(id, "Sentence.", "voice-a", 1f, 1f)
        assertNull(generator.prepareSentenceMeasured(id, "Sentence.", "voice-a", 1f, 1f).workMs)
        cache.fileFor(key("Cached.")).writeBytes(wavBytes(88_200))
        assertNull(generator.prepareSentenceMeasured(id, "Cached.", "voice-a", 1f, 1f).workMs)
    }

    @Test fun `waiting for live listening to finish its turn is not counted as work`() = runTest {
        val generator = TtsAudioGeneratorCore(synth, cache, store, encoder, StandardTestDispatcher(testScheduler)) { nanos }
        val liveEntered = CompletableDeferred<Unit>()
        val liveRelease = CompletableDeferred<Unit>()
        synth.onCall = { text ->
            if (text == "live") { liveEntered.complete(Unit); liveRelease.await() } else nanos += 2_000 * NANOS_PER_MS
        }
        store.begin(id, settings, listOf(key("Sentence.")))
        launch { generator.synthesize("live", "voice-a", 1f, 1f) }
        liveEntered.await()
        var work: PreparedSentenceWork? = null
        launch { work = generator.prepareSentenceMeasured(id, "Sentence.", "voice-a", 1f, 1f) }
        runCurrent()
        nanos += 60_000 * NANOS_PER_MS
        liveRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(2_500L, assertNotNull(work).workMs)
    }

    @Test fun `the job records each sentence it generates and none it skips`() = runTest {
        val recorded = mutableListOf<Pair<String?, TtsPreparationSample>>()
        val chapters = PreparationTestChapters()
        val source = PreparationTestSource(chapters, workMs = { text -> if (text == "Copied.") null else text.length * 30L })
        chapters.prepared += source.key("Skipped.", settings)
        val core = TtsChapterPreparationCore(
            sentences = source,
            chapters = chapters,
            scope = this,
            analytics = {},
            usableBytes = { Long.MAX_VALUE },
            speed = { voiceId, sample -> recorded += voiceId to sample },
        )
        core.start(
            TtsChapterPreparationInput(
                id, settings, TtsPreparationVoiceKind.NEURAL,
                listOf("Skipped.", "A first sentence.", "Copied.", "Short."),
            ),
        )
        advanceUntilIdle()
        assertEquals(TtsChapterPreparationState.Completed(id.chapterHref), core.state.value)
        assertEquals(
            listOf<Pair<String?, TtsPreparationSample>>(
                "voice-a" to TtsPreparationSample(workMs = 17 * 30L, characters = 17),
                "voice-a" to TtsPreparationSample(workMs = 6 * 30L, characters = 6),
            ),
            recorded,
        )
    }

    @Test fun `the record is kept in its own file and read back by a new instance`() {
        val file = File(root, "tts-preparation-speed.txt")
        val first = TtsPreparationSpeedFile(file)
        repeat(3) { first.record("voice-a", TtsPreparationSample(3_000, 100)) }
        assertEquals(30.0, assertNotNull(TtsPreparationSpeedFile(file).read().msPerCharacter("voice-a")), 0.001)
        assertNull(TtsPreparationSpeedFile(file).read().msPerCharacter("voice-b"))
    }

    @Test fun `a missing or unreadable record file is an empty record and is replaced on the next write`() {
        val file = File(root, "tts-preparation-speed.txt")
        assertEquals(TtsPreparationSpeedRecord(), TtsPreparationSpeedFile(file).read())
        file.writeBytes(byteArrayOf(0, 1, 2, -1, -2))
        val damaged = TtsPreparationSpeedFile(file)
        assertEquals(TtsPreparationSpeedRecord(), damaged.read())
        repeat(3) { damaged.record("voice-a", TtsPreparationSample(3_000, 100)) }
        assertEquals(30.0, assertNotNull(TtsPreparationSpeedFile(file).read().msPerCharacter("voice-a")), 0.001)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
