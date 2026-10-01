package com.retro99.books.data.transfer

import com.github.michaelbull.result.getOrElse
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.EpubMetadata
import com.retro99.books.data.EpubMetadataExtractor
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.model.BookType
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

/**
 * Turns a verified download into a device copy of the book it belongs to. The position,
 * favorites and bookmarks are keyed by the book id already, so nothing else moves.
 */
@Single(binds = [DownloadTransferFinalizer::class])
class DownloadFinalizer(
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val deviceFilesDatabase: DeviceFilesDatabase,
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val fileStore: BookFileTransferFileStore,
) : DownloadTransferFinalizer {
    override suspend fun finalize(
        transfer: CloudFileTransferEntity,
        request: BookFileDownloadRequest,
    ): CloudFileTransferEntity = withContext(Dispatchers.Default) {
        if (request.contentHashAlgorithm != CONTENT_HASH_ALGORITHM) {
            throw BookFileTransferRejectedException("unsupported_hash_algorithm")
        }
        val bookType = when (request.mediaType.lowercase()) {
            BookType.EBOOK.value -> BookType.EBOOK
            BookType.READALOUD.value -> BookType.READALOUD
            else -> throw BookFileTransferRejectedException("unsupported_media_type")
        }
        val bookId = request.libraryBookId
        val existingFile = deviceFilesDatabase.getDeviceFile(bookId, bookType.value)
            ?.takeIf { file ->
                file.contentHash == request.contentHash &&
                    fileStore.exists(file.filePath) &&
                    fileStore.size(file.filePath) == request.sizeBytes &&
                    fileStore.contentHash(file.filePath) == request.contentHash
            }

        val stagingPath = requireNotNull(transfer.stagingPath)
        val destinationPath = existingFile?.filePath
            ?: fileStore.libraryFilePath(bookId, bookType.value)
        var sourcePath: String? = null
        if (existingFile == null) {
            // A retry after a crash may find the file already moved into place.
            sourcePath = when {
                fileStore.exists(stagingPath) && fileStore.size(stagingPath) == request.sizeBytes ->
                    stagingPath
                fileStore.exists(destinationPath) &&
                    fileStore.size(destinationPath) == request.sizeBytes -> destinationPath
                else -> error("Downloaded staging file is missing or truncated")
            }
            if (fileStore.contentHash(sourcePath) != request.contentHash) {
                throw DownloadHashMismatchException()
            }
        }

        saveLibraryBook(bookId, request, readablePath = sourcePath ?: destinationPath)
        if (sourcePath != null && sourcePath != destinationPath) {
            fileStore.moveToImportedStore(sourcePath, destinationPath)
        }
        if (existingFile == null) {
            deviceFilesDatabase.upsertDeviceFile(
                DeviceFileEntity(
                    libraryBookId = bookId,
                    mediaType = bookType.value,
                    filePath = destinationPath,
                    fileSize = request.sizeBytes,
                    contentHash = request.contentHash,
                    contentHashAlgorithm = request.contentHashAlgorithm,
                    origin = DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD,
                    addedAt = now(),
                ),
            )
        }
        val completedTransfer = transfer.copy(
            cloudBookFileId = request.cloudBookFileId,
            stagingPath = null,
            bytesTransferred = request.sizeBytes,
            state = STATE_COMPLETED,
            nextAttemptAt = null,
            lastError = null,
            updatedAt = now(),
        )
        cloudFilesDatabase.updateTransfer(completedTransfer)
        fileStore.delete(stagingPath)
        completedTransfer
    }

    /**
     * The book row normally arrives by pull. Create it when it hasn't, and fill in the
     * cover and description Parrot Cloud doesn't store yet.
     */
    private suspend fun saveLibraryBook(
        bookId: String,
        request: BookFileDownloadRequest,
        readablePath: String,
    ) {
        val existing = libraryBooksDatabase.getLibraryBookById(bookId)
        if (existing != null && existing.coverPath != null && existing.description != null) return
        val metadata = metadataExtractor.extractMetadata(readablePath).getOrElse {
            if (existing != null) return
            throw BookFileTransferRejectedException("restored_epub_invalid")
        }
        val coverPath = existing?.coverPath
            ?: metadata.coverBytes?.let { bytes -> fileStore.writeCover(bookId, bytes) }
        libraryBooksDatabase.upsertLibraryBook(
            existing?.copy(
                coverPath = coverPath,
                description = existing.description ?: metadata.description,
                publicationDate = existing.publicationDate ?: metadata.publicationDate,
            ) ?: newBook(bookId, request, metadata, coverPath),
        )
    }

    private fun newBook(
        bookId: String,
        request: BookFileDownloadRequest,
        metadata: EpubMetadata,
        coverPath: String?,
    ) = LibraryBookEntity(
        libraryBookId = bookId,
        title = metadata.title.ifBlank { request.fileName.substringBeforeLast('.') },
        author = metadata.author,
        description = metadata.description,
        coverPath = coverPath,
        publicationDate = metadata.publicationDate,
        sourceContentHash = request.contentHash,
        sourceContentHashAlgorithm = request.contentHashAlgorithm,
        addedAt = now(),
    )

    private fun now(): String = Clock.System.now().toString()

    private companion object {
        const val STATE_COMPLETED = "completed"
    }
}

interface DownloadTransferFinalizer {
    suspend fun finalize(
        transfer: CloudFileTransferEntity,
        request: BookFileDownloadRequest,
    ): CloudFileTransferEntity
}

class DownloadHashMismatchException : Exception("Restored file did not match the cloud content hash")
