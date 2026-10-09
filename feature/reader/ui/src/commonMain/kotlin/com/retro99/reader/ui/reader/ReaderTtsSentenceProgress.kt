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

    suspend fun onCurrentSentence(sentence: TtsSentence?) {
        if (sentence != null) showSentenceNumber(sentence.index + 1)
    }
}
