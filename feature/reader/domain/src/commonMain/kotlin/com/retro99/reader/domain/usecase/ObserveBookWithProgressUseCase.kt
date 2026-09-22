package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Combined use case that returns a book with its progress information.
 * Provides reactive updates when local progress changes in the database.
 *
 * Combines book fetching with reactive progress observation in a single use case.
 */
@Factory
class ObserveBookWithProgressUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
    @Provided private val positionLocalSource: ServerPositionLocalSource,
) {
    /**
     * Observes a book with its progress information.
     * The flow emits whenever the local progress changes.
     *
     * @param serverId The ID of the server
     * @param bookUuid The UUID of the book
     * @return Flow of book with progress, or error if book not found
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(serverId: String, bookUuid: String): Flow<AppResult<BookWithProgressDomainModel>> {
        return flow {
            val booksRepository = repositoryProvider.getBooksRepository(serverId)
            if (booksRepository == null) {
                emit(Err(AppError.NotFoundError("Server not found: $serverId")))
                return@flow
            }

            // Combine book data flow with position observation
            booksRepository.getBook(bookUuid).flatMapLatest { bookResult ->
                bookResult.fold(
                    success = { serverBook ->
                        positionLocalSource.observeAllPositions().map { positions ->
                            val localPosition = positions.firstOrNull { position ->
                                position.bookUuid == bookUuid
                            } ?: serverBook.libraryBookId?.let { libraryBookId ->
                                positions.firstOrNull { position ->
                                    position.libraryBookId == libraryBookId
                                }
                            }
                            buildBookWithProgress(serverId, bookUuid, serverBook, localPosition)
                        }
                    },
                    failure = { error ->
                        flowOf(Err(error))
                    }
                )
            }.collect { result ->
                emit(result)
            }
        }
    }

    private suspend fun buildBookWithProgress(
        serverId: String,
        bookUuid: String,
        serverBook: ServerBook,
        localPosition: ServerPosition?,
    ): AppResult<BookWithProgressDomainModel> {
        val readerRepository = repositoryProvider.getReaderRepository(serverId)

        // Fetch remote position (one-time per emission)
        val remoteProgression = readerRepository?.getRemotePosition(bookUuid)
            ?.getOrElse { null }?.totalProgression

        // Check cache status
        val isEbookCached = readerSettingsRepository.isEbookCached(bookUuid, BookType.EBOOK)
        val isAudiobookCached = readerSettingsRepository.isEbookCached(bookUuid, BookType.AUDIOBOOK)
        val isReadaloudCached = readerSettingsRepository.isEbookCached(bookUuid, BookType.READALOUD)

        val progressInfo = createProgressInfo(
            bookUuid = bookUuid,
            localPosition = localPosition,
            remoteProgression = remoteProgression,
            isEbookCached = isEbookCached,
            isAudiobookCached = isAudiobookCached,
            isReadaloudCached = isReadaloudCached,
        )

        return Ok(
            BookWithProgressDomainModel(
                book = serverBook.toBookDomainModel(),
                progressInfo = progressInfo,
            )
        )
    }

    private fun createProgressInfo(
        bookUuid: String,
        localPosition: ServerPosition?,
        remoteProgression: Double?,
        isEbookCached: Boolean,
        isAudiobookCached: Boolean,
        isReadaloudCached: Boolean,
    ): BookProgressInfoDomainModel? {
        val localProgression = localPosition?.totalProgression
        val hasLocalProgress = localProgression != null && localProgression > 0.0
        val hasRemoteProgress = remoteProgression != null && remoteProgression > 0.0
        val hasCached = isEbookCached || isAudiobookCached || isReadaloudCached

        return if (hasLocalProgress || hasRemoteProgress || hasCached) {
            BookProgressInfoDomainModel(
                bookUuid = bookUuid,
                localProgression = localProgression,
                remoteProgression = remoteProgression,
                isEbookCached = isEbookCached,
                isAudiobookCached = isAudiobookCached,
                isReadaloudCached = isReadaloudCached,
            )
        } else {
            null
        }
    }
}
