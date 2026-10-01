package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.implementation.dao.library.upsertLibraryBookRow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PositionLibraryBookIdTest {

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
    fun `a library book position stores the book id`() {
        // Given
        database.upsertLibraryBookRow(book(BOOK_ID))

        // When
        insertPosition(BOOK_ID)

        // Then
        val position = database.positionQueries.getPositionByBookUuid(BOOK_ID).executeAsOne()
        assertEquals(BOOK_ID, position.library_book_id)
    }

    @Test
    fun `a server book position has no library book id`() {
        // When
        insertPosition("storyteller-book")

        // Then
        val position = database.positionQueries.getPositionByBookUuid("storyteller-book")
            .executeAsOne()
        assertNull(position.library_book_id)
    }

    @Test
    fun `a position saved before its book arrived joins the book`() {
        // Given
        insertPosition(BOOK_ID)

        // When
        database.upsertLibraryBookRow(book(BOOK_ID))

        // Then
        val position = database.positionQueries.getPositionByBookUuid(BOOK_ID).executeAsOne()
        assertEquals(BOOK_ID, position.library_book_id)
    }

    private fun book(id: String) = LibraryBookEntity(
        libraryBookId = id,
        title = "Book",
        addedAt = "2026-10-01T00:00:00Z",
    )

    private fun insertPosition(bookUuid: String) {
        database.positionQueries.upsertPosition(
            bookUuid, bookUuid, 0, null, null, "a", "a", "c1", null, null, null,
            null, null, null, 0.1, null, null, 0.1, null, "user", null, null, null, null,
        )
    }

    private companion object {
        const val BOOK_ID = "33333333-3333-4333-8333-333333333333"
    }
}
