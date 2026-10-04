package com.retro99.reader.ui.tts

data class TtsSentence(
    val index: Int,
    val elementId: String?,
    val text: String,
    val startOffset: Int? = null,
    val rawText: String? = null,
)
