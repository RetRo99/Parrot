package com.retro99.ttsbench

import java.io.File
import java.io.RandomAccessFile

const val KIND_WORD = "word"

/** The single word the latency row uses: short, common, one spoken word. */
const val WORD_TEXT = "hello"

const val PHASE_COLD = "cold"
const val PHASE_WARM = "warm"

/** Mirrors `SupertonicOnnxSynthesizer.GENERATION_STEPS`: what the app actually uses. */
const val APP_GENERATION_STEPS = 8

/** One one-word synthesis: what a speaker tap on the dictionary strip pays. */
data class WordMeasurement(
    val engine: String,
    val version: String,
    val threads: Int,
    val steps: Int,
    val phase: String,
    val run: Int,
    val generationMs: Long,
    val audioMs: Long,
    val sampleRate: Int,
    val thermal: Int,
    val charging: Boolean,
    /** File name of the saved WAV; only the cold run is kept. */
    val sampleFile: String = "",
)

/** PCM WAV facts read from the RIFF chunks; enough for duration without a decoder. */
data class WavInfo(val sampleRate: Int, val durationMs: Long)

/**
 * Walks the RIFF chunks (`fmt ` + `data`) instead of assuming the canonical 44-byte header,
 * because vendor TTS engines write slightly different layout.
 */
fun readWavInfo(file: File): WavInfo? {
    if (!file.isFile || file.length() < 44L) return null
    RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(12)
        if (input.read(header) != header.size) return null
        if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null

        var sampleRate = 0
        var byteRate = 0
        var dataBytes = -1L
        val chunkHeader = ByteArray(8)
        while (input.read(chunkHeader) == chunkHeader.size) {
            val id = String(chunkHeader, 0, 4)
            val size = readUIntLe(chunkHeader, 4)
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size.coerceAtMost(32L).toInt())
                    if (input.read(fmt) != fmt.size) return null
                    if (size > fmt.size) input.skipBytes((size - fmt.size).toInt())
                    sampleRate = readUIntLe(fmt, 4).toInt()
                    byteRate = readUIntLe(fmt, 8).toInt()
                }
                "data" -> {
                    dataBytes = size
                    break
                }
                else -> input.skipBytes(size.toInt())
            }
        }
        if (sampleRate <= 0 || byteRate <= 0 || dataBytes < 0) return null
        return WavInfo(sampleRate = sampleRate, durationMs = dataBytes * MS_PER_SECOND / byteRate)
    }
}

private fun readUIntLe(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

private const val MS_PER_SECOND = 1000L
