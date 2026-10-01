package com.retro99.server.local

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.source.LibraryBookRecord
import com.retro99.books.data.source.LibraryLocalSource
import com.retro99.server.api.MediaResource
import com.retro99.server.api.ParrotCloudLibraryState
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Your library: books with a copy on this device and/or in Parrot Cloud, one entry per
 * book. The book id is the entry's uuid.
 */
class LocalBooksRepository(
    private val localSource: LibraryLocalSource,
    private val parrotCloudLibraryState: ParrotCloudLibraryState,
    override val serverId: String,
) : ServerBooksRepository {

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> = observeLibrary().map { books ->
        Ok(books)
    }

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = observeLibrary().map { books ->
        books.firstOrNull { book -> book.uuid == uuid }
            ?.let { book -> Ok(book) }
            ?: Err(AppError.NotFoundError("Book not found: $uuid"))
    }

    override suspend fun saveBook(book: ServerBook): CompletableResult {
        // Books are added through the import flow, not through this method.
        return Err(AppError.UnknownError(NotImplementedError("Use importEpubFile to add books")))
    }

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> {
        val books = observeLibrary().first()
        return Ok(
            books.filter { book ->
                book.title.contains(query, ignoreCase = true) ||
                    book.authors.any { author -> author.contains(query, ignoreCase = true) }
            },
        )
    }

    private fun observeLibrary(): Flow<List<ServerBook>> = combine(
        localSource.observeLibrary(),
        parrotCloudLibraryState.observeIsActive(),
    ) { records, parrotActive ->
        records.mapNotNull { record ->
            record.toLibraryServerBook(serverId = serverId, parrotActive = parrotActive)
        }
    }
}

private val VISIBLE_PARROT_STATES = setOf(
    RemoteFileAvailability.Available,
    RemoteFileAvailability.UploadPending,
    RemoteFileAvailability.Uploading,
)

/**
 * One [MediaResource] per media type that has a device file or a Parrot file. Returns null
 * when the book isn't listed: it has no device copy and no Parrot copy that is available
 * or on its way.
 */
internal fun LibraryBookRecord.toLibraryServerBook(
    serverId: String,
    parrotActive: Boolean,
): ServerBook? {
    val deviceByType = deviceFiles.associateBy { file -> file.mediaType }
    val parrotByType = if (parrotActive) {
        parrotFiles.filter { file -> file.relativePath.isEmpty() }
            .associateBy { file -> file.mediaType }
    } else {
        emptyMap()
    }
    val resources = (deviceByType.keys + parrotByType.keys).map { mediaType ->
        val device = deviceByType[mediaType]
        val parrot = parrotByType[mediaType]
        MediaResource(
            mediaType = mediaType,
            localPath = device?.filePath,
            remoteAvailability = parrot?.status
                ?.let(RemoteFileAvailability::fromFileStatus)
                ?: RemoteFileAvailability.None,
            size = device?.fileSize ?: parrot?.sizeBytes,
            contentHash = device?.contentHash ?: parrot?.contentHash,
            contentHashAlgorithm = device?.contentHashAlgorithm ?: parrot?.contentHashAlgorithm,
            localOrigin = device?.origin,
            cloudBookFileId = parrot?.cloudBookFileId,
        )
    }
    val visible = resources.any { resource ->
        resource.localPath != null || resource.remoteAvailability in VISIBLE_PARROT_STATES
    }
    if (!visible) return null
    fun path(type: String) = resources.firstOrNull { resource -> resource.mediaType == type }
    return ServerBook(
        uuid = libraryBookId,
        serverId = serverId,
        title = title,
        description = description,
        coverUrl = coverPath?.let { path -> "file://$path" },
        authors = listOfNotNull(author),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = path(EBOOK) != null,
        hasAudiobook = path(AUDIOBOOK) != null,
        hasReadaloud = path(READALOUD) != null,
        ebookFilepath = path(EBOOK)?.localPath,
        audiobookFilepath = path(AUDIOBOOK)?.localPath,
        readaloudFilepath = path(READALOUD)?.localPath,
        ebookFileSize = path(EBOOK)?.size,
        audiobookFileSize = path(AUDIOBOOK)?.size,
        readaloudFileSize = path(READALOUD)?.size,
        createdAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        publicationDate = publicationDate,
        isLocal = true,
        serverType = ServerType.Local,
        libraryBookId = libraryBookId,
        mediaResources = resources,
    )
}

private const val EBOOK = "ebook"
private const val AUDIOBOOK = "audiobook"
private const val READALOUD = "readaloud"
