package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookMemberProgressDomainModel
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.domain.model.UnifiedBookProgressSource
import com.retro99.books.domain.model.UnifiedServerBook
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.books.domain.usecase.ObserveUnifiedServerBooksUseCase
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.library.SourceBookKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Combined use case that observes all books with their progress information.
 *
 * Combines book fetching from all servers with reactive progress observation.
 * Returns a list of [BookWithProgressDomainModel] that updates when:
 * - Books are added/removed from servers
 * - Local reading progress changes
 */
@Factory
class ObserveAllBooksWithProgressUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
    @Provided private val positionLocalSource: ServerPositionLocalSource,
    @Provided private val observeUnifiedServerBooksUseCase: ObserveUnifiedServerBooksUseCase,
) {
    private val remotePositionCache = mutableMapOf<ProgressCacheKey, ServerPosition?>()

    // Trigger to force re-evaluation when remote progress is fetched
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Observes all books with their progress information.
     *
     * @return Flow of books with progress, sorted by title
     */
    operator fun invoke(): Flow<AppResult<List<BookWithProgressDomainModel>>> {
        return combine(
            observeUnifiedServerBooksUseCase(),
            positionLocalSource.observeAllPositions(),
            refreshTrigger,
        ) { unifiedBooks, localPositions, _ ->
            buildBooksWithProgress(unifiedBooks, localPositions)
        }
    }

    /**
     * Fetches remote progress for all books.
     * Should be called once when the book list is loaded or refreshed.
     * Triggers a re-emission of the flow after fetching.
     */
    suspend fun fetchRemoteProgress(books: List<BookWithProgressDomainModel>) {
        if (books.isEmpty()) return

        val progressSources = books.flatMap { bookWithProgress ->
            bookWithProgress.memberProgress
        }.distinctBy { member ->
            progressCacheKey(member.sourceKey, member.serverId, member.bookUuid)
        }

        coroutineScope {
            progressSources.mapNotNull { member ->
                val serverId = member.serverId ?: return@mapNotNull null
                if (serverId.isBlank()) return@mapNotNull null
                async {
                    val cacheKey = progressCacheKey(
                        member.sourceKey,
                        serverId,
                        member.bookUuid,
                    )
                    val readerRepo = repositoryProvider.getReaderRepository(serverId)
                    val remotePosition = readerRepo?.getRemotePosition(member.bookUuid)
                        ?.getOrElse { null }
                    remotePositionCache[cacheKey] = remotePosition
                }
            }.awaitAll()
        }

        // Trigger re-emission of the flow with updated remote progress
        refreshTrigger.value++
    }

    /**
     * Clears the remote progress cache.
     */
    fun clearCache() {
        remotePositionCache.clear()
    }

    private suspend fun buildBooksWithProgress(
        books: List<UnifiedServerBook>,
        localPositions: List<ServerPosition>,
    ): AppResult<List<BookWithProgressDomainModel>> {
        val positionsByLibraryBookId = latestPositionsByLibraryBookId(localPositions)
        val booksWithProgress = books.map { unifiedBook ->
            val serverBook = unifiedBook.book
            val progressSources = unifiedBook.progressSources.ifEmpty {
                listOf(
                    UnifiedBookProgressSource(
                        sourceKey = null,
                        serverId = serverBook.serverId,
                        bookUuid = serverBook.uuid,
                        libraryBookId = serverBook.libraryBookId,
                        mediaTypes = unifiedBook.mediaTypes,
                    ),
                )
            }
            val ambiguousBookUuids = progressSources
                .groupBy { source -> source.bookUuid }
                .filterValues { sources ->
                    sources.map { source -> source.sourceKey to source.serverId }
                        .distinct()
                        .size > 1
                }
                .keys
            val ambiguousSourceScopes = progressSources
                .groupBy { source -> source.bookUuid to source.serverId }
                .filterValues { sources ->
                    sources.map { source -> source.sourceKey }.distinct().size > 1
                }
                .keys
            val memberProgress = progressSources.map { source ->
                createMemberProgress(
                    source = source,
                    groupMediaTypes = unifiedBook.mediaTypes,
                    localPositions = localPositions,
                    positionsByLibraryBookId = positionsByLibraryBookId,
                    ambiguousBookUuids = ambiguousBookUuids,
                    ambiguousSourceScopes = ambiguousSourceScopes,
                )
            }
            val progressInfo = projectReadingListProgress(
                fallbackBookUuid = serverBook.uuid,
                preferredMediaSourceKeys = unifiedBook.preferredMediaSourceKeys,
                memberProgress = memberProgress,
            )

            BookWithProgressDomainModel(
                book = unifiedBook.toBookDomainModel(),
                progressInfo = progressInfo,
                memberProgress = memberProgress,
            )
        }

        return Ok(booksWithProgress.sortedBy { it.book.title.lowercase() })
    }

    private suspend fun createMemberProgress(
        source: UnifiedBookProgressSource,
        groupMediaTypes: Set<String>,
        localPositions: List<ServerPosition>,
        positionsByLibraryBookId: Map<String, ServerPosition>,
        ambiguousBookUuids: Set<String>,
        ambiguousSourceScopes: Set<Pair<String, String?>>,
    ): BookMemberProgressDomainModel {
        val mediaTypes = source.mediaTypes.ifEmpty { groupMediaTypes }
            .map { mediaType -> mediaType.lowercase() }
            .filter { mediaType -> BookType.entries.any { type -> type.value == mediaType } }
            .toSet()
        val progressSource = source.copy(mediaTypes = mediaTypes)
        val localPositionsByMediaType = mediaTypes.associateWith { mediaType ->
            resolveMemberLocalPosition(
                source = progressSource,
                positions = localPositions,
                positionsByLibraryBookId = positionsByLibraryBookId,
                ambiguousBookUuids = ambiguousBookUuids,
                ambiguousSourceScopes = ambiguousSourceScopes,
                mediaType = mediaType,
            )
        }
        val remotePosition = source.serverId?.let { serverId ->
            remotePositionCache[
                progressCacheKey(source.sourceKey, serverId, source.bookUuid),
            ]
        }
        val progressInfoByMediaType = mediaTypes.associateWith { mediaType ->
            val compatibleLocalPosition = localPositionsByMediaType[mediaType]
            val compatibleRemotePosition = remotePosition
                ?.takeIf { position -> position.supportsMediaType(mediaType, mediaTypes) }
            createProgressInfo(
                source = source,
                mediaType = mediaType,
                localPosition = compatibleLocalPosition,
                remotePosition = compatibleRemotePosition,
            )
        }
        val savedPositionUpdatedAtByMediaType = mediaTypes.associateWith { mediaType ->
            listOfNotNull(
                localPositionsByMediaType[mediaType],
                remotePosition?.takeIf { position ->
                    position.supportsMediaType(mediaType, mediaTypes)
                },
            ).maxOfOrNull { position -> position.savedPositionUpdatedAt().orEmpty() }
                ?.takeIf { updatedAt -> updatedAt.isNotEmpty() }
        }

        return BookMemberProgressDomainModel(
            sourceKey = source.sourceKey,
            serverId = source.serverId,
            bookUuid = source.bookUuid,
            mediaTypes = mediaTypes,
            progressInfoByMediaType = progressInfoByMediaType,
            savedPositionUpdatedAtByMediaType = savedPositionUpdatedAtByMediaType,
        )
    }

    private suspend fun createProgressInfo(
        source: UnifiedBookProgressSource,
        mediaType: String,
        localPosition: ServerPosition?,
        remotePosition: ServerPosition?,
    ): BookProgressInfoDomainModel? {
        val bookType = BookType.entries.firstOrNull { type -> type.value == mediaType }
            ?: return null
        val isCached = readerSettingsRepository.isEbookCached(source.bookUuid, bookType)
        val localProgression = localPosition?.totalProgression
        val remoteProgression = remotePosition?.totalProgression

        return if (localProgression != null || remoteProgression != null || isCached) {
            BookProgressInfoDomainModel(
                bookUuid = source.bookUuid,
                localProgression = localProgression,
                remoteProgression = remoteProgression,
                isEbookCached = mediaType == BookType.EBOOK.value && isCached,
                isAudiobookCached = mediaType == BookType.AUDIOBOOK.value && isCached,
                isReadaloudCached = mediaType == BookType.READALOUD.value && isCached,
            )
        } else {
            null
        }
    }

    private fun progressCacheKey(
        sourceKey: SourceBookKey?,
        serverId: String?,
        bookUuid: String,
    ): ProgressCacheKey = ProgressCacheKey(sourceKey, serverId, bookUuid)
}

private data class ProgressCacheKey(
    val sourceKey: SourceBookKey?,
    val serverId: String?,
    val bookUuid: String,
)

internal fun ServerPosition.supportsMediaType(
    mediaType: String,
    sourceMediaTypes: Set<String>,
): Boolean {
    if (mediaType !in sourceMediaTypes) return false
    val hasAudioPosition = audioTimestampMs != null || totalDurationMs != null
    val hasEbookLocation = locatorHref != null || locatorType != null ||
        locatorTarget != null || cssSelector != null || position != null

    return when {
        hasAudioPosition && hasEbookLocation -> mediaType == BookType.READALOUD.value
        hasAudioPosition -> if (BookType.AUDIOBOOK.value in sourceMediaTypes) {
            mediaType == BookType.AUDIOBOOK.value
        } else {
            mediaType == BookType.READALOUD.value
        }
        hasEbookLocation -> mediaType == BookType.EBOOK.value ||
            mediaType == BookType.READALOUD.value
        sourceMediaTypes.size == 1 -> sourceMediaTypes.single() == mediaType
        else -> false
    }
}

private fun ServerPosition.savedPositionUpdatedAt(): String? =
    updatedAt ?: createdAt ?: timestamp?.toString()
