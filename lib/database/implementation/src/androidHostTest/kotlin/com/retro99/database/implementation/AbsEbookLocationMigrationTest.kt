package com.retro99.database.implementation

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open bugs B4 (31.sqm): Audiobookshelf's raw ebook location on positions. */
class AbsEbookLocationMigrationTest {

    @Test
    fun `migrating from 31 keeps positions with no raw ebook location`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 31)
            executeScript(
                driver,
                "INSERT INTO position(book_uuid, locator_href) VALUES ('book-1', 'c1.xhtml')",
            )

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 31, AppDatabase.Schema.version)

            // Then
            val position = AppDatabase(driver).positionQueries
                .getPositionByBookUuid("book-1")
                .executeAsOne()
            assertEquals("c1.xhtml", position.locator_href)
            assertNull(position.ebook_location_raw)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a saved position keeps its raw ebook location, locally and as the remote candidate`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            AppDatabase.Schema.create(driver)
            val database = AppDatabase(driver)

            // When
            database.positionQueries.upsertPosition(
                "book-1", "book-1", 0, null, null, null, null, "c2.xhtml", null, null, null,
                null, null, null, 0.4, null, null, 0.5, null,
                "remote", null, null, null, "epubcfi(/6/6!/4)", null, null,
            )
            database.positionQueries.upsertRemotePosition(
                "book-1", null, 1, null, null, null, "c2.xhtml", null, null, null,
                null, null, null, 0.4, null, null, 0.5, null, null, """{"href":"c2.xhtml"}""",
                null, null,
            )

            // Then
            val local = database.positionQueries.getPositionByBookUuid("book-1").executeAsOne()
            val remote = database.positionQueries
                .getRemotePositionByBookUuid("book-1")
                .executeAsOne()
            assertEquals("epubcfi(/6/6!/4)", local.ebook_location_raw)
            assertEquals("""{"href":"c2.xhtml"}""", remote.ebook_location_raw)
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
}
