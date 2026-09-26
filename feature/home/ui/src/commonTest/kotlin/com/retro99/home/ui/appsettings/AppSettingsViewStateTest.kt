package com.retro99.home.ui.appsettings

import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsViewStateTest {
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
