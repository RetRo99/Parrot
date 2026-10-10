package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * TTS-F03: a synthesis that did not succeed must leave no cache entry. The output file the
 * synthesizer writes to *is* the cache entry, so a partial WAV left behind is served as a
 * valid hit forever. See the finding in `docs/tts-investigation.md`.
 */
class TtsAudioGeneratorTest {

    private val directory: File = Files.createTempDirectory("tts-generator").toFile()
    private val store = TtsAudioCacheStore(directory = directory, now = { NOW_MS })

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a synthesis that fails leaves no cache entry`() = runTest {
        // Given a synthesizer that writes a partial WAV and then reports a failure
        val synthesizer = PartialWriteSynthesizer(outcome = Outcome.ERROR)
        val generator = TtsAudioGeneratorCore(synthesizer = synthesizer, cache = store)

        // When
        val result = generator.synthesize(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f)

        // Then
        assertEquals(TtsSynthesisStatus.ERROR, result.status)
        assertNull(
            generator.findCached(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f),
            "a failed synthesis must not leave a cache entry",
        )
        assertFalse(synthesizer.writtenFile!!.exists(), "the partial file must be deleted")
    }

    @Test
    fun `a synthesis that fails is retried instead of served from the cache`() = runTest {
        // Given
        val synthesizer = PartialWriteSynthesizer(outcome = Outcome.ERROR)
        val generator = TtsAudioGeneratorCore(synthesizer = synthesizer, cache = store)

        // When the same sentence is asked for twice
        generator.synthesize(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f)
        generator.synthesize(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f)

        // Then
        assertEquals(2, synthesizer.calls, "the second call must synthesise again")
    }

    @Test
    fun `a cancelled synthesis leaves no cache entry`() = runTest {
        // Given
        val synthesizer = PartialWriteSynthesizer(outcome = Outcome.CANCELLED)
        val generator = TtsAudioGeneratorCore(synthesizer = synthesizer, cache = store)

        // When
        val result = generator.synthesize(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f)

        // Then
        assertEquals(TtsSynthesisStatus.CANCELLED, result.status)
        assertNull(generator.findCached(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f))
        assertFalse(synthesizer.writtenFile!!.exists(), "the partial file must be deleted")
    }

    @Test
    fun `a synthesis that throws leaves no cache entry`() = runTest {
        // Given
        val synthesizer = PartialWriteSynthesizer(outcome = Outcome.THROW)
        val generator = TtsAudioGeneratorCore(synthesizer = synthesizer, cache = store)

        // When
        assertFailsWith<IllegalStateException> {
            generator.synthesize(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f)
        }

        // Then
        assertNull(generator.findCached(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f))
        assertFalse(synthesizer.writtenFile!!.exists(), "the partial file must be deleted")
    }

    @Test
    fun `a successful synthesis is stored and served from the cache`() = runTest {
        // Given
        val synthesizer = PartialWriteSynthesizer(outcome = Outcome.SUCCESS)
        val generator = TtsAudioGeneratorCore(synthesizer = synthesizer, cache = store)

        // When
        val result = generator.synthesize(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f)
        val second = generator.synthesize(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f)

        // Then
        assertEquals(TtsSynthesisStatus.SUCCESS, result.status)
        assertEquals(TtsSynthesisStatus.SUCCESS, second.status)
        assertEquals(1, synthesizer.calls, "the second call must be a cache hit")
        assertNotNull(generator.findCached(text = TEXT, voiceId = VOICE_ID, rate = 1f, pitch = 1f))
        assertEquals(1_000L, second.durationMs, "the duration comes from the cached WAV header")
    }

    private enum class Outcome { SUCCESS, ERROR, CANCELLED, THROW }

    /**
     * Writes to the output file the way a neural engine does — the file is the cache entry —
     * and only then reports its outcome.
     */
    private class PartialWriteSynthesizer(private val outcome: Outcome) : TtsSynthesizer {

        var calls: Int = 0
            private set

        var writtenFile: File? = null
            private set

        override fun isReady(): Boolean = true

        override suspend fun awaitReady(timeoutMs: Long): Boolean = true

        override fun availableVoices(): List<TtsVoice> = emptyList()

        override fun defaultVoice(): TtsVoice? = null

        override suspend fun synthesize(
            text: String,
            voiceId: String?,
            rate: Float,
            pitch: Float,
            outputFile: File,
        ): TtsSynthesisResult {
            calls++
            writtenFile = outputFile
            outputFile.parentFile?.mkdirs()
            if (outcome == Outcome.SUCCESS) {
                outputFile.writeBytes(wavBytes(dataBytes = 88_200))
                return TtsSynthesisResult(status = TtsSynthesisStatus.SUCCESS, file = outputFile)
            }
            outputFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            return when (outcome) {
                Outcome.ERROR -> TtsSynthesisResult(
                    status = TtsSynthesisStatus.ERROR,
                    error = "failed to save audio",
                )

                Outcome.CANCELLED -> TtsSynthesisResult(
                    status = TtsSynthesisStatus.CANCELLED,
                    error = "stopped",
                )

                Outcome.THROW -> error("synthesis blew up")
                Outcome.SUCCESS -> error("unreachable")
            }
        }

        override fun stop() = Unit

        override suspend fun release() = Unit
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val TEXT = "A sentence that fails."
        const val VOICE_ID = "kokoro:0"
    }
}
