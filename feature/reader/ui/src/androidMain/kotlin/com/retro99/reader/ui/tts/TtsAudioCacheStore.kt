package com.retro99.reader.ui.tts

import java.io.File
import java.security.MessageDigest

/**
 * The sentence audio cache's file logic, with no Android dependency: the directory and the
 * clock are given to it, so a host test can drive it. [TtsAudioCache] is the Android-side
 * shell that finds the directory and delegates here.
 */
internal class TtsAudioCacheStore(
    private val directory: File,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private var storesSinceTrim = 0

    fun key(
        voiceId: String,
        modelVersion: String?,
        rate: Float,
        pitch: Float,
        text: String,
    ): String = sha256("$voiceId|${modelVersion.orEmpty()}|${rate.rounded()}|${pitch.rounded()}|$text")

    fun fileFor(key: String): File = File(directory, "$key$FILE_EXTENSION")

    @Synchronized
    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.exists() || file.length() == 0L) return null
        file.setLastModified(now())
        return file
    }

    @Synchronized
    fun onStored(file: File) {
        if (!file.exists() || file.length() == 0L) return
        file.setLastModified(now())
        // Throttled: a full directory scan + sort on every stored sentence is
        // measurable per-sentence overhead.
        storesSinceTrim++
        if (storesSinceTrim >= STORES_PER_TRIM) {
            storesSinceTrim = 0
            trim()
        }
    }

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
        directory.listFiles()?.forEach { file -> file.delete() }
    }

    @Synchronized
    fun trim(maxBytes: Long = MAX_CACHE_BYTES) {
        val files = directory.listFiles() ?: return
        var total = files.sumOf { file -> file.length() }
        if (total <= maxBytes) return

        // TTS-F12: a file touched a moment ago is one a live playlist is about to play, and
        // deleting it stops narration with a player error. Going over the limit for a while
        // is the cheaper outcome, so recently used audio is never evicted.
        val keepAfterMs = now() - RECENTLY_USED_WINDOW_MS
        val oldestFirst = files
            .filter { file -> file.lastModified() < keepAfterMs }
            .sortedBy { file -> file.lastModified() }
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

    internal companion object {
        const val CACHE_DIR_NAME = "tts"
        const val FILE_EXTENSION = ".wav"
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
        const val WAV_HEADER_BYTES = 44
        const val STORES_PER_TRIM = 10

        /** How recently a file must have been used to be safe from eviction. */
        const val RECENTLY_USED_WINDOW_MS = 10L * 60L * 1000L
    }
}
