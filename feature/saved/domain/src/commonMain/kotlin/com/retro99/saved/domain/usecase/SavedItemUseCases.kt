package com.retro99.saved.domain.usecase

import com.retro99.saved.domain.SavedItemsRepository
import com.retro99.saved.domain.model.SavedItem
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Clock

/** One book's items, linked copies included, in book order. */
@Factory
class ObserveBookSavedItemsUseCase(
    @Provided private val repository: SavedItemsRepository,
) {
    operator fun invoke(bookKeys: Set<String>, bookUuids: Set<String>): Flow<List<SavedItem>> =
        repository.observeForBook(bookKeys, bookUuids)
}

/** Every item across books, most recently changed first. */
@Factory
class ObserveAllSavedItemsUseCase(
    @Provided private val repository: SavedItemsRepository,
) {
    operator fun invoke(): Flow<List<SavedItem>> = repository.observeAll()
}

/** Saves edits with a fresh updatedAt, which is what wins across devices. */
@Factory
class SaveSavedItemsUseCase(
    @Provided private val repository: SavedItemsRepository,
) {
    suspend operator fun invoke(vararg items: SavedItem) = invoke(items.toList())

    suspend operator fun invoke(items: List<SavedItem>) {
        if (items.isEmpty()) return
        val now = Clock.System.now()
        repository.save(items.map { item -> item.copy(updatedAt = now) })
    }
}

/** Deletes now; returns the item for Undo. */
@Factory
class DeleteSavedItemUseCase(
    @Provided private val repository: SavedItemsRepository,
) {
    suspend operator fun invoke(id: String): SavedItem? = repository.delete(id)
}

/** Undo of a delete: the item comes back as a newer edit, so it also returns on other devices. */
@Factory
class RestoreSavedItemUseCase(
    @Provided private val repository: SavedItemsRepository,
) {
    suspend operator fun invoke(item: SavedItem) {
        repository.save(listOf(item.copy(updatedAt = Clock.System.now())))
    }
}
