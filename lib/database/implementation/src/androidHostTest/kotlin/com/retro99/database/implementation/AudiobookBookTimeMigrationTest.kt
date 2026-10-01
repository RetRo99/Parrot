package com.retro99.database.implementation

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.implementation.dao.books.decodeTrackDurations
import com.retro99.database.implementation.dao.books.encodeTrackDurations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open bugs B3 (30.sqm): book time on positions and per-file lengths on books. */
class AudiobookBookTimeMigrationTest {

    @Test
    fun `migrating from 29 keeps positions and leaves the new values unknown`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 30)
            executeScript(
                driver,
                """
                INSERT INTO position(book_uuid, audio_timestamp_ms, chapter_index)
                    VALUES ('book-1', 450000, 20);
                INSERT INTO books(uuid, id, title, audio_duration_ms)
                    VALUES ('book-1', 1, 'Book', 36000000);
                """.trimIndent(),
            )

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 30, AppDatabase.Schema.version)

            // Then
            val database = AppDatabase(driver)
            val position = database.positionQueries.getPositionByBookUuid("book-1").executeAsOne()
            assertEquals(450_000L, position.audio_timestamp_ms)
            assertEquals(20L, position.chapter_index)
            assertNull(position.book_time_ms)
            val book = database.bookQueries.getBookByUuid("book-1").executeAsOne()
            assertNull(book.audio_track_durations_ms)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a saved position keeps its book time, locally and as the remote candidate`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            AppDatabase.Schema.create(driver)
            val database = AppDatabase(driver)

            // When
            database.positionQueries.upsertPosition(
                book_uuid = "book-1",
                library_book_id = "book-1",
                local_generation = 0,
                remote_revision = null,
                timestamp = null,
                created_at = null,
                updated_at = null,
                locator_href = null,
                locator_type = null,
                locator_title = null,
                locator_target = null,
                css_selector = null,
                audio_timestamp_ms = 450_000,
                chapter_index = 20,
                progression = null,
                total_chapters = 40,
                total_duration_ms = 36_000_000,
                total_progression = 0.5125,
                position = null,
                origin = "user",
                observed_at = null,
                text_anchor = null,
                book_time_ms = 18_450_000,
                ebook_location_raw = null,
            )
            database.positionQueries.upsertRemotePosition(
                book_uuid = "book-1",
                library_book_id = null,
                remote_revision = 1,
                timestamp = null,
                created_at = null,
                updated_at = null,
                locator_href = null,
                locator_type = null,
                locator_title = null,
                locator_target = null,
                css_selector = null,
                audio_timestamp_ms = null,
                chapter_index = null,
                progression = null,
                total_chapters = null,
                total_duration_ms = 36_000_000,
                total_progression = 0.6,
                position = null,
                book_time_ms = 21_600_000,
                ebook_location_raw = null,
            )

            // Then
            val local = database.positionQueries.getPositionByBookUuid("book-1").executeAsOne()
            val remote = database.positionQueries
                .getRemotePositionByBookUuid("book-1")
                .executeAsOne()
            assertEquals(18_450_000L, local.book_time_ms)
            assertEquals(21_600_000L, remote.book_time_ms)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `track lengths round-trip through the column`() {
        // Given
        val durations = listOf(600_500L, 900_000L)

        // When
        val stored = encodeTrackDurations(durations)

        // Then
        assertEquals("600500,900000", stored)
        assertEquals(durations, decodeTrackDurations(stored))
    }

    @Test
    fun `missing or unreadable track lengths are unknown`() {
        // Given
        val values = listOf(null, "", "600500,abc", "600500,,900000", "-1")

        values.forEach { value ->
            // When
            val durations = decodeTrackDurations(value)

            // Then
            assertNull(durations, "for $value")
        }
        assertNull(encodeTrackDurations(emptyList()))
        assertNull(encodeTrackDurations(null))
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
