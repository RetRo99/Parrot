package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 42.sqm (schema version 42 to 43): a transfer can carry a file of a book that
 * is not the book itself -- its own relative path, and its own source bytes.
 *
 * Driven through the driver rather than the DAO, because what is under test is
 * the schema and the upgrade, not the mapping.
 */
class PreparedAudioTransferMigrationTest {

    @Test
    fun `a new database is at version 43 or later and a transfer keeps its own path and source`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            AppDatabase.Schema.create(driver)

            // When
            driver.execute(
                null,
                """
                INSERT INTO cloud_file_transfers(
                    transfer_id, server_id, direction, library_book_id, media_type,
                    size_bytes, bytes_transferred, state, attempt_count, created_at, updated_at,
                    relative_path, source_path
                ) VALUES ('t-1', 'parrot-cloud', 'upload', 'book-1', 'tts_prepared_audio',
                    4096, 0, 'pending', 0, 'a', 'a', '$PREPARED_PATH', '/data/chapter.zip')
                """.trimIndent(),
                0,
            )

            // Then
            assertTrue(AppDatabase.Schema.version >= 43L)
            assertEquals(PREPARED_PATH, textOf(driver, "relative_path", "t-1"))
            assertEquals("/data/chapter.zip", textOf(driver, "source_path", "t-1"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a book file still means the empty path and no source of its own`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            AppDatabase.Schema.create(driver)

            // When: the columns omitted, which is what every existing insert does.
            driver.execute(null, insertBookFile("t-1"), 0)

            // Then
            assertEquals("", textOf(driver, "relative_path", "t-1"))
            assertNull(textOf(driver, "source_path", "t-1"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `upgrading from version 42 keeps transfers and gives them the book's own slot`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 42)
            driver.execute(null, insertBookFile("old-1"), 0)
            val before = schemaOf(driver)

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 42, newVersion = 43)

            // Then: the row survives and reads as the book's own file, which is
            // what it has always meant.
            assertEquals("ebook", textOf(driver, "media_type", "old-1"))
            assertEquals("", textOf(driver, "relative_path", "old-1"))
            assertNull(textOf(driver, "source_path", "old-1"))
            val after = schemaOf(driver)
            before.filterKeys { table -> table != "cloud_file_transfers" }.forEach { (table, definition) ->
                assertEquals(definition, after[table], "$table is unchanged by 42.sqm")
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `the upgraded transfers table matches the one a new database gets`() {
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(migrated, readResource("v28_schema.sql"))

            // When
            AppDatabase.Schema.migrate(migrated, oldVersion = 28, newVersion = AppDatabase.Schema.version)
            AppDatabase.Schema.create(fresh)

            // Then
            val table = schemaOf(fresh).getValue("cloud_file_transfers")
            assertTrue(table.any { definition -> definition.startsWith("column:relative_path:TEXT") })
            assertTrue(table.any { definition -> definition.startsWith("column:source_path:TEXT") })
            assertEquals(table, schemaOf(migrated)["cloud_file_transfers"])
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    @Test
    fun `an upgraded transfer reads back field for field through the query the DAO uses`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given: a device already carrying a real transfer, at the version
            // before 42.sqm.
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 42)
            driver.execute(
                null,
                """
                INSERT INTO cloud_file_transfers(
                    transfer_id, server_id, direction, library_book_id, cloud_book_file_id,
                    media_type, staging_path, size_bytes, bytes_transferred, content_hash,
                    content_hash_algorithm, upload_id, storage_path, tus_upload_url,
                    tus_expires_at, rights_attestation, state, attempt_count,
                    next_attempt_at, last_error, created_at, updated_at
                ) VALUES ('old-1', 'parrot-cloud', 'upload', 'book-1', 'file-1',
                    'ebook', '/staging/book.epub', 9001, 4096, 'hash-1',
                    'sha256', 'upload-1', 'users/u/book.epub', 'https://tus/1',
                    '2026-01-01T00:00:00Z', 'attested', 'transferring', 3,
                    '2026-01-02T00:00:00Z', 'a transient failure',
                    '2026-01-01T00:00:00Z', '2026-01-03T00:00:00Z')
                """.trimIndent(),
                0,
            )

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 42, newVersion = 43)

            // Then: every field is what was written. The DAO reads this row
            // through a SELECT * whose generated reader takes columns by
            // position, so a column added in the middle of the .sq table reads
            // from the wrong slot on every existing install.
            val row = AppDatabase(driver).cloudFileTransferQueries
                .getCloudFileTransfer("old-1")
                .executeAsOne()
            assertEquals("old-1", row.transfer_id)
            assertEquals("parrot-cloud", row.server_id)
            assertEquals("upload", row.direction)
            assertEquals("book-1", row.library_book_id)
            assertEquals("file-1", row.cloud_book_file_id)
            assertEquals("ebook", row.media_type)
            assertEquals("/staging/book.epub", row.staging_path)
            assertEquals(9001L, row.size_bytes)
            assertEquals(4096L, row.bytes_transferred)
            assertEquals("hash-1", row.content_hash)
            assertEquals("sha256", row.content_hash_algorithm)
            assertEquals("upload-1", row.upload_id)
            assertEquals("users/u/book.epub", row.storage_path)
            assertEquals("https://tus/1", row.tus_upload_url)
            assertEquals("2026-01-01T00:00:00Z", row.tus_expires_at)
            assertEquals("attested", row.rights_attestation)
            assertEquals("transferring", row.state)
            assertEquals(3L, row.attempt_count)
            assertEquals("2026-01-02T00:00:00Z", row.next_attempt_at)
            assertEquals("a transient failure", row.last_error)
            assertEquals("2026-01-01T00:00:00Z", row.created_at)
            assertEquals("2026-01-03T00:00:00Z", row.updated_at)
            assertEquals("", row.relative_path)
            assertNull(row.source_path)
        } finally {
            driver.close()
        }
    }

    private fun insertBookFile(transferId: String) =
        """
        INSERT INTO cloud_file_transfers(
            transfer_id, server_id, direction, library_book_id, media_type,
            size_bytes, bytes_transferred, state, attempt_count, created_at, updated_at
        ) VALUES ('$transferId', 'parrot-cloud', 'upload', 'book-1', 'ebook', 9, 0, 'pending', 0, 'a', 'a')
        """.trimIndent()

    private fun textOf(driver: SqlDriver, column: String, transferId: String): String? = driver.executeQuery(
        identifier = null,
        sql = "SELECT $column FROM cloud_file_transfers WHERE transfer_id = '$transferId'",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        },
        parameters = 0,
    ).value

    private fun readResource(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assertTrue(stream != null, "missing test resource $name")
        return stream.bufferedReader().use { reader -> reader.readText() }
    }

    private fun executeScript(driver: SqlDriver, script: String) {
        script.split(';')
            .map { statement -> statement.trim() }
            .filter { statement ->
                statement.isNotEmpty() && !statement.startsWith("CREATE TABLE sqlite_sequence")
            }
            .forEach { statement -> driver.execute(null, statement, 0) }
    }

    private companion object {
        const val PREPARED_PATH = "tts-prepared/abc/def.zip"
    }
}
