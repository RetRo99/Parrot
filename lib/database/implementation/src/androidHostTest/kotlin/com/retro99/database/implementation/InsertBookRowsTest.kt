package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.library.insertBookRows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InsertBookRowsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `an imported book is stored with its device file and its outbox upsert`() {
        // When
        database.insertBookRows(
            book = book(),
            file = deviceFile(DeviceFileEntity.ORIGIN_IMPORT),
            outboxEntry = SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                entityId = BOOK_ID,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = "{}",
            ),
        )

        // Then
        assertEquals(BOOK_ID, database.libraryBookQueries.getLibraryBookById(BOOK_ID).executeAsOne().library_book_id)
        assertEquals("import", database.deviceFileQueries.getDeviceFile(BOOK_ID, "ebook").executeAsOne().origin)
        val mutation = database.syncOutboxQueries.getAllMutations().executeAsList().single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK, mutation.entity_type)
        assertEquals(BOOK_ID, mutation.entity_id)
    }

    @Test
    fun `a catalogue download is stored with its device file and the same outbox upsert`() {
        // When
        database.insertBookRows(
            book = book(),
            file = deviceFile(DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD),
            outboxEntry = SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                entityId = BOOK_ID,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = "{}",
            ),
        )

        // Then
        assertEquals(BOOK_ID, database.libraryBookQueries.getLibraryBookById(BOOK_ID).executeAsOne().library_book_id)
        val file = database.deviceFileQueries.getDeviceFile(BOOK_ID, "ebook").executeAsOne()
        assertEquals("catalogue_download", file.origin)
        assertEquals("/library/book.epub", file.file_path)
        val mutation = database.syncOutboxQueries.getAllMutations().executeAsList().single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK, mutation.entity_type)
        assertEquals(BOOK_ID, mutation.entity_id)
    }

    private fun book() = LibraryBookEntity(
        libraryBookId = BOOK_ID,
        title = "Book",
        addedAt = "2026-10-08T00:00:00Z",
    )

    private fun deviceFile(origin: String) = DeviceFileEntity(
        libraryBookId = BOOK_ID,
        mediaType = "ebook",
        filePath = "/library/book.epub",
        fileSize = 1,
        contentHash = "hash",
        contentHashAlgorithm = "sha-256-v1",
        origin = origin,
        addedAt = "2026-10-08T00:00:00Z",
    )

    private companion object {
        const val BOOK_ID = "11111111-1111-4111-8111-111111111111"
    }
}
