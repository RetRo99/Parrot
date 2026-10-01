package com.retro99.books.ui.detail

import com.retro99.reader.domain.linked.LinkedResumeOffer

/** What opening a book from its detail screen asks first (§1.3). */
internal enum class BookDetailOpenPrompt {
    /** A newer reading in a linked copy; it already weighs this copy's own positions. */
    LinkedResume,

    /** This copy's local and server positions differ. */
    SameCopyConflict,

    /** Nothing to ask: open the reader. */
    None,
}

internal fun bookDetailOpenPrompt(
    linkedResumeOffer: LinkedResumeOffer?,
    hasSameCopyConflict: Boolean,
): BookDetailOpenPrompt = when {
    linkedResumeOffer != null -> BookDetailOpenPrompt.LinkedResume
    hasSameCopyConflict -> BookDetailOpenPrompt.SameCopyConflict
    else -> BookDetailOpenPrompt.None
}
