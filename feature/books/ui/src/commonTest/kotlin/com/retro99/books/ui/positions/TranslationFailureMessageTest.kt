package com.retro99.books.ui.positions

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.reader.domain.positions.ApplyDisabledReason
import com.retro99.reader.domain.translate.TranslationFailure
import com.retro99.translations.StringRes
import resources.translations.positions_missing_file
import resources.translations.positions_no_match
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationFailureMessageTest {
    @Test
    fun `only an explicit missing file recommends downloading`() {
        val missing = TranslationFailure.MissingFile(CopyKey(CopySource.Storyteller, "missing"))
        assertEquals(StringRes.positions_missing_file, ApplyDisabledReason.NoTranslation(missing).messageResource())
        assertEquals(StringRes.positions_no_match, ApplyDisabledReason.NoTranslation(TranslationFailure.NoMatch).messageResource())
        assertEquals(StringRes.positions_no_match, ApplyDisabledReason.NoTranslation(TranslationFailure.Unknown).messageResource())
    }
}
