package com.retro99.home.ui.navigation

import com.retro99.books.domain.model.BookType
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderSettingsExposureContextTest {
    @Test
    fun appSettingsEntryUsesReaderSettingsRowAttribution() {
        assertEquals(
            ReaderSettingsExposureContext("app_settings", "reader_settings_row"),
            readerSettingsExposureContext(HomeDestination.AppSettings),
        )
    }

    @Test
    fun readerEntryUsesToolbarAttributionWithoutBookIdentity() {
        assertEquals(
            ReaderSettingsExposureContext("reader", "reader_settings_button"),
            readerSettingsExposureContext(
                HomeDestination.Reader(
                    serverId = "private-server-id",
                    bookUuid = "private-book-id",
                    bookType = BookType.EBOOK,
                ),
            ),
        )
    }

    @Test
    fun restoredRouteUsesBoundedAttribution() {
        assertEquals(
            ReaderSettingsExposureContext("home", "route_restore"),
            readerSettingsExposureContext(null),
        )
    }
}
