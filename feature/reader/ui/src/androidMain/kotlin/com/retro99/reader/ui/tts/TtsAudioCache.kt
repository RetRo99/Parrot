package com.retro99.reader.ui.tts

import android.content.Context
import com.retro99.analytics.api.Analytics
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File

@Single
class TtsAudioCache(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
) {

    /** All the file logic lives here; this class only knows where the directory is. */
    internal val store: TtsAudioCacheStore by lazy {
        TtsAudioCacheStore(
            directory = File(context.cacheDir, TtsAudioCacheStore.CACHE_DIR_NAME).apply { mkdirs() },
        )
    }

    fun key(
        voiceId: String,
        modelVersion: String?,
        rate: Float,
        pitch: Float,
        text: String,
    ): String = store.key(
        voiceId = voiceId,
        modelVersion = modelVersion,
        rate = rate,
        pitch = pitch,
        text = text,
    )

    fun fileFor(key: String): File = store.fileFor(key)

    fun get(key: String): File? = store.get(key)

    fun onStored(file: File) = store.onStored(file)

    fun durationMs(file: File): Long? = store.durationMs(file)

    fun clear() = store.clear()

    fun trim() = store.trim()
}
