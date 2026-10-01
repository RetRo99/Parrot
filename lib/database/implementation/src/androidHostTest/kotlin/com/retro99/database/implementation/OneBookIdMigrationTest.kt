package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneBookIdMigrationTest {

    // Version 27 resets on devices; retain this historical migration regression test.
    @Test
    fun `migrating from 27 produces the fresh schema`() {
        listOf(27L to "v27_schema.sql").forEach { (version, fixture) ->
            // Given
            val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                executeScript(migrated, readResource(fixture))

                // When
                AppDatabase.Schema.migrate(
                    migrated,
                    oldVersion = version,
                    newVersion = AppDatabase.Schema.version,
                )
                AppDatabase.Schema.create(fresh)

                // Then
                assertEquals(schemaOf(fresh), schemaOf(migrated), "from version $version")
            } finally {
                migrated.close()
                fresh.close()
            }
        }
    }

    @Test
    fun `migration resets the device library and keeps server book data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v27_schema.sql"))
            executeScript(
                driver,
                """
                INSERT INTO imported_books(uuid, title, file_path, file_size, imported_at)
                VALUES ('imp-1', 'Imported', '/f/imp-1.epub', 10, '2026-09-01T00:00:00Z');
                INSERT INTO library_books(library_book_id, title, format)
                VALUES ('sha-256-v1:x', 'Imported', 'ebook');
                INSERT INTO local_book_files(library_book_id, imported_book_uuid)
                VALUES ('sha-256-v1:x', 'imp-1');
                INSERT INTO position(book_uuid, library_book_id, progression)
                VALUES ('imp-1', 'sha-256-v1:x', 0.1);
                INSERT INTO position(book_uuid, library_book_id, progression)
                VALUES ('sha-256-v1:x', 'sha-256-v1:x', 0.2);
                INSERT INTO position(book_uuid, library_book_id, progression)
                VALUES ('st-1', 'st-1', 0.3);
                INSERT INTO remote_position(book_uuid, progression) VALUES ('st-1', 0.3);
                INSERT INTO favorites(book_uuid, added_at) VALUES ('imp-1', 'a');
                INSERT INTO favorites(book_uuid, added_at) VALUES ('sha-256-v1:x', 'a');
                INSERT INTO favorites(book_uuid, added_at) VALUES ('st-1', 'a');
                INSERT INTO bookmarks(id, book_uuid, locator_href, created_at)
                VALUES ('b1', 'imp-1', 'c1', 'a');
                INSERT INTO bookmarks(id, book_uuid, locator_href, created_at)
                VALUES ('b2', 'st-1', 'c1', 'a');
                INSERT INTO reading_session(
                    book_uuid, book_title, book_type, start_time, end_time, duration_ms
                ) VALUES ('imp-1', 'Imported', 'ebook', 1, 2, 1);
                INSERT INTO reading_session(
                    book_uuid, book_title, book_type, start_time, end_time, duration_ms
                ) VALUES ('st-1', 'Storyteller', 'ebook', 1, 2, 1);
                INSERT INTO sync_outbox(mutation_id, entity_type, entity_id, operation, payload, created_at)
                VALUES ('m1', 'library_book', 'sha-256-v1:x', 'upsert', '{}', 'a');
                INSERT INTO sync_checkpoints(destination_id, remote_account_id, updated_at)
                VALUES ('parrot', 'u1', 'a');
                """.trimIndent(),
            )

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 27, newVersion = AppDatabase.Schema.version)

            // Then
            listOf("imp-1", "sha-256-v1:x").forEach { bookId ->
                assertEquals(0L, count(driver, "SELECT COUNT(*) FROM position WHERE book_uuid = '$bookId'"))
                assertEquals(0L, count(driver, "SELECT COUNT(*) FROM favorites WHERE book_uuid = '$bookId'"))
                assertEquals(0L, count(driver, "SELECT COUNT(*) FROM bookmarks WHERE book_uuid = '$bookId'"))
                assertEquals(
                    0L,
                    count(driver, "SELECT COUNT(*) FROM reading_session WHERE book_uuid = '$bookId'"),
                )
            }
            val database = AppDatabase(driver)
            val storytellerPosition = database.positionQueries.getPositionByBookUuid("st-1")
                .executeAsOne()
            assertNull(storytellerPosition.library_book_id)
            assertEquals(1L, count(driver, "SELECT COUNT(*) FROM favorites WHERE book_uuid = 'st-1'"))
            assertEquals(1L, count(driver, "SELECT COUNT(*) FROM bookmarks WHERE book_uuid = 'st-1'"))
            assertEquals(
                1L,
                count(driver, "SELECT COUNT(*) FROM reading_session WHERE book_uuid = 'st-1'"),
            )
            listOf(
                "library_books",
                "device_files",
                "cloud_book_file_state",
                "cloud_file_transfers",
                "sync_outbox",
                "sync_checkpoints",
                "remote_position",
            ).forEach { table ->
                assertEquals(0L, count(driver, "SELECT COUNT(*) FROM $table"), table)
            }
        } finally {
            driver.close()
        }
    }

    private fun readResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(name)
        assertTrue(stream != null, "missing test resource $name")
        return stream.bufferedReader().use { reader -> reader.readText() }
    }

    private fun executeScript(driver: SqlDriver, script: String) {
        script.split(';')
            .map { statement -> statement.trim() }
            .filter { statement -> statement.isNotEmpty() }
            .forEach { statement -> driver.execute(null, statement, 0) }
    }

    private fun count(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value

}
