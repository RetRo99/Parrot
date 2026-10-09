package com.retro99.reader.ui.reader

/** How a voice preparation finished. */
internal enum class TtsVoicePreparationEnd {
    /** The pack is installed and the voice can be used. */
    Prepared,

    /** The install reported a failure of its own. */
    Failed,

    /**
     * Somebody stopped waiting: Cancel in the Voices sheet, Cancel on the download
     * notification (the holder goes `Idle`, which cancels the job), or a delete of the pack
     * being installed.
     */
    Cancelled,
}

/** What becomes of the Voices sheet when a preparation ends. */
internal data class TtsVoicePreparationEndDecision(
    /** The voice to select now that its pack is there. */
    val selectVoiceId: String? = null,
    /** "Selecting this once it is downloaded" goes away; it is not going to happen. */
    val clearPendingSelection: Boolean = false,
    /** The card shows a failed download, with Retry and Cancel. */
    val showFailedPackage: Boolean = false,
)

/**
 * The one decision about a pending voice selection when its download ends.
 *
 * Tapping a voice that is not downloaded queues "select it once prepared"
 * (`pendingTtsVoiceId`). Cancel in the sheet cleared that; Cancel on the download
 * notification did not, so the sheet kept a selection that would never happen, and a
 * failure left the same leftover (TTS-F19). A cancellation the user asked for is not a
 * failed download either, which is what made the card show "failed" after the user deleted
 * a pack mid-download.
 *
 * Separate from [ReaderViewModel] so it can be tested, in the pattern of [ReaderTtsSetup].
 */
internal fun resolveTtsVoicePreparationEnd(
    end: TtsVoicePreparationEnd,
    pendingVoiceId: String?,
): TtsVoicePreparationEndDecision = when (end) {
    TtsVoicePreparationEnd.Prepared ->
        TtsVoicePreparationEndDecision(selectVoiceId = pendingVoiceId)
    // Today's behaviour, so the tests can be seen failing: a failure keeps the pending
    // selection, and a cancellation leaves the sheet exactly as it was.
    TtsVoicePreparationEnd.Failed ->
        TtsVoicePreparationEndDecision(showFailedPackage = true)
    TtsVoicePreparationEnd.Cancelled ->
        TtsVoicePreparationEndDecision()
}
