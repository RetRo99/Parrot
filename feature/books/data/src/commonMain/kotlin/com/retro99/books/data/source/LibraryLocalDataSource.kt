package com.retro99.books.data.source

import com.github.michaelbull.result.onSuccess
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // One import at a time: the duplicate lookup and the write that follows must not
    // interleave with another import of the same bytes.
    private val importMutex = Mutex()

    override suspend fun addStagedFile(file: ImportedFileCandidate): AppResult<AddedLibraryFile> {
        return importMutex.withLock {
            // Once the file starts moving, finish: a cancellation that lands after the rows
            // are committed must not undo the move and leave them pointing at nothing.
            withContext(NonCancellable) {
                databaseExecutor.executeDatabaseOperation {
                    val matchId = findBookWithContent(file.contentHashAlgorithm, file.contentHash)
                    if (matchId != null) {
                        AddedLibraryFile(attachToExistingBook(matchId, file), isNewBook = false)
                    } else {
                        AddedLibraryFile(createBook(file), isNewBook = true)
                    }
                }.onSuccess {
                    // The staged copy is used up: moved into the library, or a duplicate.
                    // After a failure it stays (or is put back) so the import can be retried.
                    deleteQuietly(file.stagedPath)
                }
            }
        }
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
            // Only copies that came from Parrot Cloud are at risk from its removal. A
            // catalogue download keeps its origin, so it is never backed up automatically.
            deviceFilesDatabase.getDeviceFiles(libraryBookId)
                .filter { file -> file.origin == DeviceFileEntity.ORIGIN_CLOUD_DOWNLOAD }
                .forEach { file ->
                    deviceFilesDatabase.upsertDeviceFile(file.copy(origin = DeviceFileEntity.ORIGIN_IMPORT))
                }
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
        var moved = false
        try {
            fileStore.moveToImportedStore(file.stagedPath, destination)
            moved = true
            deviceFilesDatabase.upsertDeviceFile(file.toDeviceFile(libraryBookId, destination))
        } catch (exception: Exception) {
            undoMove(moved, destination, file.stagedPath)
            throw exception
        }
        return libraryBookId
    }

    private suspend fun createBook(file: ImportedFileCandidate): String {
        val libraryBookId = Uuid.random().toString()
        val destination = fileStore.libraryFilePath(libraryBookId, file.mediaType)
        var coverPath: String? = null
        var moved = false
        try {
            coverPath = file.metadata.coverBytes?.let { bytes ->
                fileStore.writeCover(libraryBookId, bytes)
            }
            fileStore.moveToImportedStore(file.stagedPath, destination)
            moved = true
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
            val deviceFile = file.toDeviceFile(libraryBookId, destination)
            if (file.syncsMetadata) {
                libraryBooksDatabase.insertImportedBook(
                    book = book,
                    file = deviceFile,
                    outboxEntry = SyncOutboxEntry.new(
                        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                        entityId = libraryBookId,
                        operation = SyncOutboxEntry.OPERATION_UPSERT,
                        payload = LibraryBookJsonCodec.encode(book, format = file.mediaType),
                    ),
                )
            } else {
                libraryBooksDatabase.insertBookWithoutSync(book, deviceFile)
            }
        } catch (exception: Exception) {
            undoMove(moved, destination, file.stagedPath)
            coverPath?.let { path -> deleteQuietly(path) }
            throw exception
        }
        return libraryBookId
    }

    /**
     * Leaves no library file without a row. A finished move is put back in staging, so the
     * bytes are there for a retry; a move that failed part-way has its leftovers deleted.
     * No row points at [libraryPath] yet, so nothing else can be using it.
     */
    private suspend fun undoMove(moved: Boolean, libraryPath: String, stagedPath: String) {
        if (!moved) {
            deleteQuietly(libraryPath)
            return
        }
        try {
            fileStore.moveToImportedStore(libraryPath, stagedPath)
        } catch (_: Exception) {
            deleteQuietly(libraryPath)
        }
    }

    /** Cleanup must not turn a finished import into a failure, or hide the real one. */
    private suspend fun deleteQuietly(path: String) {
        try {
            fileStore.delete(path)
        } catch (_: Exception) {
        }
    }

    /**
     * A catalogue download is not pushed to your other devices: they would list a book they
     * have no file for. It syncs once you back the file up.
     */
    private val ImportedFileCandidate.syncsMetadata: Boolean
        get() = origin != DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD

    private fun ImportedFileCandidate.toDeviceFile(libraryBookId: String, path: String) =
        DeviceFileEntity(
            libraryBookId = libraryBookId,
            mediaType = mediaType,
            filePath = path,
            fileSize = fileSize,
            contentHash = contentHash,
            contentHashAlgorithm = contentHashAlgorithm,
            origin = origin,
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
