package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.sync.domain.ProgressKind

/** A position in one copy translated into another copy (§1.5). */
data class TranslatedPosition(
    val target: CopyKey,
    /** href/progression/totalProgression/cssSelector for ebooks, audio ms for audio. */
    val position: PositionDomainModel,
    val kind: ProgressKind,
    val confidence: TranslationConfidence,
    val strategy: TranslationStrategy,
)

enum class TranslationConfidence {
    /** The same file content: the locator is copied unchanged. */
    Exact,

    /** An anchor found uniquely, or SMIL timing. */
    High,

    /** Percentages. Shown as "about"; never written to another copy automatically. */
    Approximate,
    ;

    val isReliable: Boolean get() = this == Exact || this == High
}

enum class TranslationStrategy {
    SameFile,
    TextAnchor,
    SmilBridge,
    Proportional,
}
