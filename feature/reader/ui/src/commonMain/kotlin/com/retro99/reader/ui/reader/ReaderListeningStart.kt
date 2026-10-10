package com.retro99.reader.ui.reader

/** Availability describes the page, not whether a deliberate Play can search ahead for text. */
internal class ReaderListeningStart(
    private val enableReadAloud: () -> Unit,
    private val enterListening: () -> Unit,
    private val requestPlayback: () -> Unit,
) {
    fun start(
        source: ListenSource,
        hasNarration: Boolean,
        readAloudAvailable: Boolean,
        voicesLoaded: Boolean,
        readAloudEnabled: Boolean,
        isPlaying: Boolean,
        autoPlay: Boolean,
    ) {
        when (source) {
            ListenSource.NARRATION -> if (!hasNarration) return
            ListenSource.DEVICE_VOICE -> {
                if (!readAloudAvailable && !voicesLoaded) return
                if (!hasNarration && !readAloudEnabled) enableReadAloud()
            }
        }
        enterListening()
        if (autoPlay && !isPlaying) requestPlayback()
    }
}
