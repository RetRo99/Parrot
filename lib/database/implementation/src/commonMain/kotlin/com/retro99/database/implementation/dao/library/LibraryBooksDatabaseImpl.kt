package com.retro99.database.implementation.dao.library

import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBookMergeDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow

internal class LibraryBooksDatabaseImpl(
    private val dao: LibraryBooksSqlDelightDao,
) : LibraryBooksDatabase, DeviceFilesDatabase, LibraryBookMergeDatabase {
    override suspend fun upsertLibraryBook(book: LibraryBookEntity) = dao.upsertLibraryBook(book)

    override fun observeLibraryBooks(): Flow<List<LibraryBookEntity>> = dao.observeLibraryBooks()

    override suspend fun getLibraryBookById(libraryBookId: String) =
        dao.getLibraryBookById(libraryBookId)

    override suspend fun findLibraryBookBySourceHash(algorithm: String, hash: String) =
        dao.findLibraryBookBySourceHash(algorithm, hash)

    override suspend fun countLibraryBooksWithDeviceFiles(): Int =
        dao.countLibraryBooksWithDeviceFiles()

    override suspend fun updateLastOpenedAt(libraryBookId: String, lastOpenedAt: String) =
        dao.updateLastOpenedAt(libraryBookId, lastOpenedAt)

    override suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    ) = dao.insertImportedBook(book, file, outboxEntry)

    override suspend fun deleteBookFromDevice(libraryBookId: String) =
        dao.deleteBookFromDevice(libraryBookId)

    override fun observeAllDeviceFiles(): Flow<List<DeviceFileEntity>> = dao.observeAllDeviceFiles()

    override suspend fun getDeviceFiles(libraryBookId: String) = dao.getDeviceFiles(libraryBookId)

    override suspend fun getDeviceFile(libraryBookId: String, mediaType: String) =
        dao.getDeviceFile(libraryBookId, mediaType)

    override suspend fun findByContentHash(algorithm: String, hash: String) =
        dao.findDeviceFileByHash(algorithm, hash)

    override suspend fun upsertDeviceFile(file: DeviceFileEntity) = dao.upsertDeviceFile(file)

    override suspend fun deleteDeviceFile(libraryBookId: String, mediaType: String) =
        dao.deleteDeviceFile(libraryBookId, mediaType)

    override suspend fun setOriginForBook(libraryBookId: String, origin: String) =
        dao.setOriginForBook(libraryBookId, origin)

    override suspend fun mergeLibraryBook(fromId: String, intoId: String): List<String> =
        dao.mergeLibraryBook(fromId, intoId)
}
