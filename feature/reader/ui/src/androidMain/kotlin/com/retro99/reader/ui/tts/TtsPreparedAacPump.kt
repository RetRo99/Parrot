package com.retro99.reader.ui.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Platform owns codec buffers and muxing; the bounded feed/drain protocol is host tested. */
internal interface PreparedAacCodec : AutoCloseable {
    fun inputBuffer(): ByteBuffer?
    fun queueInput(size: Int, presentationTimeUs: Long, endOfStream: Boolean)
    fun drainOutput(): PreparedAacOutput
    fun finish(): Long
}

internal enum class PreparedAacOutput { WAIT, PROGRESS, END }

internal class TtsPreparedAacPump(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun encode(wav: File, pcm: PreparedPcmWav, codec: PreparedAacCodec): Long = codec.use {
        RandomAccessFile(wav, "r").use source@{ input ->
            input.seek(pcm.dataOffset)
            var sent = 0L
            var inputEnded = false
            var lastProgress = nanoTime()
            val bytes = ByteArray(16_384)
            while (true) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inputEnded) {
                    codec.inputBuffer()?.let { buffer ->
                        currentCoroutineContext().ensureActive()
                        buffer.clear()
                        val count = minOf(bytes.size.toLong(), buffer.remaining().toLong(), pcm.dataBytes - sent)
                            .toInt().let { it - it % 2 }
                        check(count > 0 || sent == pcm.dataBytes) { "Codec input cannot hold a PCM sample" }
                        if (count > 0) {
                            input.readFully(bytes, 0, count)
                            buffer.put(bytes, 0, count)
                        }
                        val timestamp = sent / 2 * 1_000_000L / pcm.sampleRate
                        inputEnded = sent == pcm.dataBytes
                        codec.queueInput(count, timestamp, inputEnded)
                        sent += count
                        progressed = true
                    }
                }
                when (codec.drainOutput()) {
                    PreparedAacOutput.END -> {
                        check(inputEnded) { "Codec ended before all PCM was submitted" }
                        return@source codec.finish()
                    }
                    PreparedAacOutput.PROGRESS -> progressed = true
                    PreparedAacOutput.WAIT -> Unit
                }
                val now = nanoTime()
                if (progressed) lastProgress = now
                check(now - lastProgress < 10_000_000_000L) { "AAC codec stalled" }
            }
            @Suppress("UNREACHABLE_CODE")
            0L
        }
    }
}
