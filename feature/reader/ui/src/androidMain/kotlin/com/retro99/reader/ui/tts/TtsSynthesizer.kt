package com.retro99.reader.ui.tts

import java.io.File

enum class TtsSynthesisStatus {
    SUCCESS,
    ERROR,
    TIMEOUT,
    CANCELLED,
}

data class TtsSynthesisResult(
    val status: TtsSynthesisStatus,
    val file: File? = null,
    val error: String? = null,
    val durationMs: Long? = null,
)

interface TtsSynthesizer {

    fun isReady(): Boolean

    suspend fun awaitReady(timeoutMs: Long = 3_000L): Boolean

    fun availableVoices(): List<TtsVoice>

    fun defaultVoice(): TtsVoice?

    suspend fun prepareVoice(
        voiceId: String?,
        updateToLatest: Boolean = false,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean = awaitReady()

    /** Model version the audio cache key is scoped to, or null for stateless voices. */
    fun activeModelVersion(voiceId: String?): String? = null

    /**
     * Loads a downloaded neural engine in the background (speak-word warm-up). Never
     * downloads or installs anything; false when the pack is absent or the voice is not neural.
     */
    suspend fun warmUp(voiceId: String?): Boolean = false

    suspend fun deleteNeuralVoicePackage(
        voicePackage: NeuralVoicePackage,
    ): Boolean = false

    suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
        outputFile: File,
    ): TtsSynthesisResult

    fun stop()

    suspend fun release()
}
