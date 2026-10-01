package com.retro99.reader.domain.linked

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.sync.domain.ProgressKind

/** "Continue from where you were in another copy?" for the copy being opened (§1.3). */
data class LinkedResumeOffer(
    /** The copy being opened. */
    val target: LinkedCopy,
    /** The copy with the latest real reading. */
    val source: LinkedCopy,
    val sourceKind: ProgressKind,
    /** When that reading happened, as stored. */
    val observedAt: String,
    val translated: TranslatedPosition,
) {
    /** The source reading was listening to audio. */
    val isListening: Boolean get() = sourceKind == ProgressKind.AUDIO

    /** The entry "Stay here" records, so this source reading isn't offered again. */
    val dismissalEntry: String get() = dismissalEntry(target, source, observedAt)
}

fun dismissalEntry(target: LinkedCopy, source: LinkedCopy, observedAt: String): String =
    "${target.key.value}|${source.key.value}|$observedAt"
