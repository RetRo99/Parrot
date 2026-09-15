package com.retro99.reader.ui.tts

internal const val KOKORO_VOICE_PREFIX = "kokoro:"
internal const val KOKORO_DOWNLOAD_SIZE_BYTES = 103_248_205L
internal const val SUPERTONIC_VOICE_PREFIX = "supertonic:"
internal const val SUPERTONIC_DOWNLOAD_SIZE_BYTES = 128_774_318L

enum class NeuralVoicePackage {
    KOKORO,
    SUPERTONIC,
}

internal fun String?.isKokoroVoice(): Boolean =
    this?.startsWith(KOKORO_VOICE_PREFIX) == true

internal fun String?.isSupertonicVoice(): Boolean =
    this?.startsWith(SUPERTONIC_VOICE_PREFIX) == true

fun String?.neuralVoicePackage(): NeuralVoicePackage? = when {
    isKokoroVoice() -> NeuralVoicePackage.KOKORO
    isSupertonicVoice() -> NeuralVoicePackage.SUPERTONIC
    else -> null
}

data class TtsVoice(
    val id: String,
    val name: String,
    val locale: String,
    val quality: Int = 0,
    val latency: Int = 0,
    val requiresNetwork: Boolean = false,
    val isNeural: Boolean = false,
    val downloadSizeBytes: Long? = null,
    val isDownloaded: Boolean = true,
) {
    val isHighQuality: Boolean
        get() = quality >= 400 || isNeural

    val needsDownload: Boolean
        get() = downloadSizeBytes != null && !isDownloaded

    val neuralVoicePackage: NeuralVoicePackage?
        get() = id.neuralVoicePackage()
}
