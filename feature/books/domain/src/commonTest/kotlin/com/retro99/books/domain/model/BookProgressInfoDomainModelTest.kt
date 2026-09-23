package com.retro99.books.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookProgressInfoDomainModelTest {
    @Test
    fun exactProgressionDifferenceIsAConflictWithoutAOnePercentThreshold() {
        val progress = BookProgressInfoDomainModel(
            bookUuid = "book-1",
            localProgression = 0.5000,
            remoteProgression = 0.5001,
            isEbookCached = false,
            isAudiobookCached = false,
            isReadaloudCached = false,
        )

        assertTrue(progress.hasConflict)
    }

    @Test
    fun identicalProgressionIsNotAConflict() {
        val progress = BookProgressInfoDomainModel(
            bookUuid = "book-1",
            localProgression = 0.5,
            remoteProgression = 0.5,
            isEbookCached = false,
            isAudiobookCached = false,
            isReadaloudCached = false,
        )

        assertFalse(progress.hasConflict)
    }
}
