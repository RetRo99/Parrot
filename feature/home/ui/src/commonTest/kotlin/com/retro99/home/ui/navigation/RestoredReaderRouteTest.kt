package com.retro99.home.ui.navigation

import com.retro99.books.domain.model.BookType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoredReaderRouteTest {
    @Test
    fun matchingRestoredReaderMatchesTheCurrentBook() {
        val restored = HomeDestination.Reader(
            serverId = "server",
            bookUuid = "book",
            bookType = BookType.EBOOK,
        )

        assertTrue(restored.isReaderFor("server", "book", BookType.EBOOK))
        assertFalse(restored.isLastBookLaunchReaderFor("server", "book", BookType.EBOOK))
        assertTrue(
            restored.copy(isLastBookOnLaunch = true)
                .isLastBookLaunchReaderFor("server", "book", BookType.EBOOK),
        )
    }

    @Test
    fun differentBookOrDestinationDoesNotSuppressStartupNavigation() {
        val restored = HomeDestination.Reader(
            serverId = "server",
            bookUuid = "book",
            bookType = BookType.EBOOK,
        )
        val noDestination: HomeDestination? = null

        assertFalse(restored.isReaderFor("another-server", "book", BookType.EBOOK))
        assertFalse(restored.isReaderFor("server", "another-book", BookType.EBOOK))
        assertFalse(restored.isReaderFor("server", "book", BookType.AUDIOBOOK))
        assertFalse(HomeDestination.BooksList.isReaderFor("server", "book", BookType.EBOOK))
        assertFalse(noDestination.isReaderFor("server", "book", BookType.EBOOK))
        assertFalse(noDestination.isLastBookLaunchReaderFor("server", "book", BookType.EBOOK))
    }

    @Test
    fun continueReadingAttributionSurvivesReaderRouteCopy() {
        val route = HomeDestination.Reader(
            serverId = "server",
            bookUuid = "book",
            bookType = BookType.READALOUD,
            readerOpenEntryPoint = "floating_bubble",
            readerOpenCorrelationId = "123e4567-e89b-12d3-a456-426614174000",
        )

        val restored = route.copy()

        assertEquals("floating_bubble", restored.readerOpenEntryPoint)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", restored.readerOpenCorrelationId)
        assertTrue(restored.isReaderFor("server", "book", BookType.READALOUD))
    }
}
