package com.retro99.books.data.source

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.model.LibraryBookJsonCodec
import com.retro99.books.data.model.LibraryBookMetadataJson
import com.retro99.books.data.transfer.BookFileTransferFileStore
import com.retro99.books.domain.DeviceLibraryRepository
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [LibraryLocalSource::class, DeviceLibraryRepository::class])
internal class LibraryLocalDataSource(
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val deviceFilesDatabase: DeviceFilesDatabase,
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val databaseExecutor: DatabaseExecutor,
    @Provided private val fileStore: BookFileTransferFileStore,
) : LibraryLocalSource, DeviceLibraryRepository {

    override suspend fun addImportedFile(file: ImportedFileCandidate): AppResult<String> {
        // Whatever happens, the staged copy is gone afterwards: moved into the library,
        // or deleted.
        return databaseExecutor.executeDatabaseOperation {
            val matchId = findBookWithContent(file.contentHashAlgorithm, file.contentHash)
            if (matchId != null) {
                attachToExistingBook(matchId, file)
            } else {
                createBook(file)
            }
        }.also { fileStore.delete(file.stagedPath) }
    }

    override fun observeLibrary(): Flow<List<LibraryBookRecord>> = combine(
        libraryBooksDatabase.observeLibraryBooks(),
        deviceFilesDatabase.observeAllDeviceFiles(),
        cloudFilesDatabase.observeFileStates(),
    ) { books, deviceFiles, parrotFiles ->
        val deviceFilesByBook = deviceFiles.groupBy(DeviceFileEntity::libraryBookId)
        val parrotFilesByBook = parrotFiles.groupBy(CloudBookFileEntity::libraryBookId)
        books.map { book ->
            book.toRecord(
                deviceFiles = deviceFilesByBook[book.libraryBookId].orEmpty(),
                parrotFiles = parrotFilesByBook[book.libraryBookId].orEmpty(),
            )
        }
    }

    override suspend fun getLibraryBook(libraryBookId: String): LibraryBookRecord? {
        val book = libraryBooksDatabase.getLibraryBookById(libraryBookId) ?: return null
        if (book.deletedAt != null) return null
        return book.toRecord(
            deviceFiles = deviceFilesDatabase.getDeviceFiles(libraryBookId),
            parrotFiles = cloudFilesDatabase.getFileStates(libraryBookId),
        )
    }

    override suspend fun deleteBookFromDevice(libraryBookId: String): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            val book = libraryBooksDatabase.getLibraryBookById(libraryBookId)
            val files = deviceFilesDatabase.getDeviceFiles(libraryBookId)
            libraryBooksDatabase.deleteBookFromDevice(libraryBookId)
            // Files go after the rows, so a failed delete leaves an orphan file, never a
            // row pointing at a missing file.
            files.forEach { file -> fileStore.delete(file.filePath) }
            book?.coverPath?.let { coverPath -> fileStore.delete(coverPath) }
            Unit
        }
    }

    override suspend fun keepDeviceFilesAsImports(libraryBookId: String): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            deviceFilesDatabase.setOriginForBook(libraryBookId, DeviceFileEntity.ORIGIN_IMPORT)
        }
    }

    private suspend fun findBookWithContent(algorithm: String, hash: String): String? {
        deviceFilesDatabase.findByContentHash(algorithm, hash)?.let { file ->
            return file.libraryBookId
        }
        cloudFilesDatabase.findFileStateByHash(algorithm, hash)
            ?.libraryBookId
            ?.takeIf { bookId -> libraryBooksDatabase.getLibraryBookById(bookId) != null }
            ?.let { bookId -> return bookId }
        return libraryBooksDatabase.findLibraryBookBySourceHash(algorithm, hash)?.libraryBookId
    }

    private suspend fun attachToExistingBook(libraryBookId: String, file: ImportedFileCandidate): String {
        // Re-importing a file the book already has is a no-op.
        if (deviceFilesDatabase.getDeviceFile(libraryBookId, file.mediaType) != null) {
            return libraryBookId
        }
        val destination = fileStore.libraryFilePath(libraryBookId, file.mediaType)
        fileStore.moveToImportedStore(file.stagedPath, destination)
        deviceFilesDatabase.upsertDeviceFile(file.toDeviceFile(libraryBookId, destination))
        return libraryBookId
    }

    private suspend fun createBook(file: ImportedFileCandidate): String {
        val libraryBookId = Uuid.random().toString()
        val destination = fileStore.libraryFilePath(libraryBookId, file.mediaType)
        val coverPath = file.metadata.coverBytes?.let { bytes ->
            fileStore.writeCover(libraryBookId, bytes)
        }
        fileStore.moveToImportedStore(file.stagedPath, destination)
        val book = LibraryBookEntity(
            libraryBookId = libraryBookId,
            title = file.metadata.title,
            author = file.metadata.author,
            description = file.metadata.description,
            coverPath = coverPath,
            publicationDate = file.metadata.publicationDate,
            sourceContentHash = file.contentHash,
            sourceContentHashAlgorithm = file.contentHashAlgorithm,
            addedAt = now(),
            metadataJson = LibraryBookMetadataJson.encode(isbn = file.metadata.isbn),
        )
        try {
            libraryBooksDatabase.insertImportedBook(
                book = book,
                file = file.toDeviceFile(libraryBookId, destination),
                outboxEntry = SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    entityId = libraryBookId,
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    payload = LibraryBookJsonCodec.encode(book, format = file.mediaType),
                ),
            )
        } catch (exception: Exception) {
            fileStore.delete(destination)
            coverPath?.let { path -> fileStore.delete(path) }
            throw exception
        }
        return libraryBookId
    }

    private fun ImportedFileCandidate.toDeviceFile(libraryBookId: String, path: String) =
        DeviceFileEntity(
            libraryBookId = libraryBookId,
            mediaType = mediaType,
            filePath = path,
            fileSize = fileSize,
            contentHash = contentHash,
            contentHashAlgorithm = contentHashAlgorithm,
            origin = DeviceFileEntity.ORIGIN_IMPORT,
            addedAt = now(),
        )

    private fun LibraryBookEntity.toRecord(
        deviceFiles: List<DeviceFileEntity>,
        parrotFiles: List<CloudBookFileEntity>,
    ) = LibraryBookRecord(
        libraryBookId = libraryBookId,
        title = title,
        author = author,
        description = description,
        coverPath = coverPath,
        publicationDate = publicationDate,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        deviceFiles = deviceFiles,
        parrotFiles = parrotFiles,
        isbn = LibraryBookMetadataJson.isbn(metadataJson),
    )

    private fun now(): String = Clock.System.now().toString()
}
