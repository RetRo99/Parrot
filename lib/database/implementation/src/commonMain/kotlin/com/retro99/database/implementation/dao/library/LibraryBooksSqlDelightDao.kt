package com.retro99.database.implementation.dao.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Device_files
import com.retro99.database.implementation.Library_books
import com.retro99.database.implementation.dao.links.mergeLibraryCopyLinks
import com.retro99.database.implementation.dao.sync.enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class LibraryBooksSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val database get() = databaseManager.getDatabase()

    suspend fun upsertLibraryBook(book: LibraryBookEntity) = withContext(Dispatchers.IO) {
        database.upsertLibraryBookRow(book)
    }

    fun observeLibraryBooks(): Flow<List<LibraryBookEntity>> =
        database.libraryBookQueries.observeLibraryBooks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { row -> row.toEntity() } }

    suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? =
        withContext(Dispatchers.IO) {
            database.libraryBookQueries.getLibraryBookById(libraryBookId)
                .executeAsOneOrNull()
                ?.toEntity()
        }

    suspend fun findLibraryBookBySourceHash(algorithm: String, hash: String): LibraryBookEntity? =
        withContext(Dispatchers.IO) {
            database.libraryBookQueries.findLibraryBookBySourceHash(algorithm, hash)
                .executeAsOneOrNull()
                ?.toEntity()
        }

    suspend fun countLibraryBooksWithDeviceFiles(): Int = withContext(Dispatchers.IO) {
        database.libraryBookQueries.countLibraryBooksWithDeviceFiles().executeAsOne().toInt()
    }

    suspend fun updateLastOpenedAt(libraryBookId: String, lastOpenedAt: String) =
        withContext(Dispatchers.IO) {
            database.libraryBookQueries.updateLastOpenedAt(lastOpenedAt, libraryBookId)
        }

    suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    ) = withContext(Dispatchers.IO) {
        val database = database
        database.transaction {
            database.upsertLibraryBookRow(book)
            database.upsertDeviceFileRow(file)
            database.syncOutboxQueries.enqueue(outboxEntry)
        }
    }

    suspend fun deleteBookFromDevice(libraryBookId: String) = withContext(Dispatchers.IO) {
        database.deleteBookFromDeviceRows(libraryBookId)
    }

    fun observeAllDeviceFiles(): Flow<List<DeviceFileEntity>> =
        database.deviceFileQueries.observeAllDeviceFiles()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { row -> row.toEntity() } }

    suspend fun getDeviceFiles(libraryBookId: String): List<DeviceFileEntity> =
        withContext(Dispatchers.IO) {
            database.deviceFileQueries.getDeviceFilesForBook(libraryBookId)
                .executeAsList()
                .map { row -> row.toEntity() }
        }

    suspend fun getDeviceFile(libraryBookId: String, mediaType: String): DeviceFileEntity? =
        withContext(Dispatchers.IO) {
            database.deviceFileQueries.getDeviceFile(libraryBookId, mediaType)
                .executeAsOneOrNull()
                ?.toEntity()
        }

    suspend fun findDeviceFileByHash(algorithm: String, hash: String): DeviceFileEntity? =
        withContext(Dispatchers.IO) {
            database.deviceFileQueries.findDeviceFileByHash(algorithm, hash)
                .executeAsOneOrNull()
                ?.toEntity()
        }

    suspend fun upsertDeviceFile(file: DeviceFileEntity) = withContext(Dispatchers.IO) {
        database.upsertDeviceFileRow(file)
    }

    suspend fun deleteDeviceFile(libraryBookId: String, mediaType: String) =
        withContext(Dispatchers.IO) {
            database.deviceFileQueries.deleteDeviceFile(libraryBookId, mediaType)
        }

    suspend fun setOriginForBook(libraryBookId: String, origin: String) =
        withContext(Dispatchers.IO) {
            database.deviceFileQueries.setOriginForBook(origin, libraryBookId)
        }

    suspend fun mergeLibraryBook(fromId: String, intoId: String): List<String> =
        withContext(Dispatchers.IO) {
            database.mergeLibraryBookRows(fromId = fromId, intoId = intoId)
        }
}

/**
 * Applies the merge rules in one transaction. Returns the paths of `fromId` device
 * files that `intoId` already had a copy of, for the caller to delete after commit.
 */
internal fun AppDatabase.mergeLibraryBookRows(fromId: String, intoId: String): List<String> {
    if (fromId == intoId) return emptyList()
    return transactionWithResult {
        // position: the most recently updated row wins.
        val fromPosition = positionQueries.getPositionByBookUuid(fromId).executeAsOneOrNull()
        val intoPosition = positionQueries.getPositionByBookUuid(intoId).executeAsOneOrNull()
        if (fromPosition != null) {
            val fromIsNewer = intoPosition == null ||
                (fromPosition.updated_at ?: "") > (intoPosition.updated_at ?: "")
            if (fromIsNewer) {
                if (intoPosition != null) positionQueries.deletePosition(intoId)
                positionQueries.moveBookPosition(intoId, intoId, fromId)
            } else {
                positionQueries.deletePosition(fromId)
            }
        }
        positionQueries.deleteRemotePosition(fromId)

        favoriteQueries.mergeFavorite(intoId, fromId)
        favoriteQueries.deleteFavorite(fromId)
        savedItemQueries.moveSavedItems(intoUuid = intoId, intoKey = "library:$intoId", fromUuid = fromId)
        readingSessionQueries.moveReadingSessions(intoId, fromId)
        sessionRecapQueries.moveRecaps(intoId, fromId)

        val redundantPaths = mutableListOf<String>()
        val intoMediaTypes = deviceFileQueries.getDeviceFilesForBook(intoId)
            .executeAsList()
            .mapTo(mutableSetOf()) { file -> file.media_type }
        deviceFileQueries.getDeviceFilesForBook(fromId).executeAsList().forEach { file ->
            if (file.media_type in intoMediaTypes) {
                redundantPaths += file.file_path
                deviceFileQueries.deleteDeviceFile(fromId, file.media_type)
            } else {
                deviceFileQueries.moveDeviceFile(intoId, fromId, file.media_type)
            }
        }

        cloudBookFileStateQueries.deleteCloudBookFileStatesForBook(fromId)
        cloudFileTransferQueries.moveNonTerminalTransfers(intoId, fromId)
        cloudFileTransferQueries.deleteTransfersForBook(fromId)

        mergeLibraryCopyLinks(fromId, intoId)
        mergeLinkedCopyWrites(fromId, intoId)

        // Book ids are UUIDs, so a quoted occurrence in a payload can only be the id.
        syncOutboxQueries.getAllMutations().executeAsList().forEach { mutation ->
            val quotedFrom = "\"$fromId\""
            if (mutation.entity_id != fromId && quotedFrom !in mutation.payload) return@forEach
            if (mutation.entity_type == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK &&
                mutation.entity_id == fromId
            ) {
                // The server already has the surviving book; its upsert was the duplicate.
                syncOutboxQueries.deleteMutation(mutation.mutation_id)
                return@forEach
            }
            syncOutboxQueries.rewriteMutation(
                entity_id = if (mutation.entity_id == fromId) intoId else mutation.entity_id,
                payload = mutation.payload.replace(quotedFrom, "\"$intoId\""),
                mutation_id = mutation.mutation_id,
            )
        }

        // The survivor usually came from Parrot Cloud, which stores no cover or description.
        val fromBook = libraryBookQueries.getLibraryBookById(fromId).executeAsOneOrNull()
        val intoBook = libraryBookQueries.getLibraryBookById(intoId).executeAsOneOrNull()
        if (fromBook != null && intoBook != null) {
            upsertLibraryBookRow(
                intoBook.toEntity().copy(
                    coverPath = intoBook.cover_path ?: fromBook.cover_path,
                    description = intoBook.description ?: fromBook.description,
                    publicationDate = intoBook.publication_date ?: fromBook.publication_date,
                    addedAt = minOf(intoBook.added_at, fromBook.added_at),
                    lastOpenedAt = listOfNotNull(intoBook.last_opened_at, fromBook.last_opened_at)
                        .maxOrNull(),
                ),
            )
        }
        libraryBookQueries.deleteLibraryBook(fromId)
        redundantPaths
    }
}

internal fun AppDatabase.deleteBookFromDeviceRows(libraryBookId: String) {
    transaction {
        deviceFileQueries.deleteDeviceFilesForBook(libraryBookId)
        cloudBookFileStateQueries.deleteCloudBookFileStatesForBook(libraryBookId)
        cloudFileTransferQueries.deleteTransfersForBook(libraryBookId)
        libraryBookQueries.deleteLibraryBook(libraryBookId)
        sessionRecapQueries.deleteForRemovedBook(libraryBookId)
        syncOutboxQueries.deletePendingMutationsForEntityAnyUser(
            entity_type = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
            entity_id = libraryBookId,
        )
    }
}

internal fun AppDatabase.upsertLibraryBookRow(book: LibraryBookEntity) {
    libraryBookQueries.upsertLibraryBook(
        library_book_id = book.libraryBookId,
        title = book.title,
        author = book.author,
        description = book.description,
        cover_path = book.coverPath,
        publication_date = book.publicationDate,
        source_content_hash = book.sourceContentHash,
        source_content_hash_algorithm = book.sourceContentHashAlgorithm,
        added_at = book.addedAt,
        last_opened_at = book.lastOpenedAt,
        remote_revision = book.remoteRevision,
        deleted_at = book.deletedAt,
        metadata_json = book.metadataJson,
    )
    // A position saved before its book arrived (for example by a pull) joins it now.
    positionQueries.linkPositionToLibraryBook(book.libraryBookId)
}

internal fun AppDatabase.upsertDeviceFileRow(file: DeviceFileEntity) {
    deviceFileQueries.upsertDeviceFile(
        library_book_id = file.libraryBookId,
        media_type = file.mediaType,
        file_path = file.filePath,
        file_size = file.fileSize,
        content_hash = file.contentHash,
        content_hash_algorithm = file.contentHashAlgorithm,
        origin = file.origin,
        added_at = file.addedAt,
    )
}

internal fun Library_books.toEntity() = LibraryBookEntity(
    libraryBookId = library_book_id,
    title = title,
    author = author,
    description = description,
    coverPath = cover_path,
    publicationDate = publication_date,
    sourceContentHash = source_content_hash,
    sourceContentHashAlgorithm = source_content_hash_algorithm,
    addedAt = added_at,
    lastOpenedAt = last_opened_at,
    remoteRevision = remote_revision,
    deletedAt = deleted_at,
    metadataJson = metadata_json,
)

internal fun Device_files.toEntity() = DeviceFileEntity(
    libraryBookId = library_book_id,
    mediaType = media_type,
    filePath = file_path,
    fileSize = file_size,
    contentHash = content_hash,
    contentHashAlgorithm = content_hash_algorithm,
    origin = origin,
    addedAt = added_at,
)

/** The write log follows the surviving book; on a clash the newer write is kept. */
internal fun AppDatabase.mergeLinkedCopyWrites(fromId: String, intoId: String) {
    val fromKey = "library:$fromId"
    val intoKey = "library:$intoId"
    val fromWrite = linkedCopyWriteQueries.getWrite(fromKey).executeAsOneOrNull() ?: return
    val intoWrite = linkedCopyWriteQueries.getWrite(intoKey).executeAsOneOrNull()
    if (intoWrite == null || fromWrite.written_at > intoWrite.written_at) {
        linkedCopyWriteQueries.upsertWrite(
            target_key = intoKey,
            target_book_uuid = intoId,
            source_key = fromWrite.source_key,
            source_observed_at = fromWrite.source_observed_at,
            written_at = fromWrite.written_at,
            marker = fromWrite.marker,
            locator_href = fromWrite.locator_href,
            progression = fromWrite.progression,
            total_progression = fromWrite.total_progression,
            audio_ms = fromWrite.audio_ms,
        )
    }
    linkedCopyWriteQueries.deleteWrite(fromKey)
}
