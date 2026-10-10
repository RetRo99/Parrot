package com.retro99.reader.ui.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** AAC-LC mono at the source sample rate; publication/cleanup belongs to the atomic core. */
@Single(binds = [AndroidTtsPreparedAudioEncoder::class, TtsPreparedAudioEncoder::class])
class AndroidTtsPreparedAudioEncoder : TtsPreparedAudioEncoder {
    private val core = TtsPreparedAudioEncoderCore { wav, pcm, staging ->
        TtsPreparedAacPump().encode(wav, pcm, AndroidPreparedAacCodec(staging, pcm.sampleRate))
    }

    override suspend fun encode(wav: File, output: File): PreparedAudioEncoding =
        withContext(Dispatchers.IO) { core.encode(wav, output) }
}

private class AndroidPreparedAacCodec(private val output: File, sampleRate: Int) : PreparedAacCodec {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var muxer: MediaMuxer? = null
    private var codecStarted = false
    private var muxerStarted = false
    private var track = -1
    private var inputIndex = -1
    private val info = MediaCodec.BufferInfo()

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            codecStarted = true
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun inputBuffer(): ByteBuffer? {
        inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return null
        return checkNotNull(codec.getInputBuffer(inputIndex))
    }

    override fun queueInput(size: Int, presentationTimeUs: Long, endOfStream: Boolean) {
        check(inputIndex >= 0)
        codec.queueInputBuffer(
            inputIndex, 0, size, presentationTimeUs,
            if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
        )
        inputIndex = -1
    }

    override fun drainOutput(): PreparedAacOutput {
        val index = codec.dequeueOutputBuffer(info, 10_000)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            check(!muxerStarted) { "AAC output format changed twice" }
            track = checkNotNull(muxer).addTrack(codec.outputFormat)
            checkNotNull(muxer).start()
            muxerStarted = true
            return PreparedAacOutput.PROGRESS
        }
        if (index < 0) return PreparedAacOutput.WAIT
        try {
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                check(muxerStarted) { "AAC sample arrived before output format" }
                val buffer = checkNotNull(codec.getOutputBuffer(index))
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                checkNotNull(muxer).writeSampleData(track, buffer, info)
            }
            return if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                PreparedAacOutput.END
            } else PreparedAacOutput.PROGRESS
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun finish(): Long {
        check(muxerStarted)
        checkNotNull(muxer).stop()
        muxerStarted = false
        // Never substitute the WAV duration: the format gate needs the actual container duration.
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(output.absolutePath)
            check(extractor.trackCount == 1)
            extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) / 1_000
        } finally {
            extractor.release()
        }
    }

    override fun close() {
        if (codecStarted) runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        codecStarted = false
        muxerStarted = false
        muxer = null
    }
}
