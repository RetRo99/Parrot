package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.implementation.dao.library.mergeLibraryBookRows
import com.retro99.database.implementation.dao.library.upsertLibraryBookRow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkedCopyWritesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        database.upsertLibraryBookRow(book(FROM))
        database.upsertLibraryBookRow(book(INTO))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a new write replaces the target's row`() {
        // Given
        write("storyteller:st", "st", writtenAt = "2026-10-01T10:00:00Z", progression = 0.2)

        // When
        write("storyteller:st", "st", writtenAt = "2026-10-01T11:00:00Z", progression = 0.4)

        // Then
        val rows = database.linkedCopyWriteQueries.getWrite("storyteller:st").executeAsList()
        assertEquals(listOf(0.4), rows.map { row -> row.total_progression })
    }

    @Test
    fun `old rows are deleted`() {
        // Given
        write("storyteller:st", "st", writtenAt = "2026-09-20T10:00:00Z", progression = 0.2)

        // When
        database.linkedCopyWriteQueries.deleteWrittenBefore("2026-09-24T10:00:00Z")

        // Then
        assertNull(database.linkedCopyWriteQueries.getWrite("storyteller:st").executeAsOneOrNull())
    }

    @Test
    fun `merging library books moves the write to the survivor`() {
        // Given
        write("library:$FROM", FROM, writtenAt = "2026-10-01T10:00:00Z", progression = 0.6)

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val moved = database.linkedCopyWriteQueries.getWrite("library:$INTO").executeAsOne()
        assertEquals(0.6, moved.total_progression)
        assertEquals(INTO, moved.target_book_uuid)
        assertNull(database.linkedCopyWriteQueries.getWrite("library:$FROM").executeAsOneOrNull())
    }

    @Test
    fun `merging keeps the newer write when both books have one`() {
        // Given
        write("library:$FROM", FROM, writtenAt = "2026-10-01T10:00:00Z", progression = 0.6)
        write("library:$INTO", INTO, writtenAt = "2026-10-01T12:00:00Z", progression = 0.3)

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val kept = database.linkedCopyWriteQueries.getWrite("library:$INTO").executeAsOne()
        assertEquals(0.3, kept.total_progression)
        assertNull(database.linkedCopyWriteQueries.getWrite("library:$FROM").executeAsOneOrNull())
    }

    private fun write(key: String, bookUuid: String, writtenAt: String, progression: Double) {
        database.linkedCopyWriteQueries.upsertWrite(
            key, bookUuid, "audiobookshelf:abs", "2026-10-01T09:00:00Z", writtenAt, null,
            "c1.xhtml", progression, progression, null,
        )
    }

    private fun book(id: String) = LibraryBookEntity(
        libraryBookId = id,
        title = "Book",
        addedAt = "2026-10-01T00:00:00Z",
    )

    private companion object {
        const val FROM = "11111111-1111-4111-8111-111111111111"
        const val INTO = "22222222-2222-4222-8222-222222222222"
    }
}
