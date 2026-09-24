package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.library.deleteOrphanedLibraryBookState
import com.retro99.database.implementation.dao.library.upsertLibraryBookRow
import com.retro99.database.implementation.dao.library.upsertLocalBookFileRow
import com.retro99.database.implementation.dao.library.upsertLocalLibraryBookRow
import com.retro99.database.implementation.dao.sync.enqueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibraryBookQueriesTest {

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
    fun localUpsertPreservesServerOwnedFields() {
        database.libraryBookQueries.upsertLibraryBook(
            library_book_id = "hash-1",
            content_hash = "hash-1",
            content_hash_algorithm = "sha256",
            title = "Original title",
            author = "An author",
            format = "ebook",
            remote_revision = 7,
            deleted_at = "2026-09-18T10:00:00Z",
            cloud_book_id = "cloud-book-1",
            metadata_json = null,
        )

        database.upsertLocalLibraryBookRow(
            TestLibraryBook(
                libraryBookId = "hash-1",
                title = "Re-imported title",
            ),
        )

        val row = database.libraryBookQueries.getLibraryBookById("hash-1").executeAsOne()
        assertEquals("Re-imported title", row.title)
        assertEquals("sha256", row.content_hash_algorithm)
            assertEquals(7L, row.remote_revision)
            assertEquals("2026-09-18T10:00:00Z", row.deleted_at)
            assertEquals("cloud-book-1", row.cloud_book_id)
    }

    @Test
    fun localUpsertInsertsBookWithoutServerState() {
        database.upsertLocalLibraryBookRow(
            TestLibraryBook(
                libraryBookId = "hash-2",
                title = "New book",
            ),
        )

        val row = database.libraryBookQueries.getLibraryBookById("hash-2").executeAsOne()
        assertEquals("hash-2", row.content_hash)
        assertEquals("sha256", row.content_hash_algorithm)
        assertNull(row.remote_revision)
        assertNull(row.deleted_at)
    }

    @Test
    fun orphanCleanupRemovesUnsyncedBooksAndPendingMutations() {
        database.upsertLocalLibraryBookRow(
            TestLibraryBook(
                libraryBookId = "unsynced",
                title = "Unsynced book",
            ),
        )
        database.upsertLibraryBookRow(
            TestLibraryBook(
                libraryBookId = "synced",
                title = "Synced book",
                remoteRevision = 3,
            ),
        )
        database.syncOutboxQueries.enqueue(
            outboxEntry(entityId = "unsynced", cloudUserId = "cloud-user"),
        )
        database.syncOutboxQueries.enqueue(
            outboxEntry(entityId = "synced", cloudUserId = "cloud-user"),
        )

        database.deleteOrphanedLibraryBookState()

        assertNull(database.libraryBookQueries.getLibraryBookById("unsynced").executeAsOneOrNull())
        assertNotNull(database.libraryBookQueries.getLibraryBookById("synced").executeAsOneOrNull())
        val pendingEntityIds = database.syncOutboxQueries.getPendingMutations("cloud-user")
            .executeAsList()
            .map { mutation -> mutation.entity_id }
        assertEquals(listOf("synced"), pendingEntityIds)
    }

    @Test
    fun migrationChainProducesLibraryBookSchema() {
        val migrationDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            migrationDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE imported_books (
                        uuid TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        author TEXT,
                        description TEXT,
                        cover_path TEXT,
                        file_path TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        content_hash TEXT,
                        content_hash_algorithm TEXT,
                        imported_at TEXT NOT NULL,
                        last_opened_at TEXT,
                        book_type TEXT NOT NULL DEFAULT 'ebook',
                        publication_date TEXT
                    );
                """.trimIndent(),
                parameters = 0,
            )
            migrationDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE position (
                        book_uuid TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER,
                        created_at TEXT,
                        updated_at TEXT,
                        locator_href TEXT,
                        locator_type TEXT,
                        locator_title TEXT,
                        locator_target INTEGER,
                        audio_timestamp_ms INTEGER,
                        chapter_index INTEGER,
                        progression REAL,
                        total_chapters INTEGER,
                        total_duration_ms INTEGER,
                        total_progression REAL,
                        position INTEGER
                    );
                """.trimIndent(),
                parameters = 0,
            )
            AppDatabase.Schema.migrate(migrationDriver, 17, AppDatabase.Schema.version)
            val migrated = AppDatabase(migrationDriver)

            migrated.libraryBookQueries.upsertLibraryBook(
                library_book_id = "hash-migrated",
                content_hash = "hash-migrated",
                content_hash_algorithm = "sha256",
                title = "Migrated book",
                author = "An author",
                format = "ebook",
                remote_revision = null,
                deleted_at = null,
                cloud_book_id = null,
                metadata_json = null,
            )

            val row = migrated.libraryBookQueries
                .getLibraryBookById("hash-migrated")
                .executeAsOne()
            assertEquals("Migrated book", row.title)
            assertEquals("sha256", row.content_hash_algorithm)
        } finally {
            migrationDriver.close()
        }
    }

    @Test
    fun orphanCleanupKeepsUnsyncedBooksWithLocalFiles() {
        database.upsertLocalLibraryBookRow(
            TestLibraryBook(
                libraryBookId = "hash-3",
                title = "Local book",
            ),
        )
        database.upsertLocalBookFileRow(
            TestLocalBookFile(
                libraryBookId = "hash-3",
                importedBookUuid = "uuid-3",
            ),
        )

        database.deleteOrphanedLibraryBookState()

        assertNotNull(database.libraryBookQueries.getLibraryBookById("hash-3").executeAsOneOrNull())
    }

    private fun outboxEntry(entityId: String, cloudUserId: String) = SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
        entityId = entityId,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        cloudUserId = cloudUserId,
    )
}

private data class TestLibraryBook(
    override val libraryBookId: String,
    override val contentHash: String? = libraryBookId,
    override val contentHashAlgorithm: String? = "sha256",
    override val title: String,
    override val author: String? = "An author",
    override val format: String = "ebook",
    override val remoteRevision: Long? = null,
    override val deletedAt: String? = null,
) : LibraryBookEntity

private data class TestLocalBookFile(
    override val libraryBookId: String,
    override val importedBookUuid: String,
) : LocalBookFileEntity
