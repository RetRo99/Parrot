package com.retro99.books.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingProgressTest {

    @Test
    fun floorsSoABookStaysAtItsWholePercent() {
        assertEquals(86, progressPercentOf(0.869))
        assertEquals(71, progressPercentOf(0.7199))
    }

    @Test
    fun clampsFractionsOutsideTheBook() {
        assertEquals(100, progressPercentOf(1.4))
        assertEquals(0, progressPercentOf(-0.2))
    }

    @Test
    fun aBookWithoutProgressIsAtZero() {
        assertEquals(0, progressPercentOf(null))
    }

    @Test
    fun theModelUsesTheSharedPercentForEveryLine() {
        val progress = BookProgressInfoDomainModel(
            bookUuid = "book-1",
            localProgression = 0.88,
            remoteProgression = 0.79,
            isEbookCached = false,
            isAudiobookCached = false,
            isReadaloudCached = false,
        )

        assertEquals(progressPercentOf(0.88), progress.progressPercent)
        assertEquals(88, progress.localProgressPercent)
        assertEquals(79, progress.remoteProgressPercent)
    }
}
