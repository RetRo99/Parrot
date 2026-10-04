package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 36.sqm: bookmarks become saved items with a portable book key and a UUID id. */
class SavedItemsMigrationTest {

    @Test
    fun `bookmarks move to saved items and their stuck outbox entries are dropped`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 36)
            executeScript(
                driver,
                """
                INSERT INTO library_books(library_book_id, title, author, added_at)
                VALUES ('lib-1', 'The Lantern Ferry', 'A. Writer', '2026-01-01');
                INSERT INTO books(uuid, server_id, server_type, id, title)
                VALUES ('st-1', 'srv', 'storyteller', 1, 'Winter Orchard');
                INSERT INTO books(uuid, server_id, server_type, id, title)
                VALUES ('abs-1', 'srv2', 'audiobookshelf', 2, 'Salt');
                INSERT INTO bookmarks(id, book_uuid, locator_href, locator_title, progression,
                    total_progression, created_at, sort_order)
                VALUES ('old-1', 'lib-1', 'ch6.xhtml', '6. The Hill Road', 0.3, 0.41, '2026-09-01T10:00:00Z', 0);
                INSERT INTO bookmarks(id, book_uuid, locator_href, created_at, sort_order)
                VALUES ('old-2', 'st-1', 'ch1.xhtml', '2026-09-02T10:00:00Z', 0);
                INSERT INTO bookmarks(id, book_uuid, locator_href, created_at, sort_order)
                VALUES ('old-3', 'abs-1', 'c.xhtml', '2026-09-03T10:00:00Z', 0);
                INSERT INTO bookmarks(id, book_uuid, locator_href, created_at, sort_order, deleted_at)
                VALUES ('old-4', 'lib-1', 'ch7.xhtml', '2026-09-03T10:00:00Z', 0, '2026-09-04');
                INSERT INTO sync_outbox(mutation_id, entity_type, entity_id, operation, payload, created_at)
                VALUES ('m1', 'bookmark', 'old-1', 'upsert', '{}', 'a');
                INSERT INTO sync_outbox(mutation_id, entity_type, entity_id, operation, payload, created_at)
                VALUES ('m2', 'library_book', 'lib-1', 'upsert', '{}', 'a');
                """.trimIndent(),
            )

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 36, AppDatabase.Schema.version)

            // Then
            val items = AppDatabase(driver).savedItemQueries.getAllLiveSavedItems().executeAsList()
            assertEquals(
                setOf("library:lib-1", "storyteller:st-1", "audiobookshelf:abs-1"),
                items.map { item -> item.book_key }.toSet(),
                "tombstones are dropped and every live bookmark gets its copy key",
            )
            val ferry = items.single { item -> item.book_uuid == "lib-1" }
            assertEquals("bookmark", ferry.type)
            assertEquals("6. The Hill Road", ferry.chapter_title)
            assertEquals("The Lantern Ferry", ferry.book_title)
            assertEquals("A. Writer", ferry.book_author)
            assertEquals(0.41, ferry.total_progression)
            assertEquals(1L, ferry.snippet_pending)
            assertEquals("2026-09-01T10:00:00Z", ferry.updated_at)
            items.forEach { item ->
                assertTrue(UUID_V4.matches(item.id), "${item.id} is a v4 UUID")
            }
            assertEquals(0L, count(driver, "SELECT COUNT(*) FROM sync_outbox WHERE entity_type = 'bookmark'"))
            assertEquals(1L, count(driver, "SELECT COUNT(*) FROM sync_outbox WHERE entity_type = 'library_book'"))
            assertEquals(
                0L,
                count(driver, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'bookmarks'"),
            )
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

    private companion object {
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
