package com.retro99.books.ui.model

internal fun BookUiModel.isFavoritedByGroup(favoriteBookUuids: Set<String>): Boolean =
    groupMemberUuids.any { bookUuid -> bookUuid in favoriteBookUuids }
