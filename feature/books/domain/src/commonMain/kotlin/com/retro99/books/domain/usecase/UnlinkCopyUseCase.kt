package com.retro99.books.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.CopyKey
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** "Not the same book": takes a copy out of its link so it shows as a separate book. */
@Factory
class UnlinkCopyUseCase(
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    suspend operator fun invoke(copy: CopyKey): CompletableResult =
        bookLinksRepository.unlink(copy)
}
