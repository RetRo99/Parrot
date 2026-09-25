package com.retro99.books.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.FavoritesRepository
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ToggleFavoriteUseCase(
    @Provided private val favoritesRepository: FavoritesRepository,
) {
    suspend operator fun invoke(bookUuid: String): CompletableResult {
        return if (favoritesRepository.isFavorite(bookUuid)) {
            favoritesRepository.removeFromFavorites(bookUuid)
        } else {
            favoritesRepository.addToFavorites(bookUuid)
        }
    }

    suspend fun setFavorite(bookUuid: String, isFavorite: Boolean): CompletableResult {
        return if (isFavorite) {
            favoritesRepository.addToFavorites(bookUuid)
        } else {
            favoritesRepository.removeFromFavorites(bookUuid)
        }
    }
}
