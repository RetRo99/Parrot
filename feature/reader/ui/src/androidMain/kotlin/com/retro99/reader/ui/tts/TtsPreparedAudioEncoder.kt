package com.retro99.reader.ui.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException

/** WAV in, prepared audio out. Callers can supply a host fake. */
fun interface TtsPreparedAudioEncoder {
    suspend fun encode(wav: File, output: File): PreparedAudioEncoding
}

sealed interface PreparedAudioEncoding {
    data class Success(val file: File, val durationMs: Long) : PreparedAudioEncoding
    data object Failure : PreparedAudioEncoding
}

internal data class PreparedPcmWav(val sampleRate: Int, val dataOffset: Long, val dataBytes: Long) {
    val durationMs: Long get() = dataBytes * 1_000 / (sampleRate * 2L)

    companion object {
        /** Read chunk headers only, never allocate based on an untrusted chunk length. */
        fun read(file: File): PreparedPcmWav? = try {
            RandomAccessFile(file, "r").use { source ->
                fun uint(): Long = Integer.reverseBytes(source.readInt()).toLong() and 0xffffffffL
                fun ushort(): Int = java.lang.Short.reverseBytes(source.readShort()).toInt() and 0xffff
                fun tag(): String {
                    val bytes = ByteArray(4)
                    source.readFully(bytes)
                    return String(bytes, Charsets.US_ASCII)
                }
                if (source.length() < 12 || tag() != "RIFF") return null
                val end = uint() + 8
                if (end > source.length() || end < 12 || tag() != "WAVE") return null
                var sampleRate: Int? = null
                var dataOffset: Long? = null
                var dataBytes = 0L
                while (source.filePointer + 8 <= end) {
                    val id = tag()
                    val size = uint()
                    val body = source.filePointer
                    if (size > end - body) return null
                    when (id) {
                        "fmt " -> {
                            if (size < 16 || ushort() != 1 || ushort() != 1) return null
                            val rate = uint()
                            val byteRate = uint()
                            val alignment = ushort()
                            val bits = ushort()
                            if (rate == 0L || rate > Int.MAX_VALUE || byteRate != rate * 2 ||
                                alignment != 2 || bits != 16
                            ) return null
                            sampleRate = rate.toInt()
                        }
                        "data" -> {
                            if (size == 0L || size % 2 != 0L || dataOffset != null) return null
                            dataOffset = body
                            dataBytes = size
                        }
                    }
                    val next = body + size + (size and 1)
                    if (next > end) return null
                    source.seek(next)
                }
                if (source.filePointer != end) return null
                PreparedPcmWav(sampleRate ?: return null, dataOffset ?: return null, dataBytes)
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal class TtsPreparedAudioEncoderCore(
    private val encodePcm: suspend (File, PreparedPcmWav, File) -> Long,
) : TtsPreparedAudioEncoder {
    override suspend fun encode(wav: File, output: File): PreparedAudioEncoding {
        var temporary: File? = null
        return try {
            if (wav.canonicalFile == output.canonicalFile || output.exists()) {
                return PreparedAudioEncoding.Failure
            }
            val pcm = PreparedPcmWav.read(wav) ?: return PreparedAudioEncoding.Failure
            val parent = output.absoluteFile.parentFile ?: return PreparedAudioEncoding.Failure
            if (!parent.isDirectory) return PreparedAudioEncoding.Failure
            val staging = File.createTempFile("prepared-", ".part", parent)
            temporary = staging
            val durationMs = encodePcm(wav, pcm, staging)
            if (durationMs <= 0 || staging.length() == 0L) return PreparedAudioEncoding.Failure
            // Same-directory atomic move: no copy fallback that could expose half a clip.
            Files.move(staging.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
            PreparedAudioEncoding.Success(output, durationMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PreparedAudioEncoding.Failure
        } finally {
            temporary?.delete()
        }
    }
}
