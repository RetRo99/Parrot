package com.retro99.reader.ui.tts

internal const val KOKORO_VOICE_PREFIX = "kokoro:"
internal const val SUPERTONIC_VOICE_PREFIX = "supertonic:"

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
    val updateAvailable: Boolean = false,
    /** Download size of the pending update, when the manifest publishes one. */
    val updateSizeBytes: Long? = null,
    /** Display name of the region (e.g. "United States"); blank when the voice has none. */
    val regionLabel: String = "",
) {
    val isHighQuality: Boolean
        get() = quality >= 400 || isNeural

    val needsDownload: Boolean
        get() = isNeural && !isDownloaded

    val neuralVoicePackage: NeuralVoicePackage?
        get() = id.neuralVoicePackage()
}
