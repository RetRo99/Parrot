package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * TTS-F21: a one-word sentence read-aloud has cached gets the same cache key as the spoken
 * word, so the word path's trim rewrites a cache entry the engine's player may be reading.
 * `TtsWordAudioSource` itself needs an ExoPlayer and the read-aloud engine, so the step that
 * makes the clip file is tested on its own. See the finding in `docs/tts-investigation.md`.
 */
class TtsWordClipFileTest {

    private val directory: File = Files.createTempDirectory("tts-word-clip").toFile()
    private val store = TtsAudioCacheStore(directory = directory, now = { NOW_MS })

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `preparing a word clip does not modify the cache entry`() {
        // Given a cached sentence WAV with silence at both ends
        val key = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1f, pitch = 1f, text = "Yes.")
        val cacheFile = store.fileFor(key)
        val cachedBytes = paddedWav()
        cacheFile.writeBytes(cachedBytes)

        // When the word path prepares its clip from it
        val clip = prepareWordClipFile(cacheFile)

        // Then the cache entry is byte-for-byte what it was
        assertContentEquals(cachedBytes, cacheFile.readBytes(), "the cache entry must not be rewritten")
        assertNotEquals(cacheFile, clip, "the word clip must be its own file")
        assertTrue(clip.isFile, "the word clip must exist")
        assertTrue(clip.length() < cacheFile.length(), "the word clip must be trimmed")
    }

    @Test
    fun `the word clip lives in the cache directory and is not a cache hit`() {
        // Given
        val key = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1f, pitch = 1f, text = "Yes.")
        val cacheFile = store.fileFor(key).apply { writeBytes(paddedWav()) }

        // When
        val clip = prepareWordClipFile(cacheFile)

        // Then the cache's own trim can evict it, and no lookup can land on it
        assertEqualsDirectory(clip)
        assertNull(directory.listFiles()?.firstOrNull { file -> file.name.endsWith(".trim") })
        assertTrue(
            store.fileFor(key).readBytes().size > clip.length().toInt(),
            "the key still resolves to the untrimmed sentence audio",
        )
    }

    private fun assertEqualsDirectory(clip: File) {
        assertTrue(
            clip.parentFile?.canonicalFile == directory.canonicalFile,
            "the clip must sit in the cache directory, was ${clip.parent}",
        )
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val SAMPLE_RATE = 16_000
        const val AMPLITUDE: Short = 10_000

        /** Half a second of silence, half a second of speech, half a second of silence. */
        fun paddedWav(): ByteArray {
            val half = SAMPLE_RATE / 2
            val data = ByteArray((3 * half) * 2)
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            repeat(half) { frame -> buffer.putShort((half + frame) * 2, AMPLITUDE) }
            return wavBytes(data = data, sampleRate = SAMPLE_RATE)
        }
    }
}
