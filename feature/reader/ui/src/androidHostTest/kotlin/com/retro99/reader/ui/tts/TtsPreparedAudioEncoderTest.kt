package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith

class TtsPreparedAudioEncoderTest {
    private val root = Files.createTempDirectory("prepared-encoder").toFile()
    private val input = File(root, "input.wav")
    private val output = File(root, "output.m4a")

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    @Test
    fun `mono PCM gives its sample rate data range and duration`() {
        wav()
        val pcm = PreparedPcmWav.read(input)!!
        assertEquals(24_000, pcm.sampleRate)
        assertEquals(44L, pcm.dataOffset)
        assertEquals(48_000L, pcm.dataBytes)
        assertEquals(1_000L, pcm.durationMs)
    }

    @Test
    fun `unknown chunks and their odd padding are skipped`() {
        wav(extraChunk = true)
        assertEquals(54L, PreparedPcmWav.read(input)!!.dataOffset)
    }

    @Test
    fun `stereo and non PCM are rejected`() {
        wav(channels = 2)
        assertNull(PreparedPcmWav.read(input))
        wav(format = 3)
        assertNull(PreparedPcmWav.read(input))
    }

    @Test
    fun `unsupported bit depth and inconsistent byte rate are rejected`() {
        wav(bits = 8)
        assertNull(PreparedPcmWav.read(input))
        wav(byteRate = 123)
        assertNull(PreparedPcmWav.read(input))
    }

    @Test
    fun `truncated or empty PCM and invalid RIFF are rejected`() {
        wav()
        input.writeBytes(input.readBytes().dropLast(1).toByteArray())
        assertNull(PreparedPcmWav.read(input))
        wav(dataSize = 0)
        assertNull(PreparedPcmWav.read(input))
        input.writeText("not a WAV")
        assertNull(PreparedPcmWav.read(input))
    }

    @Test
    fun `malicious unsigned chunk length is rejected without allocation`() {
        wav()
        val bytes = input.readBytes()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1)
        input.writeBytes(bytes)
        assertNull(PreparedPcmWav.read(input))
    }

    @Test
    fun `successful encode publishes a nonempty file and measured duration`() = runTest {
        wav()
        val original = input.readBytes()
        val encoder = TtsPreparedAudioEncoderCore { source, pcm, temporary ->
            assertEquals(input, source)
            assertEquals(24_000, pcm.sampleRate)
            assertFalse(output.exists(), "Never expose an unfinished output")
            assertTrue(temporary != output)
            temporary.writeBytes(byteArrayOf(1, 2, 3))
            1_021L
        }
        val result = assertIs<PreparedAudioEncoding.Success>(encoder.encode(input, output))
        assertEquals(output, result.file)
        assertEquals(1_021L, result.durationMs)
        assertTrue(original.contentEquals(input.readBytes()), "The cache WAV is untouched")
        assertEquals(setOf("input.wav", "output.m4a"), root.list()!!.toSet())
    }

    @Test
    fun `a failed encode leaves no final or temporary output`() = runTest {
        wav()
        val encoder = TtsPreparedAudioEncoderCore { _, _, temporary ->
            temporary.writeText("partial")
            error("codec failed")
        }
        assertEquals(PreparedAudioEncoding.Failure, encoder.encode(input, output))
        assertEquals(setOf("input.wav"), root.list()!!.toSet())
    }

    @Test
    fun `cancellation cleans up and propagates`() = runTest {
        wav()
        val encoder = TtsPreparedAudioEncoderCore { _, _, temporary ->
            temporary.writeText("partial")
            throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> { encoder.encode(input, output) }
        assertEquals(setOf("input.wav"), root.list()!!.toSet())
    }

    @Test
    fun `empty output and invalid duration never become success`() = runTest {
        wav()
        val empty = TtsPreparedAudioEncoderCore { _, _, _ -> 1_000L }
        assertEquals(PreparedAudioEncoding.Failure, empty.encode(input, output))
        val invalid = TtsPreparedAudioEncoderCore { _, _, temporary ->
            temporary.writeText("encoded")
            0L
        }
        assertEquals(PreparedAudioEncoding.Failure, invalid.encode(input, output))
        assertEquals(setOf("input.wav"), root.list()!!.toSet())
    }

    @Test
    fun `invalid WAV never calls codec`() = runTest {
        input.writeText("bad")
        val encoder = TtsPreparedAudioEncoderCore { _, _, _ -> error("must not encode") }
        assertEquals(PreparedAudioEncoding.Failure, encoder.encode(input, output))
        assertFalse(output.exists())
    }

    @Test
    fun `existing output and input aliased as output are never overwritten`() = runTest {
        wav()
        output.writeText("existing")
        val original = input.readBytes()
        val encoder = TtsPreparedAudioEncoderCore { _, _, _ -> error("must not encode") }
        assertEquals(PreparedAudioEncoding.Failure, encoder.encode(input, output))
        assertEquals("existing", output.readText())
        assertEquals(PreparedAudioEncoding.Failure, encoder.encode(input, input))
        assertTrue(original.contentEquals(input.readBytes()))
    }

    private fun wav(
        format: Int = 1, channels: Int = 1, bits: Int = 16,
        byteRate: Int = 48_000, dataSize: Int = 48_000, extraChunk: Boolean = false,
    ) {
        val size = 44 + dataSize + if (extraChunk) 10 else 0
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(size - 8).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16)
        b.putShort(format.toShort()).putShort(channels.toShort()).putInt(24_000)
        b.putInt(byteRate).putShort(2).putShort(bits.toShort())
        if (extraChunk) b.put("JUNK".toByteArray()).putInt(1).put(42).put(0)
        b.put("data".toByteArray()).putInt(dataSize)
        input.writeBytes(b.array())
    }
}
