package com.retro99.reader.ui.tts

import kotlin.math.roundToInt

sealed interface TtsPreparationProgress {

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : TtsPreparationProgress {

        val fraction: Float?
            get() = totalBytes?.toProgressFraction(downloadedBytes)

        val percentage: Int?
            get() = fraction?.times(100)?.roundToInt()
    }

    data object Finalizing : TtsPreparationProgress
}

private fun Long.toProgressFraction(processedBytes: Long): Float? {
    if (this <= 0L) return null
    return (processedBytes.toDouble() / this)
        .toFloat()
        .coerceIn(0f, 1f)
}
