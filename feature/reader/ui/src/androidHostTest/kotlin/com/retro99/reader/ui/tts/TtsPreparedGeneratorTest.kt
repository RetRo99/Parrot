package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TtsPreparedGeneratorTest {
    private val root = Files.createTempDirectory("prepared-generator").toFile()
    private val cache = TtsAudioCacheStore(File(root, "cache").apply { mkdirs() })
    private val store = TtsPreparedStore(File(root, "prepared"))
    private val id = PreparedChapterId("book", null, "chapter")
    private val synth = Synth()
    private var encodes = 0
    private var encodeFails = false
    private val encoder = TtsPreparedAudioEncoder { _, out ->
        encodes++
        if (encodeFails) PreparedAudioEncoding.Failure else {
            out.writeBytes(byteArrayOf(1, 2, 3))
            PreparedAudioEncoding.Success(out, 1_020)
        }
    }
    private val generator = TtsAudioGeneratorCore(synth, cache, store, encoder)
    private val settings = PreparedVoiceSettings("system", "v1", 1f, 1f)
    private fun key(text: String = "Sentence.", settings: PreparedVoiceSettings = this.settings) =
        cache.key(settings.voiceId.orEmpty(), settings.modelVersion, settings.rate, settings.pitch, text)

    @AfterTest fun cleanup() { root.deleteRecursively() }

    @Test fun `prepared hit precedes cache and synthesis and word lookup remains cache only`() = runTest {
        seed()
        val result = generator.synthesize("Sentence.", "system", 1f, 1f)
        assertEquals(assertNotNull(store.lookup(key())).file, result.file)
        assertEquals(1_020L, result.durationMs)
        assertEquals(0, synth.calls)
        assertNull(generator.findCached("Sentence.", "system", 1f, 1f))
        cache.fileFor(key()).writeBytes(wavBytes(88_200))
        assertEquals(assertNotNull(store.lookup(key())).file, generator.synthesize("Sentence.", "system", 1f, 1f).file)
    }

    @Test fun `voice model rate and pitch changes miss prepared audio`() = runTest {
        seed()
        generator.synthesize("Sentence.", "other", 1f, 1f)
        generator.synthesize("Sentence.", "system", 1.1f, 1f)
        generator.synthesize("Sentence.", "system", 1f, 1.2f)
        synth.version = "v2"
        generator.synthesize("Sentence.", "system", 1f, 1f)
        assertEquals(4, synth.calls)
    }

    @Test fun `preparation uses a cached WAV then skips already prepared audio`() = runTest {
        store.begin(id, settings, listOf(key()))
        cache.fileFor(key()).writeBytes(wavBytes(88_200))
        assertIs<PreparedAudioEncoding.Success>(generator.prepareSentence(id, "Sentence.", "system", 1f, 1f))
        assertIs<PreparedAudioEncoding.Success>(generator.prepareSentence(id, "Sentence.", "system", 1f, 1f))
        assertEquals(0, synth.calls)
        assertEquals(1, encodes)
        assertEquals(PreparedChapterState.Partial(1, 1), store.state(id, settings))
    }

    @Test fun `preparation synthesizes encodes and serves measured prepared duration`() = runTest {
        store.begin(id, settings, listOf(key()))
        assertIs<PreparedAudioEncoding.Success>(generator.prepareSentence(id, "Sentence.", "system", 1f, 1f))
        assertEquals(1, synth.calls)
        assertEquals(1, encodes)
        assertEquals(1_020L, generator.synthesize("Sentence.", "system", 1f, 1f).durationMs)
        assertEquals(1, synth.calls)
        assertEquals(setOf("${key()}.wav"), cache.fileFor(key()).parentFile!!.list()!!.toSet())
    }

    @Test fun `failed synthesis leaves no new file in either store and never encodes`() = runTest {
        store.begin(id, settings, listOf(key())); synth.fails = true
        assertEquals(PreparedAudioEncoding.Failure, generator.prepareSentence(id, "Sentence.", "system", 1f, 1f))
        assertEquals(1, synth.calls)
        assertEquals(0, encodes)
        assertNull(cache.get(key())); assertNull(store.lookup(key()))
    }

    @Test fun `failed encode removes newly synthesized WAV and publishes no prepared audio`() = runTest {
        store.begin(id, settings, listOf(key())); encodeFails = true
        assertEquals(PreparedAudioEncoding.Failure, generator.prepareSentence(id, "Sentence.", "system", 1f, 1f))
        assertEquals(1, synth.calls); assertEquals(1, encodes)
        assertNull(cache.get(key())); assertNull(store.lookup(key()))
        assertEquals(emptyList(), cache.fileFor(key()).parentFile!!.list()!!.toList())
    }

    @Test fun `real generator serves live before queued preparation after native work finishes`() = runTest {
        val core = TtsAudioGeneratorCore(synth, cache, store, encoder, StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        synth.onCall = { text -> if (text == "first") { entered.complete(Unit); release.await() } }
        store.begin(id, settings, listOf(key("first"), key("second")))
        launch { core.prepareSentence(id, "first", "system", 1f, 1f) }
        entered.await()
        launch { core.prepareSentence(id, "second", "system", 1f, 1f) }
        launch { core.synthesize("live", "system", 1f, 1f) }
        runCurrent()
        assertEquals(listOf("first"), synth.order)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("first", "live", "second"), synth.order)
    }

    @Test fun `preparing the same key in another chapter stores a self contained copy`() = runTest {
        seed()
        val other = id.copy(chapterHref = "other")
        store.begin(other, settings, listOf(key()))
        assertIs<PreparedAudioEncoding.Success>(generator.prepareSentence(other, "Sentence.", "system", 1f, 1f))
        assertEquals(PreparedChapterState.Partial(1, 1), store.state(other, settings))
        store.delete(id)
        assertNotNull(store.lookup(key()))
        assertEquals(0, synth.calls)
    }

    @Test fun `encode failure cannot delete a WAV handed to live playback during encoding`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val delayedEncoder = TtsPreparedAudioEncoder { _, _ -> entered.complete(Unit); finish.await(); PreparedAudioEncoding.Failure }
        val core = TtsAudioGeneratorCore(synth, cache, store, delayedEncoder, StandardTestDispatcher(testScheduler))
        store.begin(id, settings, listOf(key()))
        launch { core.prepareSentence(id, "Sentence.", "system", 1f, 1f) }
        entered.await()
        val live = core.synthesize("Sentence.", "system", 1f, 1f)
        finish.complete(Unit); runCurrent()
        assertEquals(true, assertNotNull(live.file).exists(), "Live playback owns a valid file even when the background encode fails")
        assertNull(store.lookup(key()))
    }

    private fun seed() {
        store.begin(id, settings, listOf(key()))
        val audio = File(root, "seed.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.add(id, key(), audio, 1_020)
    }

    private class Synth : TtsSynthesizer {
        var calls = 0
        var fails = false
        var version = "v1"
        val order = mutableListOf<String>()
        var onCall: suspend (String) -> Unit = {}
        override fun isReady() = true
        override suspend fun awaitReady(timeoutMs: Long) = true
        override fun availableVoices() = emptyList<TtsVoice>()
        override fun defaultVoice(): TtsVoice? = null
        override fun activeModelVersion(voiceId: String?) = version
        override suspend fun synthesize(text: String, voiceId: String?, rate: Float, pitch: Float, outputFile: File): TtsSynthesisResult {
            calls++
            order += text
            onCall(text)
            outputFile.writeBytes(wavBytes(88_200))
            return TtsSynthesisResult(if (fails) TtsSynthesisStatus.ERROR else TtsSynthesisStatus.SUCCESS,
                if (fails) null else outputFile, durationMs = 1_000)
        }
        override fun stop() = Unit
        override suspend fun release() = Unit
    }
}
