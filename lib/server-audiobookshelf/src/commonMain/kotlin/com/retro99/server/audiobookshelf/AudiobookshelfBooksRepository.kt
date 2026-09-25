package com.retro99.server.audiobookshelf

import co.touchlab.kermit.Logger
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.base.repository.BaseRepository
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.result.mapCatching
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookListing
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibrarySourceIdentityPairing
import com.retro99.server.api.library.LibrarySourceIdentityPairingException
import com.retro99.server.api.library.LibrarySourceIdentityPairingFailure
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemsResponse
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryListApiModel
import com.retro99.server.audiobookshelf.model.toDomain
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import retro99.network.api.get

class AudiobookshelfBooksRepository(
    private val networkClient: ServerNetworkClient,
    private val localSource: ServerBooksLocalSource,
    private val identityBindingStore: AudiobookshelfLibraryIdentityBindingProvider? = null,
) : ServerBooksRepository, BaseRepository, LibrarySourceIdentityPairing {

    private val logger = Logger.withTag("AudiobookshelfBooksRepository")

    override val serverId: String = networkClient.serverId
    override val libraryAdapterId = LibraryAdapterId("audiobookshelf")
    private val baseUrl: String? = networkClient.baseUrl

    /**
     * Audiobookshelf login provides a user ID but no durable server-instance ID. Since user IDs
     * can repeat across installations, neither value alone establishes a portable identity.
     */
    override suspend fun libraryAccountIdentity(): SourceAccountIdentity.Portable? =
        identityBindingStore?.currentIdentity(serverId)

    override fun observePairingStatus() = identityBindingStore?.observePairingStatus(serverId)
        ?: flowOf(LibrarySourceIdentityPairingStatus.Unpaired)

    override suspend fun createPairingCode(): String = identityBindingStore
        ?.createPairingCode(serverId)
        ?: throw LibrarySourceIdentityPairingException(
            LibrarySourceIdentityPairingFailure.ServerUnavailable,
        )

    override suspend fun importPairingCode(code: String) {
        val store = identityBindingStore
            ?: throw LibrarySourceIdentityPairingException(
                LibrarySourceIdentityPairingFailure.ServerUnavailable,
            )
        store.importPairingCode(serverId, code)
    }

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBooks(serverId).mapCatching { books ->
                    books?.map { book -> book.withCoverUrl(baseUrl) }
                }
            },
            remoteSource = {
                fetchAllLibrariesListing().map { listing ->
                    listing.books
                }.onSuccess { books ->
                    logger.d { "Fetched ${books.size} books from $serverId" }
                }.onFailure { error ->
                    logger.e { "Remote fetch failed: $error" }
                }
            },
            saveToCache = { books -> localSource.saveBooks(serverId, books) },
        )
    }

    override fun getLibraryListing(): Flow<AppResult<ServerBookListing>> = flow {
        val cachedBooks = localSource.getBooks(serverId)
            .mapCatching { books -> books?.map { book -> book.withCoverUrl(baseUrl) } }
            .getOrElse { error ->
                logger.e { "Cached library listing failed: $error" }
                null
            }
        if (cachedBooks != null) {
            emit(
                Ok(
                    ServerBookListing(
                        books = cachedBooks,
                        completeness = ServerBookListingCompleteness.Partial,
                    ),
                ),
            )
        }

        val remoteListing = fetchAllLibrariesListing().getOrElse { failure ->
            emit(Err(failure))
            return@flow
        }
        localSource.saveBooks(serverId, remoteListing.books).onFailure { error ->
            logger.e { "Failed to save the remote library listing: $error" }
        }
        emit(Ok(remoteListing))
    }

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBook(serverId, uuid).mapCatching { book ->
                    book?.withCoverUrl(baseUrl)
                }
            },
            remoteSource = {
                networkClient.get<AudiobookshelfLibraryItemApiModel>(
                    path = "/api/items/$uuid",
                ).map { item -> item.toDomain(serverId, baseUrl) }
            },
            saveToCache = { book -> localSource.saveBook(serverId, book) },
        )
    }

    override suspend fun saveBook(book: ServerBook): CompletableResult {
        return Err(
            AppError.UnknownError(
                NotImplementedError("Uploading books to Audiobookshelf is not yet supported"),
            ),
        )
    }

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> {
        return fetchAllLibrariesItems(query = query).map { items ->
            items.map { item -> item.toDomain(serverId, baseUrl) }
        }
    }

    private suspend fun fetchAllLibrariesListing(): AppResult<ServerBookListing> {
        val libraries = networkClient.get<AudiobookshelfLibraryListApiModel>(
            path = "/api/libraries",
        ).getOrElse { failure -> return Err(failure) }.libraries
        val books = mutableListOf<ServerBook>()
        var completeness = ServerBookListingCompleteness.Complete
        for (library in libraries) {
            val responseResult = networkClient.get<AudiobookshelfLibraryItemsResponse>(
                path = "/api/libraries/${library.id}/items",
                queryBuilder = {
                    "limit" to "0"
                },
            )
            val response = responseResult.getOrElse { failure -> return Err(failure) }
            if (response.results.size != response.total) {
                completeness = ServerBookListingCompleteness.Partial
            }

            val presentItems = response.results.filterNot { item -> item.isMissing == true }
            for (batch in presentItems.chunked(ITEM_DETAIL_BATCH_SIZE)) {
                val hydratedItems = coroutineScope {
                    batch.map { item ->
                        async { hydrateListingItem(item) }
                    }.awaitAll()
                }
                hydratedItems.forEach { hydrated ->
                    if (hydrated.item.isMissing == true) return@forEach
                    if (hydrated.isPartial) {
                        completeness = ServerBookListingCompleteness.Partial
                    }
                    books += hydrated.item.toDomain(serverId, baseUrl)
                }
            }
        }
        return Ok(
            ServerBookListing(
                books = books.sortedBy { book -> book.title.lowercase() },
                completeness = completeness,
            ),
        )
    }

    private suspend fun hydrateListingItem(
        summary: AudiobookshelfLibraryItemApiModel,
    ): HydratedLibraryItem {
        if (!summary.needsFileDetails()) return HydratedLibraryItem(summary, isPartial = false)

        return networkClient.get<AudiobookshelfLibraryItemApiModel>(
            path = "/api/items/${summary.id}",
        ).fold(
            success = { details ->
                val item = if (details.media == null) summary else details
                HydratedLibraryItem(
                    item = item,
                    isPartial = item.missesExpectedFileDetails(summary),
                )
            },
            failure = { error ->
                logger.w {
                    "Failed to fetch file details for Audiobookshelf item ${summary.id}: $error"
                }
                HydratedLibraryItem(summary, isPartial = true)
            },
        )
    }

    private suspend fun fetchAllLibrariesItems(
        query: String? = null,
    ): AppResult<List<AudiobookshelfLibraryItemApiModel>> {
        val libraries = networkClient.get<AudiobookshelfLibraryListApiModel>(
            path = "/api/libraries",
        ).getOrElse { failure -> return Err(failure) }.libraries
        val items = mutableListOf<AudiobookshelfLibraryItemApiModel>()
        for (library in libraries) {
            val response = networkClient.get<AudiobookshelfLibraryItemsResponse>(
                path = "/api/libraries/${library.id}/items",
                queryBuilder = {
                    "limit" to "0"
                    if (query != null) "search" to query
                },
            ).getOrElse { failure -> return Err(failure) }
            items += response.results.filterNot { item -> item.isMissing == true }
        }
        return Ok(items)
    }

    private fun ServerBook.withCoverUrl(baseUrl: String?): ServerBook {
        return if (coverUrl == null && baseUrl != null) {
            copy(coverUrl = "${baseUrl.trimEnd('/')}/api/items/$uuid/cover")
        } else {
            this
        }
    }

    private companion object {
        const val ITEM_DETAIL_BATCH_SIZE = 4
    }
}

private data class HydratedLibraryItem(
    val item: AudiobookshelfLibraryItemApiModel,
    val isPartial: Boolean,
)

private fun AudiobookshelfLibraryItemApiModel.needsFileDetails(): Boolean =
    missesExpectedFileDetails(this)

private fun AudiobookshelfLibraryItemApiModel.missesExpectedFileDetails(
    summary: AudiobookshelfLibraryItemApiModel,
): Boolean {
    val summaryMedia = summary.media ?: return false
    val detailMedia = media

    val expectsEbook = summaryMedia.ebookFile != null || summaryMedia.ebookFileFormat != null
    val missingEbook = expectsEbook && detailMedia?.ebookFile?.ino == null
    val expectedAudioCount = summaryMedia.numAudioFiles ?: summaryMedia.audioFiles.size
    val availableAudioCount = detailMedia?.audioFiles?.count { audioFile ->
        audioFile.ino != null
    } ?: 0
    val hasAudioWithoutFileId = detailMedia?.audioFiles?.any { audioFile ->
        audioFile.ino == null
    } == true

    return missingEbook || expectedAudioCount > availableAudioCount || hasAudioWithoutFileId
}
