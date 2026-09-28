package com.retro99.home.ui.navigation

import com.retro99.books.domain.model.BookType
import kotlin.test.Test
import kotlin.test.assertEquals

class StatisticsExposureContextTest {

    @Test
    fun appSettingsRowHasSpecificAttribution() {
        assertEquals(
            StatisticsExposureContext("app_settings", "statistics_row"),
            statisticsExposureContext(HomeDestination.AppSettings, tabChanged = false),
        )
    }

    @Test
    fun tabChangeUsesBoundedSourceWithoutEntityIdentity() {
        assertEquals(
            StatisticsExposureContext("book_detail", "bottom_navigation"),
            statisticsExposureContext(
                HomeDestination.BookDetail(
                    serverId = "private-server-id",
                    bookUuid = "private-book-id",
                ),
                tabChanged = true,
            ),
        )
    }

    @Test
    fun restoredRouteHasFallbackAttribution() {
        assertEquals(
            StatisticsExposureContext("home", "route_restore"),
            statisticsExposureContext(null, tabChanged = false),
        )
    }

    @Test
    fun returnFromReaderUsesNavigationBackWithoutBookIdentity() {
        assertEquals(
            StatisticsExposureContext("reader", "navigation_back"),
            statisticsExposureContext(
                HomeDestination.Reader(
                    serverId = "private-server-id",
                    bookUuid = "private-book-id",
                    bookType = BookType.EBOOK,
                ),
                tabChanged = false,
            ),
        )
    }
}
