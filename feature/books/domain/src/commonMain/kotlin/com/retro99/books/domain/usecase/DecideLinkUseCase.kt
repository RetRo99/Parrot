package com.retro99.books.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecisionType
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** Records the user's answer to a suggestion: "not the same book" or "skip". */
@Factory
class DecideLinkUseCase(
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    suspend operator fun invoke(
        first: CopyKey,
        second: CopyKey,
        decision: LinkDecisionType,
    ): CompletableResult = bookLinksRepository.decide(first, second, decision)
}
