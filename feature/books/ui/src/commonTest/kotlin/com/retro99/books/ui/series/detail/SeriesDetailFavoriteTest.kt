package com.retro99.books.ui.series.detail

import com.github.michaelbull.result.Ok
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.books.domain.FavoritesRepository
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.list.favoriteClickAction
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.isFavoritedByGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeriesDetailFavoriteTest {
    @Test
    fun rowReflectsAndClearsFavoritesFromAnyGroupMember() = runTest {
        // Given
        val representativeUuid = "local-copy"
        val otherMemberUuid = "cloud-copy"
        val book = BookUiModel.LocalBook(
            uuid = representativeUuid,
            serverId = "local",
            serverType = ServerType.Local,
            title = "Grouped book",
            description = null,
            coverUrl = null,
            author = "Author",
            filePath = "/books/grouped.epub",
            fileSize = 1L,
            importedAt = "2026-09-25T00:00:00Z",
            lastOpenedAt = null,
            bookType = BookType.EBOOK,
            publicationDate = null,
            groupMemberUuids = listOf(representativeUuid, otherMemberUuid),
        )
        val repository = InMemoryFavoritesRepository(setOf(otherMemberUuid))
        val toggleFavoriteUseCase = ToggleFavoriteUseCase(repository)

        // When
        val isFavorite = book.isFavoritedByGroup(repository.favoriteUuids.value)
        val action = favoriteClickAction(book, repository.favoriteUuids.value)
        action.bookUuids.forEach { bookUuid ->
            toggleFavoriteUseCase.setFavorite(bookUuid, action.isFavorite)
        }

        // Then
        assertTrue(isFavorite)
        assertEquals(listOf(otherMemberUuid), action.bookUuids)
        assertFalse(action.isFavorite)
        assertEquals(emptySet(), repository.favoriteUuids.value)
        assertFalse(book.isFavoritedByGroup(repository.favoriteUuids.value))
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
