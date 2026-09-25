package com.retro99.home.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HomeNavigationStateHolderTest {
    @Test
    fun replaceCurrentDestinationDoesNotAddABackStackEntry() {
        // Given
        val books = HomeDestination.BooksList
        val legacyDetail = HomeDestination.BookDetail("server-1", "book-1")
        val groupDetail = HomeDestination.LibraryGroupDetail("group-1")
        val backStack = mutableListOf(books, legacyDetail).toMutableStateList()
        val stateHolder = stateHolder(backStack)

        // When
        val replaced = stateHolder.replaceCurrentDestination(legacyDetail, groupDetail)

        // Then
        assertTrue(replaced)
        assertEquals(listOf(books, groupDetail), backStack.toList())
        assertTrue(stateHolder.goBack())
        assertEquals(books, stateHolder.currentDestination)
    }

    @Test
    fun replaceRetiredGroupRouteWithCanonicalGroupWithoutAddingABackStackEntry() {
        // Given
        val books = HomeDestination.BooksList
        val retiredGroup = HomeDestination.LibraryGroupDetail("retired-group")
        val canonicalGroup = HomeDestination.LibraryGroupDetail("canonical-group")
        val backStack = mutableListOf(books, retiredGroup).toMutableStateList()
        val stateHolder = stateHolder(backStack)

        // When
        val replaced = stateHolder.replaceCurrentDestination(retiredGroup, canonicalGroup)

        // Then
        assertTrue(replaced)
        assertEquals(listOf(books, canonicalGroup), backStack.toList())
        assertTrue(stateHolder.goBack())
        assertEquals(books, stateHolder.currentDestination)
    }

    @Test
    fun replaceCurrentDestinationIgnoresAStaleLegacyRoute() {
        // Given
        val books = HomeDestination.BooksList
        val legacyDetail = HomeDestination.BookDetail("server-1", "book-1")
        val currentGroupDetail = HomeDestination.LibraryGroupDetail("group-2")
        val backStack = mutableListOf(books, currentGroupDetail).toMutableStateList()
        val stateHolder = stateHolder(backStack)

        // When
        val replaced = stateHolder.replaceCurrentDestination(
            expected = legacyDetail,
            replacement = HomeDestination.LibraryGroupDetail("group-1"),
        )

        // Then
        assertFalse(replaced)
        assertEquals(listOf(books, currentGroupDetail), backStack.toList())
    }

    @Test
    fun readerRouteRestoresTheExactResourceAndProgressOwner() {
        // Given
        val progressOwner = ProgressOwnerRef(
            adapterId = LibraryAdapterId("parrot-cloud"),
            source = SourceBookRef(
                key = SourceBookKey(
                    profileId = LibraryProfileId("profile-17"),
                    adapterId = LibraryAdapterId("parrot-cloud"),
                    accountIdentity = SourceAccountIdentity.Portable(
                        backendId = "parrot-cloud",
                        accountId = "account-3",
                    ),
                    nativeBookId = NativeBookId("native-cloud-book"),
                ),
                connectionId = SourceConnectionId("cloud-connection"),
            ),
            nativeProgressId = "native-progress-book",
        )
        val destination = HomeDestination.Reader(
            serverId = "cloud-connection",
            bookUuid = "native-cloud-book",
            bookType = com.retro99.books.domain.model.BookType.EBOOK,
            selection = HomeDestination.ReaderLaunchSelection(
                resourceId = "native-resource",
                resourceRevision = "revision-3",
                localStorageReference = "/device/books/book.epub",
                progressAdapterId = "parrot-cloud",
                progressNativeId = "native-progress-book",
                progressOwner = progressOwner,
            ),
        )

        // When
        val encoded = Json.encodeToString<HomeDestination>(destination)
        val restored = Json.decodeFromString<HomeDestination>(encoded)

        // Then
        assertEquals(destination, restored)
    }

    @Test
    fun readerReplacementKeepsSelectionWhenNowPlayingTargetsTheCurrentBook() {
        // Given
        val books = HomeDestination.BooksList
        val selection = HomeDestination.ReaderLaunchSelection(
            resourceId = "abs-audio-01",
            resourceRevision = null,
            localStorageReference = "/device/ebooks/book_audiobook",
            progressAdapterId = "audiobookshelf",
            progressNativeId = "abs-book-1",
        )
        val reader = HomeDestination.Reader(
            serverId = "abs-connection",
            bookUuid = "abs-book-1",
            bookType = com.retro99.books.domain.model.BookType.AUDIOBOOK,
            selection = selection,
        )
        val backStack = mutableListOf(books, reader).toMutableStateList()
        val stateHolder = stateHolder(backStack)

        // When
        stateHolder.navigateToReaderReplacing(
            destination = reader.copy(selection = null),
            tab = HomeTab.Books,
        )

        // Then
        assertEquals(listOf(books, reader), backStack.toList())
        assertEquals(selection, (stateHolder.currentDestination as HomeDestination.Reader).selection)
    }

    @Test
    fun readerReplacementReplacesTheCurrentBookWhenIdentityChanges() {
        // Given
        val books = HomeDestination.BooksList
        val currentReader = HomeDestination.Reader(
            serverId = "abs-connection",
            bookUuid = "abs-book-1",
            bookType = com.retro99.books.domain.model.BookType.AUDIOBOOK,
        )
        val nextReader = HomeDestination.Reader(
            serverId = "abs-connection",
            bookUuid = "abs-book-2",
            bookType = com.retro99.books.domain.model.BookType.AUDIOBOOK,
        )
        val backStack = mutableListOf(books, currentReader).toMutableStateList()
        val stateHolder = stateHolder(backStack)

        // When
        stateHolder.navigateToReaderReplacing(nextReader, HomeTab.Books)

        // Then
        assertEquals(listOf(books, nextReader), backStack.toList())
    }

    private fun stateHolder(
        backStack: androidx.compose.runtime.snapshots.SnapshotStateList<HomeDestination>,
    ) = HomeNavigationStateHolder(
        startTab = HomeTab.Books,
        currentTabState = mutableStateOf(HomeTab.Books),
        backStacks = mapOf(HomeTab.Books to backStack),
    )
}
