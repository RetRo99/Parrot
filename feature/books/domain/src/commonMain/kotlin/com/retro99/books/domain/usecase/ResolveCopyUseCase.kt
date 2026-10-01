package com.retro99.books.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.copyKey
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import kotlinx.coroutines.flow.firstOrNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Finds where a copy can be opened on this device: `(serverId, uuid)` of the first
 * authenticated source of the copy's kind that lists it, or null when none does. A link
 * made on another device may name copies on servers this device doesn't have.
 */
@Factory
class ResolveCopyUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
) {
    suspend operator fun invoke(key: CopyKey): Pair<String, String>? {
        repositoryProvider.getBooksRepositories().forEach { repository ->
            // The first emission is the cached list when there is one.
            val books = repository.getBooks().firstOrNull()?.getOrElse { emptyList() }.orEmpty()
            val match = books.firstOrNull { book -> book.toBookDomainModel().copyKey() == key }
            if (match != null) return match.serverId to match.uuid
        }
        return null
    }
}
