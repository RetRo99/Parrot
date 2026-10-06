package com.retro99.books.ui.positions

import com.retro99.reader.domain.positions.ApplyDisabledReason
import com.retro99.reader.domain.translate.TranslationFailure
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import resources.translations.positions_no_match
import resources.translations.positions_missing_file

/** Pure state-to-copy mapping: never consult file state when presenting a failure. */
internal fun ApplyDisabledReason.NoTranslation.messageResource(): StringResource = when (cause) {
    is TranslationFailure.MissingFile -> StringRes.positions_missing_file
    TranslationFailure.NoMatch, TranslationFailure.Unknown -> StringRes.positions_no_match
}
