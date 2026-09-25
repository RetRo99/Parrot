package com.retro99.books.ui.list

import com.retro99.books.ui.model.BookUiModel

internal data class FavoriteClickAction(
    val bookUuids: List<String>,
    val isFavorite: Boolean,
)

internal fun favoriteClickAction(
    book: BookUiModel,
    favoriteBookUuids: Set<String>,
): FavoriteClickAction {
    val currentGroupFavorites = book.groupMemberUuids
        .distinct()
        .filter { bookUuid -> bookUuid in favoriteBookUuids }

    return if (currentGroupFavorites.isNotEmpty()) {
        FavoriteClickAction(bookUuids = currentGroupFavorites, isFavorite = false)
    } else {
        FavoriteClickAction(bookUuids = listOf(book.uuid), isFavorite = true)
    }
}
