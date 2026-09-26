package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * media_files.size was added so a cached book keeps its file sizes and so the cache round
 * trip is lossless (which lets repositories skip redundant cache writes).
 */
class MediaFileSizeTest {

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
    fun mediaFileSizeSurvivesARoundTrip() {
        database.mediaFileQueries.upsertMediaFile(
            uuid = "book-1-ebook",
            book_uuid = "book-1",
            type = "ebook",
            filepath = "/api/v2/books/book-1/files?format=ebook",
            missing = null,
            size = 12_345_678L,
            created_at = null,
            updated_at = null,
        )

        val stored = database.mediaFileQueries
            .getMediaFileByBookAndType("book-1", "ebook")
            .executeAsOne()

        assertEquals(12_345_678L, stored.size)
    }

    @Test
    fun migrationAddsTheSizeColumnToAnExistingMediaFilesTable() {
        val migrationDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // The media_files shape as it existed before the size column was added.
            migrationDriver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE media_files (
                        uuid TEXT NOT NULL PRIMARY KEY,
                        book_uuid TEXT NOT NULL,
                        type TEXT NOT NULL,
                        filepath TEXT,
                        missing INTEGER,
                        created_at TEXT,
                        updated_at TEXT
                    );
                """.trimIndent(),
                parameters = 0,
            )
            migrationDriver.execute(
                identifier = null,
                sql = """
                    INSERT INTO media_files(uuid, book_uuid, type, filepath, missing, created_at, updated_at)
                    VALUES ('book-1-ebook', 'book-1', 'ebook', 'path.epub', NULL, NULL, NULL)
                """.trimIndent(),
                parameters = 0,
            )

            AppDatabase.Schema.migrate(migrationDriver, 26, AppDatabase.Schema.version)

            val migrated = AppDatabase(migrationDriver)
            migrated.mediaFileQueries.upsertMediaFile(
                uuid = "book-1-ebook",
                book_uuid = "book-1",
                type = "ebook",
                filepath = "path.epub",
                missing = null,
                size = 42L,
                created_at = null,
                updated_at = null,
            )
            val stored = migrated.mediaFileQueries
                .getMediaFileByBookAndType("book-1", "ebook")
                .executeAsOne()

            assertEquals(42L, stored.size)
        } finally {
            migrationDriver.close()
        }
    }
}
