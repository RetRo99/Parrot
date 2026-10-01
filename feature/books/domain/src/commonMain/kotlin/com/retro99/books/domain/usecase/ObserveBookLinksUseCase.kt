package com.retro99.books.domain.usecase

import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ObserveBookLinksUseCase(
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    operator fun invoke(): Flow<List<BookLink>> = bookLinksRepository.observeLinks()
}
