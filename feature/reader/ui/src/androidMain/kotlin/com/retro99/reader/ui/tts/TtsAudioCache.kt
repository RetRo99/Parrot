package com.retro99.reader.ui.tts

import android.content.Context
import android.media.MediaMetadataRetriever
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

    fun key(voiceId: String, rate: Float, pitch: Float, text: String): String =
        sha256("$voiceId|${rate.rounded()}|${pitch.rounded()}|$text")

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
        trim()
    }

    fun durationMs(file: File): Long? {
        if (!file.exists() || file.length() == 0L) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { durationMs -> durationMs > 0L }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
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
    }
}
