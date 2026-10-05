package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.PositionUiModel

/** Restoring a locator (including accepting a conflict) isn't a new reading checkpoint. */
internal class RestoredPositionSaveGuard {
    private var restored: PositionUiModel? = null

    fun restored(position: PositionUiModel?) {
        restored = position
    }

    fun shouldSave(position: PositionUiModel, audioTimestampMs: Long?): Boolean {
        val initial = restored ?: return true
        val sameText = initial.href == position.href &&
            initial.progression == position.progression &&
            initial.totalProgression == position.totalProgression &&
            initial.cssSelector == position.cssSelector
        // Null means this surface isn't observing narration (e.g. opening the ebook of a
        // read-aloud copy). It must not erase an existing audio checkpoint on restoration.
        val sameAudio = audioTimestampMs == null ||
            (initial.audioTimestampMs ?: 0L) == audioTimestampMs
        if (sameText && sameAudio) return false
        restored = null
        return true
    }
}
