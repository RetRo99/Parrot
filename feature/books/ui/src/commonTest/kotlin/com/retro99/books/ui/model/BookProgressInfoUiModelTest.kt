package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookProgressInfoDomainModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The list row and the book details screen show this percent. It must be the same value the
 * domain model computes, so no surface can round a fraction differently from the others.
 */
class BookProgressInfoUiModelTest {

    @Test
    fun theUiPercentIsTheDomainPercent() {
        val domain = BookProgressInfoDomainModel(
            bookUuid = "book-1",
            localProgression = 0.869,
            remoteProgression = 0.79,
            isEbookCached = true,
            isAudiobookCached = false,
            isReadaloudCached = false,
        )

        val uiModel = domain.toUiModel()

        assertEquals(domain.progressPercent, uiModel.progressPercent)
        assertEquals(domain.localProgressPercent, uiModel.localProgressPercent)
        assertEquals(domain.remoteProgressPercent, uiModel.remoteProgressPercent)
        assertEquals(86, uiModel.progressPercent)
    }
}
