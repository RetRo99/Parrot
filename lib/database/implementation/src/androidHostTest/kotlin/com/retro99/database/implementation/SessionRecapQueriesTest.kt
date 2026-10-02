package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.database.implementation.dao.recap.applyRecapRetention
import com.retro99.database.implementation.dao.recap.changed
import com.retro99.database.implementation.dao.recap.changedOne
import com.retro99.database.implementation.dao.recap.insertCapturingRow
import com.retro99.database.implementation.dao.recap.toEntity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRecapQueriesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase
    private val queries get() = database.sessionRecapQueries

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
    fun `a session gets one row and a second insert is ignored`() {
        assertTrue(database.insertCapturingRow(row("s1", createdAt = 1)))
        assertFalse(database.insertCapturingRow(row("s1", createdAt = 2)))

        val stored = queries.getRecap("s1").executeAsOne().toEntity()
        assertEquals("CAPTURING", stored.status)
        assertEquals(1, stored.createdAt)
    }

    @Test
    fun `sessions of the same book are kept as history`() {
        database.insertCapturingRow(row("s1", createdAt = 1))
        database.insertCapturingRow(row("s2", createdAt = 2))
        pending("s1")
        pending("s2")

        assertEquals(
            listOf("s2", "s1"),
            queries.observeForBook("book").executeAsList().map { it.session_id },
        )
    }

    @Test
    fun `only one caller can claim a due row`() {
        database.insertCapturingRow(row("s1", createdAt = 1))
        pending("s1")

        assertTrue(database.changedOne { claim("cloud", 10, "s1") })
        assertFalse(database.changedOne { claim("cloud", 10, "s1") })

        val stored = queries.getRecap("s1").executeAsOne()
        assertEquals("RUNNING", stored.status)
        assertEquals(1L, stored.attempt_count)
    }

    @Test
    fun `a row scheduled in the future is not due or claimable`() {
        database.insertCapturingRow(row("s1", createdAt = 1))
        pending("s1")
        queries.claim("cloud", 10, "s1")
        queries.fail("FAILED_RETRYABLE", 1, 500, "RATE_LIMITED", 10, "s1")

        assertNull(queries.getNextDueId(100).executeAsOneOrNull())
        assertFalse(database.changedOne { claim("cloud", 100, "s1") })
        assertEquals(500L, queries.getEarliestScheduled(100).executeAsOne().MIN)
        assertEquals("s1", queries.getNextDueId(500).executeAsOne())
    }

    @Test
    fun `stale running rows go back to pending`() {
        database.insertCapturingRow(row("old", createdAt = 1))
        database.insertCapturingRow(row("fresh", createdAt = 2))
        pending("old")
        pending("fresh")
        queries.claim("cloud", 100, "old")
        queries.claim("cloud", 900, "fresh")

        assertEquals(1L, database.changed { recoverStaleRunning(now = 1000, staleBefore = 500) })
        assertEquals("PENDING", queries.getRecap("old").executeAsOne().status)
        assertEquals("RUNNING", queries.getRecap("fresh").executeAsOne().status)
    }

    @Test
    fun `completing drops the excerpt and keeps the summary`() {
        database.insertCapturingRow(row("s1", createdAt = 1))
        pending("s1")
        queries.claim("cloud", 10, "s1")

        assertTrue(database.changedOne { complete("SUCCEEDED", "Summary.", "hy3", 20, "s1") })

        val stored = queries.getRecap("s1").executeAsOne()
        assertNull(stored.excerpt)
        assertNull(stored.last_sentence)
        assertEquals("Summary.", stored.summary)
        assertEquals("hy3", stored.model)
        assertEquals(20L, stored.generated_at)
    }

    @Test
    fun `retention drops the last sentence of finished rows`() {
        upsertBook("book")
        database.insertCapturingRow(row("s1", createdAt = 1_000))
        pending("s1")
        queries.claim("cloud", 1_000, "s1")
        // A row finished before last_sentence was cleared on completion.
        queries.complete("NOT_ENOUGH", null, null, 1_000, "s1")
        driver.execute(null, "UPDATE session_recap SET last_sentence = 'Old.'", 0)

        database.applyRecapRetention(excerptCutoff = 500, rowCutoff = 50, now = 2_000)

        assertNull(queries.getRecap("s1").executeAsOne().last_sentence)
    }

    @Test
    fun `latest for book prefers a succeeded recap`() {
        database.insertCapturingRow(row("done", createdAt = 1))
        database.insertCapturingRow(row("newer", createdAt = 2))
        database.insertCapturingRow(row("skipped", createdAt = 3))
        database.insertCapturingRow(row("open", createdAt = 4))
        pending("done")
        pending("newer")
        finish("skipped", "SKIPPED_INELIGIBLE")
        queries.claim("cloud", 10, "done")
        queries.complete("SUCCEEDED", "Summary.", null, 10, "done")

        assertEquals("done", queries.observeLatestForBook("book").executeAsOne().session_id)

        queries.deleteRecap("done")
        assertEquals("newer", queries.observeLatestForBook("book").executeAsOne().session_id)
    }

    @Test
    fun `requeue resets a failed row with text`() {
        database.insertCapturingRow(row("s1", createdAt = 1))
        pending("s1")
        queries.claim("cloud", 10, "s1")
        queries.fail("FAILED_PERMANENT", 5, null, "PROVIDER_ERROR", 10, "s1")

        assertTrue(database.changedOne { requeue(20, "s1") })
        val stored = queries.getRecap("s1").executeAsOne()
        assertEquals("PENDING", stored.status)
        assertEquals(0L, stored.attempt_count)
        assertNull(stored.last_error)
    }

    @Test
    fun `retention expires old excerpts and deletes old or orphaned rows`() {
        upsertBook("book")
        database.insertCapturingRow(row("ancient", createdAt = 10))
        database.insertCapturingRow(row("stale", createdAt = 100))
        database.insertCapturingRow(row("fresh", createdAt = 1_000))
        database.insertCapturingRow(row("orphan", createdAt = 1_000, bookUuid = "gone"))
        database.insertCapturingRow(row("live", createdAt = 1_000, bookUuid = "gone"))
        listOf("ancient", "stale", "fresh", "orphan").forEach(::pending)

        val deleted = database.applyRecapRetention(excerptCutoff = 500, rowCutoff = 50, now = 2_000)

        assertEquals(2L, deleted)
        assertNull(queries.getRecap("ancient").executeAsOneOrNull())
        assertNull(queries.getRecap("orphan").executeAsOneOrNull())
        val stale = queries.getRecap("stale").executeAsOne()
        assertNull(stale.excerpt)
        assertNull(stale.last_sentence)
        assertEquals("FAILED_PERMANENT", stale.status)
        assertEquals("EXCERPT_EXPIRED", stale.last_error)
        assertNotNull(queries.getRecap("fresh").executeAsOne().excerpt)
        assertNotNull(queries.getRecap("fresh").executeAsOne().last_sentence)
        // A session still capturing is never touched.
        assertEquals("CAPTURING", queries.getRecap("live").executeAsOne().status)
    }

    @Test
    fun `merging library books moves recaps`() {
        database.insertCapturingRow(row("s1", createdAt = 1, bookUuid = "from"))

        queries.moveRecaps("into", "from")

        assertEquals("into", queries.getRecap("s1").executeAsOne().book_uuid)
    }

    private fun upsertBook(uuid: String) {
        database.bookQueries.upsertBook(
            uuid, "server", null, 1, "Title", null, null, null, null, null,
            null, null, null, null, null, null, null, null, null,
        )
    }

    private fun pending(sessionId: String) = finish(sessionId, "PENDING")

    private fun finish(sessionId: String, status: String) {
        queries.finishCapture(
            status = status,
            excerpt = "Some read text.",
            excerptHash = "h",
            lastSentence = "She stopped here.",
            endHref = null,
            endProgression = null,
            endTotalProgression = null,
            endChapterIndex = null,
            endChapterTitle = null,
            furthestTotalProgression = null,
            pageAdvances = 2,
            ttsSentences = 0,
            activeReadingMs = 1,
            lastError = null,
            endedAt = 5,
            sessionId = sessionId,
        )
    }

    private fun row(
        sessionId: String,
        createdAt: Long,
        bookUuid: String = "book",
    ) = SessionRecapEntity(
        sessionId = sessionId,
        serverId = "server",
        bookUuid = bookUuid,
        status = "CAPTURING",
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
