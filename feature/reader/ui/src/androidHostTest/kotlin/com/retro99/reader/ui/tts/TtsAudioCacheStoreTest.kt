package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the sentence audio cache's file logic: key composition, what counts as a hit, how a
 * hit ages the file, the eviction order and the WAV duration parse. The behaviour is the one
 * `TtsAudioCache` had before run 3a moved it into [TtsAudioCacheStore].
 */
class TtsAudioCacheStoreTest {

    private val directory: File = Files.createTempDirectory("tts-cache-store").toFile()

    private var nowMs: Long = 1_700_000_000_000L

    private val store = TtsAudioCacheStore(directory = directory, now = { nowMs })

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `the key is the same for the same inputs`() {
        // Given / When
        val first = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1f, pitch = 1f, text = "Hello.")
        val second = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1f, pitch = 1f, text = "Hello.")

        // Then
        assertEquals(first, second)
    }

    @Test
    fun `the key differs when any input differs`() {
        // Given
        val base = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1f, pitch = 1f, text = "Hello.")

        // When
        val otherVoice = store.key(voiceId = "kokoro:1", modelVersion = "1", rate = 1f, pitch = 1f, text = "Hello.")
        val otherVersion = store.key(voiceId = "kokoro:0", modelVersion = "2", rate = 1f, pitch = 1f, text = "Hello.")
        val otherRate = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1.5f, pitch = 1f, text = "Hello.")
        val otherPitch = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1f, pitch = 1.2f, text = "Hello.")
        val otherText = store.key(voiceId = "kokoro:0", modelVersion = "1", rate = 1f, pitch = 1f, text = "Goodbye.")

        // Then
        assertEquals(5, setOf(otherVoice, otherVersion, otherRate, otherPitch, otherText).size)
        listOf(otherVoice, otherVersion, otherRate, otherPitch, otherText).forEach { other ->
            assertNotEquals(base, other)
        }
    }

    @Test
    fun `get returns null for a missing file`() {
        // Given / When / Then
        assertNull(store.get("missing"))
    }

    @Test
    fun `get returns null for an empty file`() {
        // Given
        store.fileFor("empty").writeBytes(ByteArray(0))

        // When / Then
        assertNull(store.get("empty"))
    }

    @Test
    fun `get on a hit refreshes the last-modified time`() {
        // Given
        val file = store.fileFor("hit").apply { writeBytes(wavBytes(dataBytes = 100)) }
        file.setLastModified(nowMs - 60 * 60 * 1000L)

        // When
        val hit = store.get("hit")

        // Then
        assertEquals(file, hit)
        assertEquals(nowMs, file.lastModified())
    }

    @Test
    fun `trim deletes the oldest files first and stops once under the limit`() {
        // Given three 100-byte files, each a day older than the last
        val oldest = fileAged(name = "oldest.wav", sizeBytes = 100, ageMs = 3 * DAY_MS)
        val middle = fileAged(name = "middle.wav", sizeBytes = 100, ageMs = 2 * DAY_MS)
        val newest = fileAged(name = "newest.wav", sizeBytes = 100, ageMs = DAY_MS)

        // When
        store.trim(maxBytes = 150L)

        // Then
        assertFalse(oldest.exists(), "the oldest file should have been evicted")
        assertFalse(middle.exists(), "the second-oldest file should have been evicted")
        assertTrue(newest.exists(), "the trim should stop once it is under the limit")
    }

    @Test
    fun `durationMs reads the duration from a PCM WAV header`() {
        // Given a 44.1 kHz 16-bit mono WAV with one second of audio
        val file = store.fileFor("duration").apply { writeBytes(wavBytes(dataBytes = 88_200)) }

        // When / Then
        assertEquals(1_000L, store.durationMs(file))
    }

    @Test
    fun `durationMs is null for a file shorter than the header`() {
        // Given
        val file = store.fileFor("short").apply { writeBytes(ByteArray(20)) }

        // When / Then
        assertNull(store.durationMs(file))
    }

    @Test
    fun `durationMs is null for a file that is not a WAV`() {
        // Given
        val file = store.fileFor("text").apply { writeBytes(ByteArray(200) { 'x'.code.toByte() }) }

        // When / Then
        assertNull(store.durationMs(file))
    }

    private fun fileAged(name: String, sizeBytes: Int, ageMs: Long): File =
        File(directory, name).apply {
            writeBytes(ByteArray(sizeBytes))
            setLastModified(nowMs - ageMs)
        }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** A canonical 44-byte-header 16-bit mono 44.1 kHz PCM WAV with [dataBytes] of silence. */
internal fun wavBytes(dataBytes: Int, sampleRate: Int = 44_100, channels: Int = 1): ByteArray =
    wavBytes(data = ByteArray(dataBytes), sampleRate = sampleRate, channels = channels)

/** A canonical 44-byte-header 16-bit PCM WAV wrapping [data]. */
internal fun wavBytes(data: ByteArray, sampleRate: Int = 44_100, channels: Int = 1): ByteArray {
    val byteRate = sampleRate * channels * 2
    val out = ByteArray(44 + data.size)
    val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    out[0] = 'R'.code.toByte()
    out[1] = 'I'.code.toByte()
    out[2] = 'F'.code.toByte()
    out[3] = 'F'.code.toByte()
    buffer.putInt(4, out.size - 8)
    "WAVEfmt ".forEachIndexed { index, char -> out[8 + index] = char.code.toByte() }
    buffer.putInt(16, 16)
    buffer.putShort(20, 1)
    buffer.putShort(22, channels.toShort())
    buffer.putInt(24, sampleRate)
    buffer.putInt(28, byteRate)
    buffer.putShort(32, (channels * 2).toShort())
    buffer.putShort(34, 16)
    "data".forEachIndexed { index, char -> out[36 + index] = char.code.toByte() }
    buffer.putInt(40, data.size)
    data.copyInto(out, 44)
    return out
}
