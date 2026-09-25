package com.retro99.server.local

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.map
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.source.ImportedBooksLocalSource
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.MediaResource
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookListing
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.LibraryAdapterId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local implementation of ServerBooksRepository.
 * Reads books from the local imported books database.
 */
class LocalBooksRepository(
    private val localSource: ImportedBooksLocalSource,
    override val serverId: String,
) : ServerBooksRepository {

    override val libraryAdapterId = LibraryAdapterId("local")

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> {
        return localSource.observeAllImportedBooks().map { books ->
            Ok(books.map { it.toServerBook(serverId) })
        }
    }

    override fun getLibraryListing(): Flow<AppResult<ServerBookListing>> =
        getBooks().map { result ->
            result.map { books ->
                ServerBookListing(
                    books = books,
                    completeness = ServerBookListingCompleteness.Complete,
                )
            }
        }

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> {
        return localSource.observeAllImportedBooks().map { books ->
            val book = books.find { it.uuid == uuid }
            if (book != null) {
                Ok(book.toServerBook(serverId))
            } else {
                Err(AppError.NotFoundError("Book not found: $uuid"))
            }
        }
    }

    override suspend fun saveBook(book: ServerBook): CompletableResult {
        // Local books are saved through the import flow, not through this method
        return Err(AppError.UnknownError(NotImplementedError("Use importEpubFile to add local books")))
    }

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> {
        // Search is handled at the UI layer for local books
        return Ok(emptyList())
    }
}

/**
 * Convert a LocalBook to ServerBook for unified handling.
 */
private fun BookDomainModel.LocalBook.toServerBook(serverId: String): ServerBook {
    val isEbook = bookType == com.retro99.books.domain.model.BookType.EBOOK
    val isReadaloud = bookType == com.retro99.books.domain.model.BookType.READALOUD
    val normalizedHashAlgorithm = contentHashAlgorithm
    val canonicalHash = if (normalizedHashAlgorithm == LocalContentIdentity.HASH_ALGORITHM) {
        LocalContentIdentity.canonicalHash(contentHash)
    } else {
        null
    }

    return ServerBook(
        uuid = uuid,
        serverId = serverId,
        title = title,
        description = description,
        coverUrl = coverUrl,
        authors = listOfNotNull(author),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = isEbook,
        hasAudiobook = false,
        hasReadaloud = isReadaloud,
        // File paths - set based on book type
        ebookFilepath = if (isEbook) filePath else null,
        audiobookFilepath = null,
        readaloudFilepath = if (isReadaloud) filePath else null,
        // File sizes - set based on book type
        ebookFileSize = if (isEbook) fileSize else null,
        audiobookFileSize = null,
        readaloudFileSize = if (isReadaloud) fileSize else null,
        // Timestamps
        createdAt = importedAt,
        lastOpenedAt = lastOpenedAt,
        publicationDate = publicationDate,
        isLocal = true,
        serverType = ServerType.Local,
        libraryBookId = canonicalHash?.let { hash ->
            "${LocalContentIdentity.HASH_ALGORITHM}:$hash"
        },
        contentHash = contentHash,
        contentHashAlgorithm = normalizedHashAlgorithm,
        mediaResources = listOf(
            MediaResource(
                mediaType = bookType.value,
                localPath = filePath,
                size = fileSize,
                contentHash = contentHash,
                contentHashAlgorithm = normalizedHashAlgorithm,
                localOrigin = origin,
                cloudBookFileId = cloudBookFileId,
                nativeResourceId = uuid,
                format = bookType.value,
            ),
        ),
    )
}
