package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.implementation.dao.links.readBookLink
import com.retro99.database.implementation.dao.links.writeBookLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookLinkValidationTest {
    @Test
    fun `transaction refuses stale membership without changing stored links`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AppDatabase.Schema.create(driver)
            val database = AppDatabase(driver)
            val original = BookLinkEntity("link", listOf("library:b1", "storyteller:s1"),
                "2026-10-05T12:00:00Z", "2026-10-05T12:00:00Z")
            database.writeBookLinks(BookLinkWrite(links = listOf(original)))
            val expected = mapOf(original.linkId to original.members.toSet())
            val synced = original.copy(members = original.members + "audiobookshelf:a2")
            database.writeBookLinks(BookLinkWrite(links = listOf(synced)))

            assertFailsWith<IllegalStateException> {
                database.writeBookLinks(BookLinkWrite(
                    links = listOf(original.copy(members = original.members + "audiobookshelf:a1")),
                    expectedMemberships = expected,
                ))
            }
            assertEquals(synced.members.toSet(), database.readBookLink("link")?.members?.toSet())
        } finally {
            driver.close()
        }
    }
}
