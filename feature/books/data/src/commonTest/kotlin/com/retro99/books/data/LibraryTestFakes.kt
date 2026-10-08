package com.retro99.books.data

import com.retro99.books.data.transfer.BookFileTransferFileStore
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryImportJournalDatabase
import com.retro99.database.api.library.LibraryImportJournalEntry
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakeLibraryBooksDatabase(
    vararg initialBooks: LibraryBookEntity,
    private val deviceFiles: FakeDeviceFilesDatabase? = null,
) : LibraryBooksDatabase {
    val books = MutableStateFlow(initialBooks.associateBy(LibraryBookEntity::libraryBookId))
    val outbox = mutableListOf<SyncOutboxEntry>()
    val deletedFromDevice = mutableListOf<String>()

    override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        books.value = books.value + (book.libraryBookId to book)
    }

    override fun observeLibraryBooks(): Flow<List<LibraryBookEntity>> =
        books.map { byId -> byId.values.filter { book -> book.deletedAt == null } }

    override suspend fun getLibraryBookById(libraryBookId: String) = books.value[libraryBookId]

    override suspend fun findLibraryBookBySourceHash(algorithm: String, hash: String) =
        books.value.values.firstOrNull { book ->
            book.deletedAt == null &&
                book.sourceContentHashAlgorithm == algorithm &&
                book.sourceContentHash == hash
        }

    override suspend fun countLibraryBooksWithDeviceFiles(): Int = books.value.keys.count { id ->
        deviceFiles?.files?.value?.any { file -> file.libraryBookId == id } == true
    }

    override suspend fun updateLastOpenedAt(libraryBookId: String, lastOpenedAt: String) {
        val book = books.value[libraryBookId] ?: return
        upsertLibraryBook(book.copy(lastOpenedAt = lastOpenedAt))
    }

    override suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    ) {
        upsertLibraryBook(book)
        requireNotNull(deviceFiles) { "No device files database" }.upsertDeviceFile(file)
        outbox += outboxEntry
    }

    override suspend fun deleteBookFromDevice(libraryBookId: String) {
        deletedFromDevice += libraryBookId
        deviceFiles?.files?.value = deviceFiles?.files?.value.orEmpty()
            .filterNot { file -> file.libraryBookId == libraryBookId }
        books.value = books.value - libraryBookId
    }
}

internal class FakeDeviceFilesDatabase(vararg initialFiles: DeviceFileEntity) : DeviceFilesDatabase {
    val files = MutableStateFlow(initialFiles.toList())

    override fun observeAllDeviceFiles(): Flow<List<DeviceFileEntity>> = files

    override suspend fun getDeviceFiles(libraryBookId: String) =
        files.value.filter { file -> file.libraryBookId == libraryBookId }

    override suspend fun getDeviceFile(libraryBookId: String, mediaType: String) =
        files.value.firstOrNull { file ->
            file.libraryBookId == libraryBookId && file.mediaType.equals(mediaType, ignoreCase = true)
        }

    override suspend fun findByContentHash(algorithm: String, hash: String) =
        files.value.firstOrNull { file ->
            file.contentHashAlgorithm == algorithm && file.contentHash == hash
        }

    override suspend fun upsertDeviceFile(file: DeviceFileEntity) {
        files.value = files.value.filterNot { existing ->
            existing.libraryBookId == file.libraryBookId && existing.mediaType == file.mediaType
        } + file
    }

    override suspend fun deleteDeviceFile(libraryBookId: String, mediaType: String) {
        files.value = files.value.filterNot { file ->
            file.libraryBookId == libraryBookId && file.mediaType.equals(mediaType, ignoreCase = true)
        }
    }

    override suspend fun setOriginForBook(libraryBookId: String, origin: String) {
        files.value = files.value.map { file ->
            if (file.libraryBookId == libraryBookId) file.copy(origin = origin) else file
        }
    }
}

internal class FakeLibraryImportJournal : LibraryImportJournalDatabase {
    val entries = mutableListOf<LibraryImportJournalEntry>()

    override suspend fun record(entry: LibraryImportJournalEntry) {
        entries.removeAll { existing -> existing.entryId == entry.entryId }
        entries += entry
    }

    override suspend fun getAll(): List<LibraryImportJournalEntry> = entries.toList()

    override suspend fun clear(entryId: String) {
        entries.removeAll { entry -> entry.entryId == entryId }
    }
}

internal class InMemoryFileStore : BookFileTransferFileStore {
    val files = mutableMapOf<String, ByteArray>()

    override fun stagingPath(transferId: String) = "/staging/$transferId.part"

    override fun libraryFilePath(libraryBookId: String, mediaType: String) =
        "/library/${libraryBookId}_$mediaType.epub"

    override suspend fun exists(path: String) = path in files

    override suspend fun size(path: String) = files[path]?.size?.toLong() ?: 0L

    override fun contentHash(path: String) =
        sha256(files[path] ?: error("No file at $path")).toHexString()

    override suspend fun truncate(path: String) {
        files[path] = byteArrayOf()
    }

    override suspend fun write(path: String, offset: Long, bytes: ByteArray) {
        val current = files[path] ?: byteArrayOf()
        val output = ByteArray(maxOf(current.size.toLong(), offset + bytes.size).toInt())
        current.copyInto(output)
        bytes.copyInto(output, offset.toInt())
        files[path] = output
    }

    override suspend fun moveToImportedStore(stagingPath: String, destinationPath: String) {
        files[destinationPath] = files.remove(stagingPath) ?: error("No staging file")
    }

    override fun coverPath(libraryBookId: String) = "/covers/$libraryBookId.png"

    override suspend fun writeCover(libraryBookId: String, bytes: ByteArray): String {
        val path = coverPath(libraryBookId)
        files[path] = bytes
        return path
    }

    override suspend fun delete(path: String): Boolean = files.remove(path) != null
}

internal fun testDeviceFile(
    libraryBookId: String,
    mediaType: String = "ebook",
    filePath: String = "/library/${libraryBookId}_$mediaType.epub",
    contentHash: String? = "hash",
    fileSize: Long = 512,
    origin: String = DeviceFileEntity.ORIGIN_IMPORT,
) = DeviceFileEntity(
    libraryBookId = libraryBookId,
    mediaType = mediaType,
    filePath = filePath,
    fileSize = fileSize,
    contentHash = contentHash,
    contentHashAlgorithm = contentHash?.let { CONTENT_HASH_ALGORITHM },
    origin = origin,
    addedAt = "2026-10-01T00:00:00Z",
)

internal fun testLibraryBook(
    libraryBookId: String,
    remoteRevision: Long? = 1,
    sourceContentHash: String? = null,
    coverPath: String? = null,
    description: String? = null,
) = LibraryBookEntity(
    libraryBookId = libraryBookId,
    title = "Book",
    coverPath = coverPath,
    description = description,
    sourceContentHash = sourceContentHash,
    sourceContentHashAlgorithm = sourceContentHash?.let { CONTENT_HASH_ALGORITHM },
    addedAt = "2026-10-01T00:00:00Z",
    remoteRevision = remoteRevision,
)

internal class InMemoryCloudFilesDatabase(
    vararg initialFileStates: CloudBookFileEntity,
) : CloudFilesDatabase {
    val fileStates = MutableStateFlow(initialFileStates.toList())
    val transfers = MutableStateFlow(emptyMap<String, CloudFileTransferEntity>())

    override suspend fun upsertFileState(file: CloudBookFileEntity) {
        fileStates.value = fileStates.value.filterNot { existing ->
            existing.libraryBookId == file.libraryBookId &&
                existing.mediaType == file.mediaType &&
                existing.relativePath == file.relativePath
        } + file
    }

    override suspend fun getFileStates(libraryBookId: String) =
        fileStates.value.filter { file -> file.libraryBookId == libraryBookId }

    override suspend fun getFileStateById(cloudBookFileId: String) =
        fileStates.value.firstOrNull { file -> file.cloudBookFileId == cloudBookFileId }

    override suspend fun findFileStateByHash(algorithm: String, hash: String) =
        fileStates.value.firstOrNull { file ->
            file.contentHashAlgorithm == algorithm && file.contentHash == hash
        }

    override fun observeFileStates(): Flow<List<CloudBookFileEntity>> = fileStates

    override suspend fun deleteFileState(libraryBookId: String, mediaType: String, relativePath: String) {
        fileStates.value = fileStates.value.filterNot { file ->
            file.libraryBookId == libraryBookId &&
                file.mediaType == mediaType &&
                file.relativePath == relativePath
        }
    }

    override suspend fun insertTransfer(transfer: CloudFileTransferEntity) {
        transfers.value = transfers.value + (transfer.transferId to transfer)
    }

    override suspend fun getTransfer(transferId: String) = transfers.value[transferId]

    override suspend fun updateTransfer(transfer: CloudFileTransferEntity) = insertTransfer(transfer)

    override suspend fun deleteTransfer(transferId: String) {
        transfers.value = transfers.value - transferId
    }

    override suspend fun getTransfers(serverId: String, states: List<String>) =
        transfers.value.values.filter { transfer ->
            transfer.serverId == serverId && transfer.state in states
        }

    override suspend fun getTransfersForCloudFile(cloudBookFileId: String) =
        transfers.value.values.filter { transfer -> transfer.cloudBookFileId == cloudBookFileId }

    override fun observeTransfers(
        serverId: String,
        libraryBookId: String,
    ): Flow<List<CloudFileTransferEntity>> = transfers.map { byId ->
        byId.values.filter { transfer ->
            transfer.serverId == serverId && transfer.libraryBookId == libraryBookId
        }
    }

    override fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> =
        transfers.map { byId -> byId.values.toList() }

    override fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> =
        transfers.map { byId -> byId.values.toList() }

    override suspend fun clearAllData() {
        fileStates.value = emptyList()
        transfers.value = emptyMap()
    }
}
