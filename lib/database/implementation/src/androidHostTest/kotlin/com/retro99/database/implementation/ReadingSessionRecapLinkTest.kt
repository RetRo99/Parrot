package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.database.api.statistics.ReadingSessionEntity
import com.retro99.database.implementation.dao.library.deleteBookFromDeviceRows
import com.retro99.database.implementation.dao.recap.insertCapturingRow
import com.retro99.database.implementation.dao.statistics.ReadingSessionDatabaseImpl
import com.retro99.database.implementation.dao.statistics.ReadingSessionSqlDelightDao
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 33.sqm: statistics sessions point at the recap of the same reader session. */
class ReadingSessionRecapLinkTest {
    @Test
    fun `full supported upgrade preserves statistics history and aggregate totals`() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { migrated ->
            executeScript(migrated, readResource("v28_schema.sql"))
            for (index in 1..3) {
                migrated.execute(null,
                    "INSERT INTO reading_session(book_uuid, book_title, book_type, start_time, end_time, duration_ms, " +
                        "pages_read, start_progression, end_progression, reading_speed_wpm) " +
                        "VALUES ('book-$index', 'Title $index', 'ebook', ${index * 1000}, ${index * 1000 + 60000}, " +
                        "60000, 5, 0.2, 0.4, 275)", 0)
            }
            AppDatabase.Schema.migrate(migrated, 28, AppDatabase.Schema.version)
            val queries = AppDatabase(migrated).readingSessionQueries
            val rows = queries.getAllSessions().executeAsList()
            assertEquals(3, rows.size)
            assertEquals(180_000L, queries.getTotalReadingTimeMs().executeAsOne())
            assertEquals(listOf(3L, 2L, 1L), rows.map { it.id })
            rows.forEach {
                assertEquals("Title ${it.id}", it.book_title)
                assertEquals(5L, it.pages_read)
                assertEquals(0.2, it.start_progression)
                assertEquals(0.4, it.end_progression)
                assertEquals(275L, it.reading_speed_wpm)
                assertNull(it.recap_session_id)
            }
        }
    }

    @Test
    fun `upgrading a database with recap links keeps links and session metadata`() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { migrated ->
            executeScript(migrated, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(migrated, 28, 34)
            migrated.execute(null,
                "INSERT INTO reading_session(book_uuid, book_title, book_type, start_time, end_time, duration_ms, " +
                    "reading_speed_wpm, recap_session_id) VALUES ('book', 'Book', 'ebook', 1000, 61000, 60000, 275, 'recap-1')", 0)
            // Seed using the historical schema, not today's generated recap queries.
            migrated.execute(null,
                "INSERT INTO session_recap(session_id, server_id, book_uuid, status, created_at, updated_at) " +
                    "VALUES ('recap-1', 'server', 'book', 'CAPTURING', 1, 1)", 0)
            AppDatabase.Schema.migrate(migrated, 34, AppDatabase.Schema.version)
            val upgraded = AppDatabase(migrated)
            val row = upgraded.readingSessionQueries.getAllSessions().executeAsOne()
            assertEquals("recap-1", row.recap_session_id)
            assertEquals(60_000L, row.duration_ms)
            assertEquals(275L, row.reading_speed_wpm)
            assertNotNull(upgraded.sessionRecapQueries.getRecap("recap-1").executeAsOneOrNull())
        }
    }

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var databaseManager: DatabaseManager
    private lateinit var sessions: ReadingSessionDatabaseImpl
    private val database get() = AppDatabase(driver)

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        databaseManager = DatabaseManager(
            userRegistry = FakeUserRegistry,
            driverFactory = object : SqlDriverFactory {
                override fun createDriver(userId: String): SqlDriver = driver
                override fun deleteUserDatabase(userId: String): Boolean = true
            },
        )
        sessions = ReadingSessionDatabaseImpl(ReadingSessionSqlDelightDao(databaseManager))
    }

    @AfterTest
    fun tearDown() {
        runBlocking { databaseManager.close() }
    }

    @Test
    fun `existing sessions migrate without a recap link`() {
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(migrated, readResource("v28_schema.sql"))
            migrated.execute(
                null,
                "INSERT INTO reading_session(book_uuid, book_title, book_type, start_time, " +
                    "end_time, duration_ms) VALUES ('book', 'Book', 'ebook', 1, 2, 1)",
                0,
            )

            // When
            AppDatabase.Schema.migrate(
                migrated,
                oldVersion = 28,
                newVersion = AppDatabase.Schema.version,
            )

            // Then
            val row = AppDatabase(migrated).readingSessionQueries.getAllSessions().executeAsOne()
            assertEquals("book", row.book_uuid)
            assertNull(row.recap_session_id)
            assertEquals(
                1L,
                count(
                    migrated,
                    "SELECT COUNT(*) FROM pragma_table_info('reading_session') " +
                        "WHERE name = 'recap_session_id'",
                ),
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a saved session keeps its recap link`() = withProfile {
        // When
        sessions.insertSession(session(recapSessionId = "r1"))

        // Then
        assertEquals("r1", sessions.getAllSessions().single().recapSessionId)
    }

    @Test
    fun `deleting a session deletes only its linked recap`() = withProfile {
        // Given
        recap("r1")
        recap("r2")
        sessions.insertSession(session(recapSessionId = "r1", startTime = 1))
        sessions.insertSession(session(recapSessionId = "r2", startTime = 2))
        val first = sessions.getAllSessions().single { it.recapSessionId == "r1" }

        // When
        sessions.deleteSession(first.id)

        // Then
        assertNull(recapRow("r1"))
        assertNotNull(recapRow("r2"))
    }

    @Test
    fun `clearing sessions deletes linked recaps and keeps unlinked ones`() = withProfile {
        // Given
        recap("linked")
        recap("open")
        sessions.insertSession(session(recapSessionId = "linked"))
        sessions.insertSession(session(recapSessionId = null, startTime = 2))

        // When
        sessions.deleteAllSessions()

        // Then
        assertTrue(sessions.getAllSessions().isEmpty())
        assertNull(recapRow("linked"))
        assertNotNull(recapRow("open"))
    }

    @Test
    fun `removing a book from the device deletes its recaps unless a server has it`() {
        // Given
        recap("local", bookUuid = "local-book")
        recap("served", bookUuid = "server-book")
        driver.execute(
            null,
            "INSERT INTO books(uuid, server_id, id, title) VALUES ('server-book', 'server', 1, 'Book')",
            0,
        )

        // When
        database.deleteBookFromDeviceRows("local-book")
        database.deleteBookFromDeviceRows("server-book")

        // Then
        assertNull(recapRow("local"))
        assertNotNull(recapRow("served"))
    }

    private fun withProfile(block: suspend () -> Unit) = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) { block() }
    }

    private fun recap(sessionId: String, bookUuid: String = "book") {
        database.insertCapturingRow(
            SessionRecapEntity(
                sessionId = sessionId,
                serverId = "server",
                bookUuid = bookUuid,
                status = "CAPTURING",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun recapRow(sessionId: String) =
        database.sessionRecapQueries.getRecap(sessionId).executeAsOneOrNull()

    private fun session(recapSessionId: String?, startTime: Long = 1) = TestSession(
        startTime = startTime,
        recapSessionId = recapSessionId,
    )

    private data class TestSession(
        override val startTime: Long,
        override val recapSessionId: String?,
        override val id: Long = 0,
        override val bookUuid: String = "book",
        override val bookTitle: String = "Book",
        override val bookType: String = "ebook",
        override val endTime: Long = startTime + 1,
        override val durationMs: Long = 1,
        override val pagesRead: Int? = null,
        override val startProgression: Double? = null,
        override val endProgression: Double? = null,
        override val readingSpeedWpm: Int? = null,
    ) : ReadingSessionEntity

    private fun readResource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().use { reader -> reader.readText() }

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

    /** Never emits, so the database opens only via [DatabaseManager.withProfile]. */
    private object FakeUserRegistry : UserRegistry {
        override fun observeAllProfiles(): Flow<List<UserProfile>> = emptyFlow()
        override suspend fun getAllProfiles(): List<UserProfile> = emptyList()
        override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile =
            error("Not used")
        override suspend fun updateProfile(profile: UserProfile) = Unit
        override suspend fun deleteProfile(profileId: String) = Unit
        override suspend fun getProfile(profileId: String): UserProfile? = null
        override fun observeActiveProfile(): Flow<UserProfile?> = emptyFlow()
        override suspend fun getActiveProfile(): UserProfile? = null
        override fun getActiveProfileId(): String? = null
        override suspend fun setActiveProfile(profileId: String) = Unit
        override suspend fun clearActiveProfile() = Unit
        override suspend fun hasProfiles(): Boolean = false
        override fun isProfileActive(): Boolean = false
    }
}
