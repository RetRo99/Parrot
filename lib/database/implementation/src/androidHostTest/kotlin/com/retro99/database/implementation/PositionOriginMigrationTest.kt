package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The project 3 migration (29.sqm). There's no v29 schema dump, so this starts from v28 and
 * runs both 28.sqm and 29.sqm.
 */
class PositionOriginMigrationTest {

    @Test
    fun `migrating to the latest version produces the fresh schema`() {
        // Given
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            executeScript(migrated, readResource("v28_schema.sql"))

            // When
            AppDatabase.Schema.migrate(migrated, oldVersion = 28, newVersion = LATEST)
            AppDatabase.Schema.create(fresh)

            // Then
            assertEquals(LATEST, AppDatabase.Schema.version)
            assertEquals(schemaOf(fresh), schemaOf(migrated))
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    @Test
    fun `existing positions become reading on this device`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            executeScript(
                driver,
                """
                INSERT INTO position(book_uuid, progression) VALUES ('book-1', 0.4);
                INSERT INTO position(book_uuid, progression) VALUES ('book-2', 0.7);
                """.trimIndent(),
            )

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = LATEST)

            // Then
            assertEquals(2L, count(driver, "SELECT COUNT(*) FROM position WHERE origin = 'user'"))
            assertEquals(
                0L,
                count(
                    driver,
                    "SELECT COUNT(*) FROM position " +
                        "WHERE observed_at IS NOT NULL OR text_anchor IS NOT NULL",
                ),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a saved position keeps its origin, observation time and anchor`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            AppDatabase.Schema.create(driver)
            val database = AppDatabase(driver)

            // When
            database.positionQueries.upsertPosition(
                "book-1", "book-1", 0, null, null, null, null, "c1", null, null, null,
                null, null, null, 0.1, null, null, 0.1, null,
                "linked_copy", "2026-10-01T10:00:00Z", """{"before":"a","after":"b"}""",
            )

            // Then
            val row = database.positionQueries.getPositionByBookUuid("book-1").executeAsOne()
            assertEquals("linked_copy", row.origin)
            assertEquals("2026-10-01T10:00:00Z", row.observed_at)
            assertEquals("""{"before":"a","after":"b"}""", row.text_anchor)
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

    private companion object {
        const val LATEST = 30L
    }
}
