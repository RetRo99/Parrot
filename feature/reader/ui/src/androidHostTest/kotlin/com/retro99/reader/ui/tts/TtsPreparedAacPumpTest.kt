package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TtsPreparedAacPumpTest {
    @Test
    fun `feeds only PCM with sample based timestamps and separate end then measures muxed output`() = runTest {
        withInput { wav, pcm ->
            val codec = FakeCodec()
            assertEquals(317L, TtsPreparedAacPump().encode(wav, pcm, codec))
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), codec.bytes.toByteArray())
            assertEquals(listOf(0L, 1_000L, 1_500L), codec.timestamps)
            assertEquals(listOf(false, false, true), codec.endFlags)
            assertTrue(codec.finished)
            assertTrue(codec.closed)
        }
    }

    @Test
    fun `a stalled codec times out and releases without publishing a duration`() = runTest {
        withInput { wav, pcm ->
            val codec = FakeCodec(stalled = true)
            var time = 0L
            assertFailsWith<IllegalStateException> {
                TtsPreparedAacPump { time.also { time += 11_000_000_000L } }.encode(wav, pcm, codec)
            }
            assertTrue(codec.closed)
            assertEquals(false, codec.finished)
        }
    }

    @Test
    fun `cancellation releases codec and propagates`() = runTest {
        withInput { wav, pcm ->
            val codec = FakeCodec(onInput = { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { TtsPreparedAacPump().encode(wav, pcm, codec) }
            assertTrue(codec.closed)
            assertEquals(false, codec.finished)
        }
    }

    private suspend fun withInput(block: suspend (File, PreparedPcmWav) -> Unit) {
        val wav = Files.createTempFile("prepared-pump", ".wav").toFile()
        try {
            wav.writeBytes(byteArrayOf(99, 98, 1, 2, 3, 4, 5, 6, 97))
            block(wav, PreparedPcmWav(2_000, 2, 6))
        } finally { wav.delete() }
    }

    private class FakeCodec(
        val stalled: Boolean = false,
        val onInput: () -> Unit = {},
    ) : PreparedAacCodec {
        val buffer = ByteBuffer.allocate(4)
        val bytes = mutableListOf<Byte>()
        val timestamps = mutableListOf<Long>()
        val endFlags = mutableListOf<Boolean>()
        var closed = false
        var finished = false
        private var drainCount = 0
        override fun inputBuffer(): ByteBuffer? {
            onInput()
            return if (stalled) null else buffer.also { it.clear() }
        }
        override fun queueInput(size: Int, presentationTimeUs: Long, endOfStream: Boolean) {
            bytes += buffer.array().take(size)
            timestamps += presentationTimeUs
            endFlags += endOfStream
        }
        override fun drainOutput(): PreparedAacOutput = when {
            stalled -> PreparedAacOutput.WAIT
            endFlags.lastOrNull() == true -> PreparedAacOutput.END
            drainCount++ == 0 -> PreparedAacOutput.PROGRESS // format before first sample
            else -> PreparedAacOutput.WAIT
        }
        override fun finish(): Long { finished = true; return 317 }
        override fun close() { closed = true }
    }
}
