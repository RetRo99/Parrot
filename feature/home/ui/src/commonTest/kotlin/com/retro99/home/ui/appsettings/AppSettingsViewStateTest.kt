package com.retro99.home.ui.appsettings

import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsViewStateTest {
    @Test
    fun preferenceSaveFailureIncrementsSnackbarTriggerWithoutChangingSettings() {
        val initial = AppSettingsViewState(openLastBookOnLaunch = true)

        val failure = initial.withAppSettingSaveFailure()

        assertEquals(1, failure.appSettingSaveFailureCount)
        assertTrue(failure.openLastBookOnLaunch)
        assertEquals(2, failure.withAppSettingSaveFailure().appSettingSaveFailureCount)
    }

    @Test
    fun currentBookVisibilityReflectsPresentEmptyAndClearedValues() {
        val initial = AppSettingsViewState()
        assertFalse(initial.hasCurrentlyReadingBook)

        val withBook = initial.withCurrentlyReading(
            CurrentlyReadingDomainModel(
                serverId = "local",
                bookUuid = "fixture",
                bookType = BookType.EBOOK,
                bookTitle = "Fixture",
                coverUrl = null,
                totalProgression = 0.25,
            ),
        )
        assertTrue(withBook.hasCurrentlyReadingBook)

        val afterClear = withBook.withCurrentlyReading(null)
        assertFalse(afterClear.hasCurrentlyReadingBook)
    }
}
