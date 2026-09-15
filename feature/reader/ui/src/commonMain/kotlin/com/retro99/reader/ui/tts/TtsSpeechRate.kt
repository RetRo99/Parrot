package com.retro99.reader.ui.tts

internal object TtsSpeechRate {

    const val MIN = 0.5f
    const val MAX = 2.0f

    fun coerce(rate: Float): Float = rate.coerceIn(MIN, MAX)
}
