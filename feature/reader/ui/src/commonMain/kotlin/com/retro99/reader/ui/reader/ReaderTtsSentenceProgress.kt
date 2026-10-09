package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.TtsSentence

/**
 * What the reader does with each sentence read-aloud reports: the position the Listening
 * sheet shows, and the highlight the page carries.
 *
 * Separate from [ReaderViewModel] so both can be tested; the ViewModel only hands the state
 * update and the page call over.
 */
internal class ReaderTtsSentenceProgress(
    private val showSentenceNumber: (Int?) -> Unit,
    private val clearSentenceHighlight: suspend () -> Unit,
) {

    private var isHighlighted = false

    suspend fun onCurrentSentence(sentence: TtsSentence?) {
        // Reported for a null sentence too: a stop has no position to show (TTS-F24).
        showSentenceNumber(sentence?.let { it.index + 1 })
        if (sentence != null) {
            isHighlighted = true
            return
        }
        // The highlight is drawn per sentence and was never taken off again, so a stop left
        // the last sentence lit on the page.
        if (!isHighlighted) return
        isHighlighted = false
        clearSentenceHighlight()
    }
}
