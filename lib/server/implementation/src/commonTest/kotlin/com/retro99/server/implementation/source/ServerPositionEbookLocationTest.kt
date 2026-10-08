package com.retro99.server.implementation.source

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** B4: reading in this app keeps the shape Audiobookshelf stored for the ebook location. */
class ServerPositionEbookLocationTest {

    private val positions = RecordingPositionDatabase()
    private val source = ServerPositionLocalDataSource(
        positionDatabase = positions,
        databaseExecutor = DirectExecutor(),
        libraryBooksDatabase = NoLibraryBooks(),
    )

    @Test
    fun `a local save keeps the stored ebook location`() = runTest {
        // Given
        source.savePosition(position().copy(ebookLocationRaw = CFI))

        // When
        source.savePositionWithSync(position(), remoteAccountId = "abs-1")

        // Then
        assertEquals(CFI, positions.stored.getValue("item-1").ebookLocationRaw)
    }

    @Test
    fun `a pulled ebook location replaces the stored one`() = runTest {
        // Given
        source.savePosition(position().copy(ebookLocationRaw = CFI))

        // When
        source.savePosition(position().copy(ebookLocationRaw = JSON))

        // Then
        assertEquals(JSON, positions.stored.getValue("item-1").ebookLocationRaw)
    }

    private fun position() = ServerPosition(
        bookUuid = "item-1",
        serverId = "abs-1",
        timestamp = 1L,
        createdAt = null,
        updatedAt = null,
        locatorHref = "OEBPS/ch02.xhtml",
        locatorType = "application/xhtml+xml",
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = null,
        chapterIndex = null,
        progression = 0.4,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = 0.5,
        position = null,
    )

    private companion object {
        const val CFI = "epubcfi(/6/6!/4)"
        const val JSON = """{"href":"OEBPS/ch02.xhtml"}"""
    }
}

private class DirectExecutor : DatabaseExecutor {
    override suspend fun <T> executeDatabaseOperation(
        reportException: Boolean,
        operation: suspend () -> T,
    ): AppResult<T> = Ok(operation())
}

private class RecordingPositionDatabase : PositionDatabase {
    val stored = mutableMapOf<String, PositionEntity>()

    override suspend fun upsertPosition(position: PositionEntity) {
        stored[position.bookUuid] = position
    }

    override suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    ) {
        stored[position.bookUuid] = position
    }

    override suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) = Unit

    override suspend fun upsertRemotePosition(position: PositionEntity) = Unit

    override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? = null

    override suspend fun deleteRemotePosition(bookUuid: String) = Unit

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? =
        stored[bookUuid]

    override suspend fun getAllPositions(): List<PositionEntity> = stored.values.toList()

    override suspend fun deletePosition(bookUuid: String) {
        stored.remove(bookUuid)
    }

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> =
        flowOf(stored[bookUuid])

    override fun observeAllPositions(): Flow<List<PositionEntity>> =
        flowOf(stored.values.toList())

    override suspend fun clearAllData() {
        stored.clear()
    }
}

private class NoLibraryBooks : LibraryBooksDatabase {
    override suspend fun upsertLibraryBook(book: LibraryBookEntity) = Unit

    override fun observeLibraryBooks(): Flow<List<LibraryBookEntity>> = flowOf(emptyList())

    override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = null

    override suspend fun findLibraryBookBySourceHash(
        algorithm: String,
        hash: String,
    ): LibraryBookEntity? = null

    override suspend fun countLibraryBooksWithDeviceFiles(): Int = 0

    override suspend fun updateLastOpenedAt(libraryBookId: String, lastOpenedAt: String) = Unit

    override suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    ) = Unit

    override suspend fun insertBookWithoutSync(book: LibraryBookEntity, file: DeviceFileEntity) = Unit

    override suspend fun deleteBookFromDevice(libraryBookId: String) = Unit
}
