package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trims leading and trailing silence from a 16-bit PCM WAV, replacing the file atomically.
 * Neural engines pad a single word with hundreds of milliseconds of silence (Supertonic
 * ~0.35 s before and ~0.5 s after, docs/tts-bench-results.md 2026-10-05); the padding delays
 * both the first audible sound and the resume of paused audio. A margin is kept at both ends
 * so plosives and decays survive. Returns the same file untouched when nothing measurable
 * can be trimmed or the file is not a parseable PCM WAV.
 */
fun trimWavSilence(file: File, marginMs: Int = DEFAULT_MARGIN_MS): File {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return file
    val wav = parsePcm16Wav(bytes) ?: return file

    val frameSize = 2 * wav.channels
    val frames = wav.dataSize / frameSize
    if (frames <= 0) return file

    var peak = 0
    for (frame in 0 until frames) {
        for (channel in 0 until wav.channels) {
            val offset = wav.dataStart + frame * frameSize + channel * 2
            val sample = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF))
                .toShort().toInt()
            if (kotlin.math.abs(sample) > peak) peak = kotlin.math.abs(sample)
        }
    }
    if (peak == 0) return file
    val threshold = maxOf(peak / 50, SILENCE_FLOOR)

    fun isLoud(frame: Int): Boolean {
        for (channel in 0 until wav.channels) {
            val offset = wav.dataStart + frame * frameSize + channel * 2
            val sample = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF))
                .toShort().toInt()
            if (kotlin.math.abs(sample) >= threshold) return true
        }
        return false
    }

    var firstLoud = 0
    while (firstLoud < frames && !isLoud(firstLoud)) firstLoud++
    if (firstLoud >= frames) return file
    var lastLoud = frames - 1
    while (lastLoud > firstLoud && !isLoud(lastLoud)) lastLoud--

    val marginFrames = marginMs.toLong() * wav.sampleRate / 1000
    val startFrame = (firstLoud - marginFrames).coerceAtLeast(0L)
    val endFrame = (lastLoud + 1 + marginFrames).coerceAtMost(frames.toLong())
    if (startFrame == 0L && endFrame == frames.toLong()) return file

    val sliceStart = (wav.dataStart + startFrame * frameSize).toInt()
    val sliceEnd = (wav.dataStart + endFrame * frameSize).toInt()
    val newDataSize = sliceEnd - sliceStart
    val out = ByteArray(wav.dataStart + newDataSize)
    System.arraycopy(bytes, 0, out, 0, wav.dataStart)
    System.arraycopy(bytes, sliceStart, out, wav.dataStart, newDataSize)
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(RIFF_SIZE_OFFSET, out.size - 8)
        putInt(wav.dataStart - CHUNK_SIZE_OFFSET_FROM_BODY, newDataSize)
    }

    val temporary = File(file.parentFile, "${file.name}.trim")
    return try {
        temporary.writeBytes(out)
        if (temporary.renameTo(file)) file else run {
            temporary.delete()
            file
        }
    } catch (_: Exception) {
        temporary.delete()
        file
    }
}

private data class Pcm16Wav(
    val channels: Int,
    val sampleRate: Int,
    val dataStart: Int,
    val dataSize: Int,
)

private fun parsePcm16Wav(bytes: ByteArray): Pcm16Wav? {
    if (bytes.size < MIN_WAV_BYTES) return null
    if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var channels = 0
    var sampleRate = 0
    var dataStart = -1
    var dataSize = 0
    var offset = 12
    while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
        val id = String(bytes, offset, 4)
        val size = buffer.getInt(offset + 4)
        if (size < 0) return null
        when (id) {
            "fmt " -> {
                if (offset + CHUNK_HEADER_BYTES + MIN_FMT_BYTES > bytes.size) return null
                if (buffer.getShort(offset + CHUNK_HEADER_BYTES + AUDIO_FORMAT_OFFSET) != PCM_FORMAT) {
                    return null
                }
                channels = buffer.getShort(offset + CHUNK_HEADER_BYTES + CHANNELS_OFFSET).toInt()
                sampleRate = buffer.getInt(offset + CHUNK_HEADER_BYTES + SAMPLE_RATE_OFFSET)
            }

            "data" -> {
                dataStart = offset + CHUNK_HEADER_BYTES
                dataSize = size.coerceAtMost(bytes.size - dataStart)
                break
            }
        }
        offset += CHUNK_HEADER_BYTES + size + (size and 1)
    }
    if (channels <= 0 || sampleRate <= 0 || dataStart < 0) return null
    return Pcm16Wav(channels = channels, sampleRate = sampleRate, dataStart = dataStart, dataSize = dataSize)
}

private const val DEFAULT_MARGIN_MS = 40
private const val SILENCE_FLOOR = 256
private const val MIN_WAV_BYTES = 44
private const val CHUNK_HEADER_BYTES = 8
private const val MIN_FMT_BYTES = 16
private const val AUDIO_FORMAT_OFFSET = 0
private const val CHANNELS_OFFSET = 2
private const val SAMPLE_RATE_OFFSET = 4
private const val PCM_FORMAT = 1.toShort()
private const val RIFF_SIZE_OFFSET = 4
private const val CHUNK_SIZE_OFFSET_FROM_BODY = 4
