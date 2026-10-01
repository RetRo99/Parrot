package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookLinksMigrationTest {

    @Test
    fun `migrating from 28 produces the fresh schema`() {
        // Given
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            executeScript(migrated, readResource("v28_schema.sql"))

            // When
            AppDatabase.Schema.migrate(
                migrated,
                oldVersion = 28,
                newVersion = AppDatabase.Schema.version,
            )
            AppDatabase.Schema.create(fresh)

            // Then
            assertEquals(schemaOf(fresh), schemaOf(migrated))
            assertTrue("book_links" in schemaOf(migrated).keys)
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    @Test
    fun `migrating from 28 keeps library books and positions`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            executeScript(
                driver,
                """
                INSERT INTO library_books(library_book_id, title, added_at)
                VALUES ('book-1', 'Kept', '2026-10-01T00:00:00Z');
                INSERT INTO position(book_uuid, library_book_id, progression)
                VALUES ('book-1', 'book-1', 0.4);
                """.trimIndent(),
            )

            // When
            AppDatabase.Schema.migrate(
                driver,
                oldVersion = 28,
                newVersion = AppDatabase.Schema.version,
            )

            // Then
            assertEquals(1L, count(driver, "SELECT COUNT(*) FROM library_books"))
            assertEquals(1L, count(driver, "SELECT COUNT(*) FROM position"))
            assertEquals(0L, count(driver, "SELECT COUNT(*) FROM book_links"))
            assertEquals(0L, count(driver, "SELECT COUNT(*) FROM book_link_members"))
            assertEquals(0L, count(driver, "SELECT COUNT(*) FROM book_link_decisions"))
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
            .filter { statement ->
                statement.isNotEmpty() && !statement.startsWith("CREATE TABLE sqlite_sequence")
            }
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

    private fun schemaOf(driver: SqlDriver): Map<String, List<String>> {
        val tables = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' ORDER BY name",
            mapper = { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names += cursor.getString(0).orEmpty()
                QueryResult.Value(names)
            },
            parameters = 0,
        ).value
        return tables.associateWith { table ->
            driver.executeQuery(
                identifier = null,
                sql = "SELECT name, type, \"notnull\", pk FROM pragma_table_info('$table')",
                mapper = { cursor ->
                    val columns = mutableListOf<String>()
                    while (cursor.next().value) {
                        columns += listOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getLong(2),
                            cursor.getLong(3),
                        ).joinToString(":")
                    }
                    QueryResult.Value(columns.sorted())
                },
                parameters = 0,
            ).value
        }
    }
}
