package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 39.sqm (schema version 39 to 40): catalogue download queue, provenance of acquired books, saved catalogue pages. */
class CatalogueTablesMigrationTest {

    @Test
    fun `a new database has the three catalogue tables and is at version 40`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // When
            AppDatabase.Schema.create(driver)

            // Then
            assertEquals(40L, AppDatabase.Schema.version)
            assertEquals(CATALOGUE_TABLES, catalogueTables(driver))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `upgrading from version 39 adds the catalogue tables and keeps existing rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 39)
            executeScript(
                driver,
                """
                INSERT INTO library_books(library_book_id, title, author, added_at)
                VALUES ('lib-1', 'The Lantern Ferry', 'A. Writer', '2026-01-01');
                INSERT INTO books(uuid, server_id, server_type, id, title)
                VALUES ('st-1', 'srv', 'storyteller', 1, 'Winter Orchard');
                INSERT INTO sync_outbox(mutation_id, entity_type, entity_id, operation, payload, created_at)
                VALUES ('m1', 'library_book', 'lib-1', 'upsert', '{}', 'a');
                """.trimIndent(),
            )
            assertEquals(emptySet(), catalogueTables(driver))
            val before = schemaOf(driver)

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 39, newVersion = 40)

            // Then
            assertEquals(CATALOGUE_TABLES, catalogueTables(driver))
            assertEquals(
                "The Lantern Ferry",
                text(driver, "SELECT title FROM library_books WHERE library_book_id = 'lib-1'"),
            )
            assertEquals("Winter Orchard", text(driver, "SELECT title FROM books WHERE uuid = 'st-1'"))
            assertEquals(1L, count(driver, "SELECT COUNT(*) FROM sync_outbox"))
            val after = schemaOf(driver)
            before.forEach { (table, definition) ->
                assertEquals(definition, after[table], "$table is unchanged by 39.sqm")
            }
            CATALOGUE_TABLES.forEach { table ->
                assertEquals(0L, count(driver, "SELECT COUNT(*) FROM $table"), "$table starts empty")
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `the upgraded catalogue tables match the ones a new database gets`() {
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
            CATALOGUE_TABLES.forEach { table ->
                assertTrue(freshSchema.getValue(table).isNotEmpty())
                assertEquals(freshSchema[table], migratedSchema[table], table)
            }
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    private fun catalogueTables(driver: SqlDriver): Set<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'catalogue_%'",
        mapper = { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) names += cursor.getString(0).orEmpty()
            QueryResult.Value(names)
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

    private fun count(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        parameters = 0,
    ).value

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
        val CATALOGUE_TABLES = setOf("catalogue_acquisitions", "catalogue_book_sources", "catalogue_documents")
    }
}
