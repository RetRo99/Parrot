package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.links.CopyKey

sealed interface TranslationFailure {
    data class MissingFile(val version: CopyKey) : TranslationFailure
    data object NoMatch : TranslationFailure
    /** Insufficient timing/position data or unreadable content; downloading may not help. */
    data object Unknown : TranslationFailure
}

sealed interface TranslationOutcome {
    data class Success(val translated: TranslatedPosition) : TranslationOutcome
    data class Failure(val cause: TranslationFailure) : TranslationOutcome
}
