package com.retro99.reader.ui.tts

import java.io.File

/** WAV in, prepared audio out. Callers can supply a host fake. */
internal fun interface TtsPreparedAudioEncoder {
    suspend fun encode(wav: File, output: File): PreparedAudioEncoding
}

internal sealed interface PreparedAudioEncoding {
    data class Success(val file: File, val durationMs: Long) : PreparedAudioEncoding
    data object Failure : PreparedAudioEncoding
}

internal data class PreparedPcmWav(val sampleRate: Int, val dataOffset: Long, val dataBytes: Long) {
    val durationMs: Long get() = dataBytes * 1_000 / (sampleRate * 2L)

    companion object {
        // Red-phase seam: the tests specify the supported WAV shape before implementation.
        fun read(file: File): PreparedPcmWav? = null
    }
}

internal class TtsPreparedAudioEncoderCore(
    private val encodePcm: suspend (File, PreparedPcmWav, File) -> Long,
) : TtsPreparedAudioEncoder {
    override suspend fun encode(wav: File, output: File): PreparedAudioEncoding =
        PreparedAudioEncoding.Failure
}
