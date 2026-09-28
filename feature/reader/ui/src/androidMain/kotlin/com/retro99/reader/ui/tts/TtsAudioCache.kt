package com.retro99.reader.ui.tts

import android.content.Context
import com.retro99.analytics.api.Analytics
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.security.MessageDigest

@Single
class TtsAudioCache(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

    fun key(
        voiceId: String,
        modelVersion: String?,
        rate: Float,
        pitch: Float,
        text: String,
    ): String = sha256("$voiceId|${modelVersion.orEmpty()}|${rate.rounded()}|${pitch.rounded()}|$text")

    fun fileFor(key: String): File = File(cacheDir, "$key$FILE_EXTENSION")

    @Synchronized
    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.exists() || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    @Synchronized
    fun onStored(file: File) {
        if (!file.exists() || file.length() == 0L) return
        file.setLastModified(System.currentTimeMillis())
        // Throttled: a full directory scan + sort on every stored sentence is
        // measurable per-sentence overhead.
        storesSinceTrim++
        if (storesSinceTrim >= STORES_PER_TRIM) {
            storesSinceTrim = 0
            trim()
        }
    }

    private var storesSinceTrim = 0

    fun durationMs(file: File): Long? {
        if (!file.isFile || file.length() <= WAV_HEADER_BYTES) return null

        // Parsed from the canonical PCM WAV header written at synthesis time:
        // duration = data bytes / byte rate. Avoids MediaMetadataRetriever, which is
        // far too expensive to run per sentence.
        return try {
            val header = ByteArray(WAV_HEADER_BYTES)
            file.inputStream().use { input ->
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) return null
                    read += count
                }
            }
            val isWave = header[8] == 'W'.code.toByte() &&
                    header[9] == 'A'.code.toByte() &&
                    header[10] == 'V'.code.toByte() &&
                    header[11] == 'E'.code.toByte()
            if (!isWave) return null
            val byteRate = (header[28].toLong() and 0xFF) or
                    ((header[29].toLong() and 0xFF) shl 8) or
                    ((header[30].toLong() and 0xFF) shl 16) or
                    ((header[31].toLong() and 0xFF) shl 24)
            if (byteRate <= 0L) return null
            ((file.length() - WAV_HEADER_BYTES) * 1000L) / byteRate
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        cacheDir.listFiles()?.forEach { file -> file.delete() }
    }

    @Synchronized
    fun trim(maxBytes: Long = MAX_CACHE_BYTES) {
        val files = cacheDir.listFiles() ?: return
        var total = files.sumOf { file -> file.length() }
        if (total <= maxBytes) return

        val oldestFirst = files.sortedBy { file -> file.lastModified() }
        for (file in oldestFirst) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.encodeToByteArray())
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun Float.rounded(): Int = (this * 100f).toInt()

    private companion object {
        const val CACHE_DIR_NAME = "tts"
        const val FILE_EXTENSION = ".wav"
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
        const val WAV_HEADER_BYTES = 44
        const val STORES_PER_TRIM = 10
    }
}
