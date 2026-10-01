package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PositionBaselineTest {

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
    fun remoteCandidateCanBeStoredWithoutReplacingDirtyLocalPosition() {
        database.positionQueries.upsertPosition(
            book_uuid = "book-1",
            library_book_id = "library-book-1",
            local_generation = 3L,
            remote_revision = 7L,
            timestamp = 100L,
            created_at = null,
            updated_at = "2026-09-22T10:00:00Z",
            locator_href = "chapter-1",
            locator_type = "epub",
            locator_title = "Chapter 1",
            locator_target = 10L,
            css_selector = "#chapter-1 p:nth-of-type(2)",
            audio_timestamp_ms = null,
            chapter_index = 1L,
            progression = 0.2,
            total_chapters = 10L,
            total_duration_ms = null,
            total_progression = 0.2,
            position = 10L,
            origin = "user",
            observed_at = null,
            text_anchor = null,
        )
        database.positionQueries.upsertRemotePosition(
            book_uuid = "book-1",
            library_book_id = "library-book-1",
            remote_revision = 8L,
            timestamp = 200L,
            created_at = null,
            updated_at = "2026-09-22T10:01:00Z",
            locator_href = "chapter-4",
            locator_type = "epub",
            locator_title = "Chapter 4",
            locator_target = 40L,
            css_selector = "#chapter-4 p:nth-of-type(1)",
            audio_timestamp_ms = null,
            chapter_index = 4L,
            progression = 0.8,
            total_chapters = 10L,
            total_duration_ms = null,
            total_progression = 0.8,
            position = 40L,
        )

        val local = database.positionQueries.getPositionByBookUuid("book-1").executeAsOne()
        val remote = database.positionQueries
            .getRemotePositionByBookUuid("book-1")
            .executeAsOne()

        assertEquals(3L, local.local_generation)
        assertEquals(0.2, local.progression)
        assertEquals("#chapter-1 p:nth-of-type(2)", local.css_selector)
        assertEquals(7L, local.remote_revision)
        assertEquals(0.8, remote.progression)
        assertEquals("#chapter-4 p:nth-of-type(1)", remote.css_selector)
        assertEquals(8L, remote.remote_revision)
    }

    @Test
    fun acknowledgementCannotAdvanceAnOlderLocalGeneration() {
        database.positionQueries.upsertPosition(
            book_uuid = "book-1",
            library_book_id = "library-book-1",
            local_generation = 3L,
            remote_revision = 7L,
            timestamp = 100L,
            created_at = null,
            updated_at = "2026-09-22T10:00:00Z",
            locator_href = "chapter-1",
            locator_type = "epub",
            locator_title = "Chapter 1",
            locator_target = 10L,
            css_selector = "#chapter-1",
            audio_timestamp_ms = null,
            chapter_index = 1L,
            progression = 0.2,
            total_chapters = 10L,
            total_duration_ms = null,
            total_progression = 0.2,
            position = 10L,
            origin = "user",
            observed_at = null,
            text_anchor = null,
        )

        database.positionQueries.updateRemoteRevisionIfGeneration(8L, "book-1", 2L)
        assertEquals(
            7L,
            database.positionQueries.getPositionByBookUuid("book-1").executeAsOne().remote_revision,
        )

        database.positionQueries.updateRemoteRevisionIfGeneration(8L, "book-1", 3L)
        assertEquals(
            8L,
            database.positionQueries.getPositionByBookUuid("book-1").executeAsOne().remote_revision,
        )
    }
}
