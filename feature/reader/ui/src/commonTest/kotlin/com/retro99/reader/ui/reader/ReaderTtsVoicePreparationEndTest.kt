package com.retro99.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TTS-F19: Cancel on the download notification left the sheet showing a pending voice
 * selection that was never going to happen, and run 4 saw a delete during a download leave
 * the card showing a failed download the user never had.
 */
class ReaderTtsVoicePreparationEndTest {

    private val pending = "supertonic-en-f"

    @Test
    fun a_finished_download_selects_the_voice_that_was_waiting_for_it() {
        val decision = resolveTtsVoicePreparationEnd(TtsVoicePreparationEnd.Prepared, pending)

        assertEquals(pending, decision.selectVoiceId)
        assertFalse(decision.showFailedPackage)
    }

    @Test
    fun a_finished_download_nobody_was_waiting_for_selects_nothing() {
        val decision = resolveTtsVoicePreparationEnd(TtsVoicePreparationEnd.Prepared, null)

        assertNull(decision.selectVoiceId)
        assertFalse(decision.clearPendingSelection)
    }

    @Test
    fun a_cancelled_download_abandons_the_pending_selection() {
        val decision = resolveTtsVoicePreparationEnd(TtsVoicePreparationEnd.Cancelled, pending)

        assertTrue(decision.clearPendingSelection, "The sheet keeps a selection that cannot happen")
        assertNull(decision.selectVoiceId)
    }

    @Test
    fun a_failed_download_abandons_the_pending_selection() {
        val decision = resolveTtsVoicePreparationEnd(TtsVoicePreparationEnd.Failed, pending)

        assertTrue(decision.clearPendingSelection, "The sheet keeps a selection that cannot happen")
        assertNull(decision.selectVoiceId)
    }

    @Test
    fun a_failed_download_shows_the_failure_on_the_card() {
        assertTrue(resolveTtsVoicePreparationEnd(TtsVoicePreparationEnd.Failed, null).showFailedPackage)
    }

    /** Deleting a pack mid-download cancels the install; the user asked for that. */
    @Test
    fun a_cancelled_download_is_not_a_failed_one() {
        assertFalse(
            resolveTtsVoicePreparationEnd(TtsVoicePreparationEnd.Cancelled, null).showFailedPackage,
            "A download the user stopped shows as failed",
        )
    }
}
