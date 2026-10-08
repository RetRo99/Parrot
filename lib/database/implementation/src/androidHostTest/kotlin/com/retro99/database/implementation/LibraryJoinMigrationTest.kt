package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 40.sqm (schema version 40 to 41): the import journal and what joining downloads to the library stores. */
class LibraryJoinMigrationTest {

    @Test
    fun `a new database is at version 41 and has the import journal`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // When
            AppDatabase.Schema.create(driver)

            // Then
            assertEquals(41L, AppDatabase.Schema.version)
            assertTrue(schemaOf(driver).getValue("library_import_journal").isNotEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `upgrading from version 40 keeps downloads and provenance and adds empty columns`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 40)
            executeScript(
                driver,
                """
                INSERT INTO catalogue_acquisitions(
                    request_id, source_id, publication_key, representation_key, title, catalogue_name,
                    state, queue_position, expected_size_bytes, created_at, updated_at
                ) VALUES ('r1', 'source-1', 'urn:book:1', 'epub#1', 'The Lantern Ferry', 'Home shelf', 'waiting', 1, 900, 1, 1);
                INSERT INTO catalogue_book_sources(
                    id, library_book_id, source_id, catalogue_name, catalogue_origin, publication_key,
                    selected_format, content_hash, acquired_at
                ) VALUES ('p1', 'lib-1', 'source-1', 'Home shelf', 'https://books.example', 'urn:book:1', 'application/epub+zip', 'abc', 2);
                """.trimIndent(),
            )
            val before = schemaOf(driver)

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 40, newVersion = 41)

            // Then
            assertEquals("The Lantern Ferry", text(driver, "SELECT title FROM catalogue_acquisitions WHERE request_id = 'r1'"))
            assertEquals("900", text(driver, "SELECT expected_size_bytes FROM catalogue_acquisitions WHERE request_id = 'r1'"))
            assertEquals(
                null,
                text(driver, "SELECT COALESCE(rights_text, catalogue_updated, needed_bytes) FROM catalogue_acquisitions"),
            )
            assertEquals("lib-1", text(driver, "SELECT library_book_id FROM catalogue_book_sources WHERE id = 'p1'"))
            assertEquals("0", text(driver, "SELECT COUNT(*) FROM library_import_journal"))
            val after = schemaOf(driver)
            before.filterKeys { table -> table !in CHANGED_TABLES }.forEach { (table, definition) ->
                assertEquals(definition, after[table], "$table is unchanged by 40.sqm")
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `the upgraded tables match the ones a new database gets`() {
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(migrated, readResource("v28_schema.sql"))

            // When
            AppDatabase.Schema.migrate(migrated, oldVersion = 28, newVersion = AppDatabase.Schema.version)
            AppDatabase.Schema.create(fresh)

            // Then
            val migratedSchema = schemaOf(migrated)
            val freshSchema = schemaOf(fresh)
            (CHANGED_TABLES + "library_import_journal").forEach { table ->
                assertTrue(freshSchema.getValue(table).isNotEmpty())
                assertEquals(freshSchema[table], migratedSchema[table], table)
            }
        } finally {
            migrated.close()
            fresh.close()
        }
    }

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

    private fun text(driver: SqlDriver, sql: String): String? = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getString(0))
        },
        parameters = 0,
    ).value

    private companion object {
        val CHANGED_TABLES = setOf("catalogue_acquisitions", "catalogue_book_sources")
    }
}
