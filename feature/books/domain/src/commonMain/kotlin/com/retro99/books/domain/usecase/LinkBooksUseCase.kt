package com.retro99.books.domain.usecase

import com.retro99.base.result.AppResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** Says two copies on different sources are the same book. */
@Factory
class LinkBooksUseCase(
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    suspend operator fun invoke(first: CopyKey, second: CopyKey): AppResult<BookLink> =
        bookLinksRepository.link(first, second)
}
