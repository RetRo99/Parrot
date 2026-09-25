package com.retro99.books.ui.list

import com.github.michaelbull.result.Ok
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.books.domain.FavoritesRepository
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.model.BookFilterState
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.SeriesUiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BooksListUnifiedLibraryTest {
    @Test
    fun mergeActionRequiresAtLeastTwoSelectedRowsAndNoPendingResolution() {
        // Given
        val noSelection = BooksListViewState(isMergeSelectionMode = true)
        val oneSelection = noSelection.copy(selectedMergeGroupIds = setOf("group-one"))
        val twoSelections = noSelection.copy(
            selectedMergeGroupIds = setOf("group-one", "group-two"),
        )

        // Then
        assertTrue(!noSelection.canMergeSelectedGroups)
        assertTrue(!oneSelection.canMergeSelectedGroups)
        assertTrue(twoSelections.canMergeSelectedGroups)
        assertTrue(
            !twoSelections.copy(isResolvingMergeSelection = true).canMergeSelectedGroups,
        )
    }

    @Test
    fun searchAndFiltersMatchAnyGroupedSourceMetadata() {
        // Given
        val book = BookUiModel.StorytellerBook(
            uuid = "storyteller-book",
            serverId = "storyteller-1",
            serverType = ServerType.Storyteller,
            title = "Stable title",
            description = null,
            coverUrl = null,
            subtitle = null,
            authors = listOf("Second source author"),
            series = listOf(SeriesUiModel("series", "Shared series", 1.0)),
            tags = listOf("second-source-tag"),
            statusName = null,
            rating = null,
            publicationDate = null,
            dateAdded = null,
            hasEbook = true,
            hasAudiobook = true,
            hasReadaloud = false,
            ebookFilepath = null,
            audiobookFilepath = null,
            readaloudFilepath = null,
            unifiedGroupId = "group-one",
            groupMemberUuids = listOf("storyteller-book", "cloud-book"),
            alternateTitles = listOf("Second source title"),
            groupServerTypes = setOf("storyteller", "audiobookshelf"),
            groupMediaTypes = setOf("ebook", "audiobook"),
        )
        val viewState = BooksListViewState(
            books = listOf(book),
            searchQuery = "Second source title",
            filterState = BookFilterState(
                activeQuickFilters = setOf(BookQuickFilter.HAS_AUDIOBOOK),
                serverTypeFilter = ServerType.Audiobookshelf,
            ),
        )

        // When
        val filteredBooks = viewState.filteredBooks

        // Then
        assertEquals(listOf(book), filteredBooks)
        assertTrue(viewState.showServerBadge)
    }

    @Test
    fun nonRepresentativeFavoriteIsUnfavoritedWithoutFavoritingRepresentative() =
        runTest {
            // Given
            val representativeUuid = "storyteller-book"
            val otherMemberUuid = "cloud-book"
            val book = BookUiModel.StorytellerBook(
                uuid = representativeUuid,
                serverId = "storyteller-1",
                serverType = ServerType.Storyteller,
                title = "Grouped book",
                description = null,
                coverUrl = null,
                subtitle = null,
                authors = emptyList(),
                series = emptyList(),
                tags = emptyList(),
                statusName = null,
                rating = null,
                publicationDate = null,
                dateAdded = null,
                hasEbook = true,
                hasAudiobook = false,
                hasReadaloud = false,
                ebookFilepath = null,
                audiobookFilepath = null,
                readaloudFilepath = null,
                unifiedGroupId = "group-one",
                groupMemberUuids = listOf(representativeUuid, otherMemberUuid),
            )
            val repository = InMemoryFavoritesRepository(setOf(otherMemberUuid))
            val toggleFavoriteUseCase = ToggleFavoriteUseCase(repository)

            // When
            val action = favoriteClickAction(book, repository.favoriteUuids.value)
            action.bookUuids.forEach { bookUuid ->
                toggleFavoriteUseCase.setFavorite(bookUuid, action.isFavorite)
            }

            // Then
            assertEquals(listOf(otherMemberUuid), action.bookUuids)
            assertFalse(action.isFavorite)
            assertEquals(emptySet(), repository.favoriteUuids.value)
            assertFalse(representativeUuid in repository.favoriteUuids.value)
        }
}

private class InMemoryFavoritesRepository(initialFavorites: Set<String>) : FavoritesRepository {
    val favoriteUuids = MutableStateFlow(initialFavorites)

    override suspend fun addToFavorites(bookUuid: String): CompletableResult {
        favoriteUuids.value = favoriteUuids.value + bookUuid
        return Ok(Unit)
    }

    override suspend fun removeFromFavorites(bookUuid: String): CompletableResult {
        favoriteUuids.value = favoriteUuids.value - bookUuid
        return Ok(Unit)
    }

    override fun observeIsFavorite(bookUuid: String): Flow<Boolean> =
        favoriteUuids.map { favoriteUuids -> bookUuid in favoriteUuids }

    override suspend fun isFavorite(bookUuid: String): Boolean =
        bookUuid in favoriteUuids.value

    override fun observeAllFavoriteUuids(): Flow<Set<String>> = favoriteUuids
}
