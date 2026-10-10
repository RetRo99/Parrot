package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer

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
    suspend fun encode(wav: File, pcm: PreparedPcmWav, codec: PreparedAacCodec): Long =
        error("AAC feed/drain pump not implemented")
}
