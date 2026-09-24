package com.retro99.books.data.transfer

import com.github.michaelbull.result.getOrElse
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.EpubMetadataExtractor
import com.retro99.books.data.model.ImportedBookLocalModel
import com.retro99.books.data.model.LibraryBookLocalModel
import com.retro99.books.data.model.LocalBookFileLocalModel
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.model.BookType
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

@Single(binds = [DownloadTransferFinalizer::class])
class DownloadFinalizer(
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val booksDatabase: PositionDatabase,
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
        if (request.libraryBookId != "${request.contentHashAlgorithm}:${request.contentHash}") {
            throw BookFileTransferRejectedException("cloud_file_identity_mismatch")
        }
        val bookType = when (request.mediaType.lowercase()) {
            BookType.EBOOK.value -> BookType.EBOOK
            BookType.READALOUD.value -> BookType.READALOUD
            else -> throw BookFileTransferRejectedException("unsupported_media_type")
        }
        val existing = importedBooksDatabase.getImportedBookByContentHash(request.contentHash)
            ?.takeIf { localBook ->
                localBook.contentHashAlgorithm == request.contentHashAlgorithm &&
                    localBook.bookType.equals(request.mediaType, ignoreCase = true) &&
                    fileStore.exists(localBook.filePath) &&
                    fileStore.size(localBook.filePath) == request.sizeBytes &&
                    fileStore.contentHash(localBook.filePath) == request.contentHash
            }

        val localUuid = existing?.uuid ?: requireNotNull(transfer.localSourceUuid)
        val stagingPath = requireNotNull(transfer.stagingPath)
        val importedFilePath = existing?.filePath ?: fileStore.importedFilePath(localUuid, bookType.value)
        var sourcePath: String? = null
        if (existing == null) {
            sourcePath = when {
                fileStore.exists(stagingPath) && fileStore.size(stagingPath) == request.sizeBytes -> stagingPath
                fileStore.exists(importedFilePath) && fileStore.size(importedFilePath) == request.sizeBytes ->
                    importedFilePath
                else -> error("Downloaded staging file is missing or truncated")
            }
            if (fileStore.contentHash(sourcePath) != request.contentHash) {
                throw DownloadHashMismatchException()
            }
        }

        val existingLibraryBook = libraryBooksDatabase.getLibraryBookByCloudBookId(request.cloudBookId)
            ?: libraryBooksDatabase.getLibraryBookByContentHash(
                request.contentHashAlgorithm,
                request.contentHash,
            )
        val libraryBook = existingLibraryBook?.let { existingBook ->
            if (existingBook.cloudBookId != null && existingBook.cloudBookId != request.cloudBookId) {
                throw BookFileTransferRejectedException("library_book_identity_mismatch")
            }
            LibraryBookLocalModel(
                libraryBookId = existingBook.libraryBookId,
                contentHash = existingBook.contentHash ?: request.contentHash,
                contentHashAlgorithm = existingBook.contentHashAlgorithm ?: request.contentHashAlgorithm,
                title = existingBook.title,
                author = existingBook.author,
                format = existingBook.format,
                remoteRevision = existingBook.remoteRevision,
                deletedAt = existingBook.deletedAt,
                cloudBookId = request.cloudBookId,
                metadataJson = existingBook.metadataJson,
            )
        } ?: LibraryBookLocalModel(
                libraryBookId = request.libraryBookId,
                contentHash = request.contentHash,
                contentHashAlgorithm = request.contentHashAlgorithm,
                title = existing?.title ?: request.fileName.substringBeforeLast('.', request.fileName),
                author = existing?.author,
                format = bookType.value,
                cloudBookId = request.cloudBookId,
            )
        if (libraryBook.libraryBookId != request.libraryBookId) {
            throw BookFileTransferRejectedException("library_book_identity_mismatch")
        }

        val importedBook = existing ?: createImportedBook(
            localUuid = localUuid,
            sourcePath = requireNotNull(sourcePath),
            destinationPath = importedFilePath,
            request = request,
            bookType = bookType,
            libraryBook = libraryBook,
        )
        if (sourcePath != null && sourcePath != importedFilePath) {
            fileStore.moveToImportedStore(sourcePath, importedFilePath)
        }
        val completedTransfer = transfer.copy(
            libraryBookId = libraryBook.libraryBookId,
            cloudBookId = request.cloudBookId,
            cloudBookFileId = request.cloudBookFileId,
            localSourceUuid = localUuid,
            stagingPath = null,
            bytesTransferred = request.sizeBytes,
            state = STATE_COMPLETED,
            nextAttemptAt = null,
            lastError = null,
            updatedAt = Clock.System.now().toString(),
        )
        val canonicalPosition = booksDatabase.getPositionByLibraryBookId(libraryBook.libraryBookId)
        val localPosition = canonicalPosition?.let { position ->
            RestoredPositionEntity(
                source = position,
                bookUuid = localUuid,
                libraryBookId = libraryBook.libraryBookId,
            )
        }
        importedBooksDatabase.saveRestoredBookWithLibraryMapping(
            book = importedBook,
            libraryBook = libraryBook,
            localBookFile = LocalBookFileLocalModel(
                libraryBookId = libraryBook.libraryBookId,
                importedBookUuid = localUuid,
            ),
            transfer = completedTransfer,
            position = localPosition,
        )
        fileStore.delete(stagingPath)
        completedTransfer
    }

    private suspend fun createImportedBook(
        localUuid: String,
        sourcePath: String,
        destinationPath: String,
        request: BookFileDownloadRequest,
        bookType: BookType,
        libraryBook: LibraryBookEntity,
    ): ImportedBookEntity {
        val metadata = metadataExtractor.extractMetadata(sourcePath).getOrElse { error ->
            throw BookFileTransferRejectedException("restored_epub_invalid")
        }
        val coverPath = metadata.coverBytes?.let { bytes -> fileStore.writeCover(localUuid, bytes) }
        return ImportedBookLocalModel(
            uuid = localUuid,
            title = metadata.title.ifBlank { libraryBook.title },
            author = metadata.author ?: libraryBook.author,
            description = metadata.description,
            coverPath = coverPath,
            filePath = destinationPath,
            fileSize = request.sizeBytes,
            contentHash = request.contentHash,
            contentHashAlgorithm = request.contentHashAlgorithm,
            importedAt = Clock.System.now().toString(),
            lastOpenedAt = null,
            bookType = bookType.value,
            publicationDate = metadata.publicationDate,
            origin = ORIGIN_CLOUD_DOWNLOAD,
            cloudBookFileId = request.cloudBookFileId,
        )
    }

    private data class RestoredPositionEntity(
        val source: PositionEntity,
        override val bookUuid: String,
        override val libraryBookId: String,
    ) : PositionEntity {
        override val localGeneration: Long get() = source.localGeneration
        override val remoteRevision: Long? get() = source.remoteRevision
        override val timestamp: Long? get() = source.timestamp
        override val createdAt: String? get() = source.createdAt
        override val updatedAt: String? get() = source.updatedAt
        override val locatorHref: String? get() = source.locatorHref
        override val locatorType: String? get() = source.locatorType
        override val locatorTitle: String? get() = source.locatorTitle
        override val locatorTarget: Int? get() = source.locatorTarget
        override val cssSelector: String? get() = source.cssSelector
        override val audioTimestampMs: Long? get() = source.audioTimestampMs
        override val chapterIndex: Int? get() = source.chapterIndex
        override val progression: Double? get() = source.progression
        override val totalChapters: Int? get() = source.totalChapters
        override val totalDurationMs: Long? get() = source.totalDurationMs
        override val totalProgression: Double? get() = source.totalProgression
        override val position: Int? get() = source.position
    }

    private companion object {
        const val STATE_COMPLETED = "completed"
        const val ORIGIN_CLOUD_DOWNLOAD = "cloud_download"
    }
}

interface DownloadTransferFinalizer {
    suspend fun finalize(
        transfer: CloudFileTransferEntity,
        request: BookFileDownloadRequest,
    ): CloudFileTransferEntity
}

class DownloadHashMismatchException : Exception("Restored file did not match the cloud content hash")
