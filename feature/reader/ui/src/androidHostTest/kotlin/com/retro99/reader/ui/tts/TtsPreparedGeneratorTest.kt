package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private fun seed() {
        store.begin(id, settings, listOf(key()))
        val audio = File(root, "seed.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.add(id, key(), audio, 1_020)
    }

    private class Synth : TtsSynthesizer {
        var calls = 0
        var fails = false
        var version = "v1"
        override fun isReady() = true
        override suspend fun awaitReady(timeoutMs: Long) = true
        override fun availableVoices() = emptyList<TtsVoice>()
        override fun defaultVoice(): TtsVoice? = null
        override fun activeModelVersion(voiceId: String?) = version
        override suspend fun synthesize(text: String, voiceId: String?, rate: Float, pitch: Float, outputFile: File): TtsSynthesisResult {
            calls++
            outputFile.writeBytes(wavBytes(88_200))
            return TtsSynthesisResult(if (fails) TtsSynthesisStatus.ERROR else TtsSynthesisStatus.SUCCESS,
                if (fails) null else outputFile, durationMs = 1_000)
        }
        override fun stop() = Unit
        override suspend fun release() = Unit
    }
}
