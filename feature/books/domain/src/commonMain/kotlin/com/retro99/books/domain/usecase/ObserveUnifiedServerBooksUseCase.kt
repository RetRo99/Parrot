package com.retro99.books.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.LibraryBookSourceFeed
import com.retro99.books.domain.model.UnifiedServerBook
import com.retro99.books.domain.model.projectUnifiedServerBooks
import com.retro99.library.domain.projection.LibraryGroupProjectionRepository
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ObserveUnifiedServerBooksUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val libraryGroupProjectionRepository: LibraryGroupProjectionRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<UnifiedServerBook>> =
        repositoryProvider.observeBooksRepositories()
            .flatMapLatest { repositories ->
                val sourceFlows: List<Flow<LibraryBookSourceFeed>> =
                    repositories.map { repository ->
                        repository.getBooks()
                            .runningFold(emptyList<ServerBook>()) { previousBooks, result ->
                                result.getOrElse { previousBooks }
                            }
                            .map { books ->
                                LibraryBookSourceFeed(
                                    adapterId = repository.libraryAdapterId,
                                    connectionId = repository.serverId,
                                    books = books,
                                )
                            }
                    }
                val sourceFeeds: Flow<List<LibraryBookSourceFeed>> = if (sourceFlows.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(sourceFlows) { feeds -> feeds.toList() }
                }

                val groupFlows = libraryGroupProjectionRepository.observeActiveGroups()
                combine(sourceFeeds, groupFlows) { feeds, groups ->
                    projectUnifiedServerBooks(groups, feeds)
                }
            }
}
