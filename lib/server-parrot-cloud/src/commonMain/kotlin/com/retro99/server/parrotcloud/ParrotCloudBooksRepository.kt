package com.retro99.server.parrotcloud

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookListing
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Provided

class ParrotCloudBooksRepository(
    private val serverConfig: ServerConfig,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val cloudProfileLinkRepository: CloudProfileLinkRepository,
    @Provided private val userRegistry: UserRegistry,
) : ServerBooksRepository {
    private val json = Json {
        encodeDefaults = true
    }
    private val bookRemovalService = ParrotCloudBookRemovalService(
        libraryBooksDatabase = libraryBooksDatabase,
        syncOutboxDatabase = syncOutboxDatabase,
        cloudFilesDatabase = cloudFilesDatabase,
    )

    override val serverId: String = serverConfig.id
    override val libraryAdapterId = LibraryAdapterId("parrot-cloud")

    override suspend fun libraryAccountIdentity(): SourceAccountIdentity.Portable? {
        val cloudUserId = cloudProfileLinkRepository.getForLocalProfile(
            userRegistry.getActiveProfileIdOrDefault(),
        )?.cloudUserId ?: return null
        return SourceAccountIdentity.Portable(
            backendId = PARROT_CLOUD_BACKEND_ID,
            accountId = cloudUserId,
        )
    }

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> {
        return combine(
            libraryBooksDatabase.getAllLibraryBooks(),
            cloudFilesDatabase.observeFileStates(),
            importedBooksDatabase.getAllImportedBooks(),
        ) { books, fileStates, importedBooks ->
            Ok(
                books.filter { book -> book.cloudBookId != null }
                    .map { book ->
                        val localBook = importedBooks.firstOrNull { imported ->
                            imported.contentHash != null &&
                                "${imported.contentHashAlgorithm ?: "sha-256-v1"}:${imported.contentHash}" ==
                                book.libraryBookId
                        }
                        book.toServerBook(
                            serverId = serverId,
                            localBook = localBook,
                            fileStates = fileStates.filter { state ->
                                state.libraryBookId == book.libraryBookId
                            },
                        )
                    }
                    .sortedBy { book -> book.title.lowercase() },
            )
        }
    }

    override fun getLibraryListing(): Flow<AppResult<ServerBookListing>> {
        return combine(
            getBooks(),
            libraryBooksDatabase.observeDeletedCloudBookIds(),
        ) { result, removedCloudBookIds ->
            result.map { books ->
                ServerBookListing(
                    books = books,
                    completeness = ServerBookListingCompleteness.Partial,
                    removedNativeBookIds = removedCloudBookIds.map { cloudBookId ->
                        NativeBookId(cloudBookId)
                    }.toSet(),
                )
            }
        }
    }

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> {
        return getBooks().map { result ->
            result.andThen { books ->
                val book = books.firstOrNull { candidate ->
                    candidate.uuid == uuid || candidate.libraryBookId == uuid
                }
                if (book == null) {
                    Err(AppError.NotFoundError("Cloud book not found: $uuid"))
                } else {
                    Ok(book)
                }
            }
        }
    }

    override suspend fun saveBook(book: ServerBook): CompletableResult {
        return try {
            val contentHash = book.contentHash
                ?: return Err(AppError.UnknownError(IllegalArgumentException("Cloud books require a content hash")))
            val contentHashAlgorithm = book.contentHashAlgorithm ?: "sha-256-v1"
            val existing = book.libraryBookId?.let { id ->
                libraryBooksDatabase.getLibraryBookById(id)
            }
            val libraryBookId = book.libraryBookId ?: "${contentHashAlgorithm}:$contentHash"
            val cloudBookId = if (book.serverType == com.retro99.base.server.ServerType.ParrotCloud) {
                book.uuid
            } else {
                existing?.cloudBookId
            }
            val entity = ParrotCloudLibraryBookEntity(
                libraryBookId = libraryBookId,
                contentHash = contentHash,
                contentHashAlgorithm = contentHashAlgorithm,
                title = book.title,
                author = book.authors.firstOrNull(),
                format = when {
                    book.hasAudiobook -> "audiobook"
                    book.hasReadaloud -> "readaloud"
                    else -> "ebook"
                },
                remoteRevision = book.remoteRevision ?: existing?.remoteRevision,
                cloudBookId = cloudBookId,
                metadataJson = existing?.metadataJson,
            )
            libraryBooksDatabase.upsertLibraryBook(entity)
            syncOutboxDatabase.enqueue(
                SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    entityId = libraryBookId,
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    payload = json.encodeToString(
                        ParrotCloudBookPayload(
                            libraryBookId = libraryBookId,
                            cloudBookId = cloudBookId,
                            contentHash = contentHash,
                            contentHashAlgorithm = contentHashAlgorithm,
                            title = book.title,
                            author = book.authors.firstOrNull(),
                            format = entity.format,
                            metadataJson = entity.metadataJson,
                            remoteRevision = entity.remoteRevision,
                        ),
                    ),
                ),
            )
            Ok(Unit)
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    /** Queue whole-book removal after every remote file has been removed. */
    suspend fun deleteCloudBook(libraryBookId: String): CompletableResult =
        bookRemovalService.deleteCloudBook(libraryBookId)

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> {
        return getBooks().first().map { books ->
            books.filter { book ->
                book.title.contains(query, ignoreCase = true) ||
                    book.authors.any { author -> author.contains(query, ignoreCase = true) }
            }
        }
    }

    private companion object {
        const val PARROT_CLOUD_BACKEND_ID = "parrot-cloud"
        const val DEFAULT_CONTENT_HASH_ALGORITHM = "sha-256-v1"
    }
}
