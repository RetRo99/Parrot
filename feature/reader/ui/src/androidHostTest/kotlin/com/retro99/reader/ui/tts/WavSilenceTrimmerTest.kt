package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [trimWavSilence]: what it removes, what it leaves alone and that it never leaves a
 * `.trim` file behind. Written for TTS-F21, which is about *which* file the word path trims.
 */
class WavSilenceTrimmerTest {

    private val directory: File = Files.createTempDirectory("tts-trimmer").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `leading and trailing silence is removed, a margin kept`() {
        // Given one second of silence, one of speech and one of silence at 16 kHz
        val frames = silence(SAMPLE_RATE) + tone(SAMPLE_RATE) + silence(SAMPLE_RATE)
        val file = writeWav("padded.wav", frames)

        // When
        val trimmed = trimWavSilence(file)

        // Then the speech plus a 40 ms margin at each end is left
        val marginFrames = 40 * SAMPLE_RATE / 1000
        val expectedFrames = SAMPLE_RATE + 2 * marginFrames
        assertSame(file, trimmed, "the trim replaces the file it was given")
        assertEquals(expectedFrames * 2, dataSizeOf(trimmed))
        assertEquals(44 + expectedFrames * 2, trimmed.length().toInt())
        assertNoTemporaryFiles()
    }

    @Test
    fun `an all-silent file is returned untouched`() {
        // Given
        val original = wavBytes(data = silence(SAMPLE_RATE))
        val file = writeBytes("silent.wav", original)

        // When
        val trimmed = trimWavSilence(file)

        // Then
        assertSame(file, trimmed)
        assertContentEquals(original, trimmed.readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun `a file with no silence is returned untouched`() {
        // Given
        val original = wavBytes(data = tone(SAMPLE_RATE))
        val file = writeBytes("loud.wav", original)

        // When
        val trimmed = trimWavSilence(file)

        // Then
        assertSame(file, trimmed)
        assertContentEquals(original, trimmed.readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun `a file that is not a WAV is returned untouched`() {
        // Given
        val original = ByteArray(200) { 'x'.code.toByte() }
        val file = writeBytes("notes.txt", original)

        // When
        val trimmed = trimWavSilence(file)

        // Then
        assertSame(file, trimmed)
        assertContentEquals(original, trimmed.readBytes())
        assertNoTemporaryFiles()
    }

    @Test
    fun `a malformed WAV is returned untouched`() {
        // Given a RIFF/WAVE header with no parseable fmt or data chunk
        val original = wavBytes(data = tone(100)).copyOf(60).also { bytes ->
            bytes.fill(0, 12, 60)
        }
        val file = writeBytes("malformed.wav", original)

        // When
        val trimmed = trimWavSilence(file)

        // Then
        assertSame(file, trimmed)
        assertContentEquals(original, trimmed.readBytes())
        assertNoTemporaryFiles()
    }

    private fun assertNoTemporaryFiles() {
        val leftovers = directory.listFiles()?.filter { file -> file.name.endsWith(".trim") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "no .trim file may be left behind, found $leftovers")
    }

    private fun writeWav(name: String, data: ByteArray): File =
        writeBytes(name, wavBytes(data = data, sampleRate = SAMPLE_RATE))

    private fun writeBytes(name: String, bytes: ByteArray): File =
        File(directory, name).apply { writeBytes(bytes) }

    private fun dataSizeOf(file: File): Int =
        ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40)

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val AMPLITUDE: Short = 10_000

        fun silence(frames: Int): ByteArray = ByteArray(frames * 2)

        fun tone(frames: Int): ByteArray = ByteArray(frames * 2).also { bytes ->
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { frame -> buffer.putShort(frame * 2, AMPLITUDE) }
        }
    }
}
