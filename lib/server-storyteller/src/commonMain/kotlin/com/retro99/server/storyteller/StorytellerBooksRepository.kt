package com.retro99.server.storyteller

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
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.storyteller.model.StorytellerBookApiModel
import com.retro99.server.storyteller.model.StorytellerCurrentUserResponse
import com.retro99.server.storyteller.model.StorytellerServerDetailsResponse
import com.retro99.server.storyteller.model.toDomain
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retro99.network.api.get

/**
 * Storyteller implementation of ServerBooksRepository.
 * Fetches books from a Storyteller server using the v2 API.
 * Uses local caching with cachedRemoteFlow pattern.
 */
class StorytellerBooksRepository(
    private val networkClient: ServerNetworkClient,
    private val localSource: ServerBooksLocalSource,
) : ServerBooksRepository, BaseRepository {

    private val logger = Logger.withTag("čič")

    override val serverId: String = networkClient.serverId
    override val libraryAdapterId = LibraryAdapterId("storyteller")
    private val baseUrl: String? = networkClient.baseUrl

    override suspend fun libraryAccountIdentity(): SourceAccountIdentity.Portable? {
        val serverDetails = networkClient.get<StorytellerServerDetailsResponse>(
            path = SERVER_DETAILS_PATH,
        ).getOrElse { return null }
        val backendId = serverDetails.id.toCanonicalUuid() ?: return null

        val currentUser = networkClient.get<StorytellerCurrentUserResponse>(
            path = CURRENT_USER_PATH,
        ).getOrElse { return null }
        val accountId = currentUser.id.toCanonicalUuid() ?: return null

        return SourceAccountIdentity.Portable(
            backendId = backendId,
            accountId = accountId,
        )
    }

    init {
        logger.d { "StorytellerBooksRepository created for server: $serverId, baseUrl: $baseUrl" }
    }

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> {
        logger.d { "getBooks() called for server: $serverId" }
        return cachedRemoteFlow(
            cacheSource = {
                logger.d { "Fetching from cache for server: $serverId" }
                localSource.getBooks(serverId).mapCatching { books ->
                    logger.d { "Cache returned ${books?.size ?: 0} books" }
                    books?.map { it.withCoverUrl(baseUrl) }
                }
            },
            remoteSource = { fetchRemoteBooks() },
            saveToCache = { books ->
                logger.d { "Saving ${books.size} books to cache" }
                localSource.saveBooks(serverId, books)
            },
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

        fetchRemoteBooks().fold(
            success = { remoteBooks ->
                localSource.saveBooks(serverId, remoteBooks).onFailure { error ->
                    logger.e { "Failed to save the remote library listing: $error" }
                }
                emit(
                    Ok(
                        ServerBookListing(
                            books = remoteBooks,
                            completeness = ServerBookListingCompleteness.Complete,
                        ),
                    ),
                )
            },
            failure = { error ->
                emit(Err(error))
            },
        )
    }

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBook(serverId, uuid).mapCatching { book ->
                    book?.withCoverUrl(baseUrl)
                }
            },
            remoteSource = {
                networkClient.get<StorytellerBookApiModel>(
                    path = "/api/v2/books/$uuid"
                ).map { book ->
                    book.toDomain(serverId, baseUrl)
                }
            },
            saveToCache = { book ->
                localSource.saveBook(serverId, book)
            },
        )
    }

    override suspend fun saveBook(book: ServerBook): CompletableResult {
        // TODO: Implement upload to Storyteller server when API supports it
        return Err(AppError.UnknownError(NotImplementedError("Uploading books to Storyteller is not yet supported")))
    }

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> {
        return networkClient.get<List<StorytellerBookApiModel>>(
            path = "/api/v2/books",
            queryBuilder = {
                "search" to query
            }
        ).map { books ->
            books.map { it.toDomain(serverId, baseUrl) }
        }
    }

    private suspend fun fetchRemoteBooks(): AppResult<List<ServerBook>> {
        logger.d { "Fetching from remote: $baseUrl/api/v2/books" }
        return networkClient.get<List<StorytellerBookApiModel>>(
            path = "/api/v2/books",
        ).onSuccess { books ->
            logger.d { "Remote returned ${books.size} books" }
        }.onFailure { error ->
            logger.e { "Remote fetch failed: $error" }
        }.map { books ->
            books.map { book -> book.toDomain(serverId, baseUrl) }
                .sortedBy { book -> book.title.lowercase() }
        }
    }

    /**
     * Fallback for cached books that were saved before cover_url was persisted.
     */
    private fun ServerBook.withCoverUrl(baseUrl: String?): ServerBook {
        return if (coverUrl == null && baseUrl != null) {
            copy(coverUrl = com.retro99.base.url.CoverUrlBuilder.buildCoverUrl(baseUrl, uuid))
        } else {
            this
        }
    }
}

private const val SERVER_DETAILS_PATH = "/api/v2/server/details"
private const val CURRENT_USER_PATH = "/api/v2/user"
private val STORYTELLER_UUID_PATTERN = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
        "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
)

private fun String?.toCanonicalUuid(): String? =
    this?.takeIf { uuid -> STORYTELLER_UUID_PATTERN.matches(uuid) }?.lowercase()
