package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class SafeDatabaseOpenerTest {
    @Test
    fun `unknown version recreates before opening and records version afterwards`() {
        // Given
        withDatabase { fileOps, preferences, analytics ->
            // When
            SafeDatabaseOpener(preferences, analytics).open(USER_ID, fileOps).close()

            // Then
            assertEquals(1, fileOps.deletions)
            assertEquals(listOf("delete", "create", "force", "record"), fileOps.events.take(4))
            assertEquals(AppDatabase.Schema.version, preferences.getLong(versionKey))
            assertTrue(analytics.exceptions.isEmpty())
        }
    }

    @Test
    fun `upgrade from baseline preserves local books and advances both versions`() {
        // Given
        withDatabase { fileOps, preferences, analytics ->
            JdbcSqliteDriver(fileOps.url).use { driver ->
                val schema = javaClass.classLoader!!.getResourceAsStream("v28_schema.sql")!!
                    .bufferedReader().use { reader -> reader.readText() }
                schema.split(';').map { statement -> statement.trim() }
                    .filter { statement ->
                        statement.isNotEmpty() && !statement.startsWith("CREATE TABLE sqlite_sequence")
                    }
                    .forEach { statement -> driver.execute(null, statement, 0) }
                driver.execute(null, "PRAGMA user_version = 28", 0)
                driver.execute(
                    null,
                    "INSERT INTO library_books(library_book_id, title, added_at) " +
                        "VALUES ('local-book', 'Unsynced book', '2026-10-01')",
                    0,
                )
            }
            preferences.putLong(versionKey, 28)
            fileOps.events.clear()

            // When
            SafeDatabaseOpener(preferences, analytics).open(USER_ID, fileOps).use { driver ->
                // Then
                assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM library_books"))
                assertEquals(AppDatabase.Schema.version, scalar(driver, "PRAGMA user_version"))
            }
            assertEquals(0, fileOps.deletions)
            assertEquals(AppDatabase.Schema.version, preferences.getLong(versionKey))
            assertTrue(analytics.exceptions.isEmpty())
        }
    }

    @Test
    fun `failed forced open closes driver and recreates once`() {
        // Given
        withDatabase { fileOps, preferences, analytics ->
            preferences.putLong(versionKey, 28)
            fileOps.events.clear()
            fileOps.failForcedOpens = 1

            // When
            SafeDatabaseOpener(preferences, analytics).open(USER_ID, fileOps).close()

            // Then
            assertEquals(1, fileOps.deletions)
            assertEquals(
                listOf("create", "force", "close", "delete", "create", "force", "record"),
                fileOps.events.take(7),
            )
            assertSame(fileOps.failure, analytics.exceptions.single().first)
            assertEquals(
                "Database migration failed; recreating",
                analytics.exceptions.single().second,
            )
            assertEquals(AppDatabase.Schema.version, preferences.getLong(versionKey))
        }
    }

    @Test
    fun `failed driver construction also recreates once`() {
        // Given
        withDatabase { fileOps, preferences, analytics ->
            preferences.putLong(versionKey, 28)
            fileOps.failCreations = 1

            // When
            SafeDatabaseOpener(preferences, analytics).open(USER_ID, fileOps).close()

            // Then
            assertEquals(1, fileOps.deletions)
            assertEquals(2, fileOps.creations)
            assertSame(fileOps.failure, analytics.exceptions.single().first)
        }
    }

    @Test
    fun `failed recovery closes driver and does not record current version`() {
        // Given
        withDatabase { fileOps, preferences, analytics ->
            preferences.putLong(versionKey, 28)
            fileOps.events.clear()
            fileOps.failForcedOpens = 2

            // When
            val failure = assertFailsWith<IllegalStateException> {
                SafeDatabaseOpener(preferences, analytics).open(USER_ID, fileOps)
            }

            // Then
            assertSame(fileOps.failure, failure)
            assertEquals(28L, preferences.getLong(versionKey))
            assertEquals(2, fileOps.events.count { event -> event == "close" })
            assertEquals(1, fileOps.deletions)
            assertEquals(1, analytics.exceptions.size)
            assertTrue("record" !in fileOps.events)
        }
    }

    @Test
    fun `downgrade still recreates the database`() {
        // Given
        withDatabase { fileOps, preferences, analytics ->
            preferences.putLong(versionKey, 99)

            // When
            SafeDatabaseOpener(preferences, analytics).open(USER_ID, fileOps).close()

            // Then
            assertEquals(1, fileOps.deletions)
            assertEquals(AppDatabase.Schema.version, preferences.getLong(versionKey))
        }
    }

    private fun withDatabase(
        test: (FileOps, FakePreferences, RecordingAnalytics) -> Unit,
    ) {
        val path = Files.createTempFile("database-opener", ".db")
        val events = mutableListOf<String>()
        try {
            test(FileOps(path.toFile(), events), FakePreferences(events), RecordingAnalytics())
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        },
        0,
    ).value

    private class FileOps(
        private val file: java.io.File,
        val events: MutableList<String>,
    ) : DatabaseFileOps {
        val url = "jdbc:sqlite:${file.absolutePath}"
        val failure = IllegalStateException("migration failure")
        var deletions = 0
        var creations = 0
        var failForcedOpens = 0
        var failCreations = 0

        override fun deleteDatabaseFile(): Boolean {
            events += "delete"
            deletions++
            return file.delete()
        }

        override fun createDriver(): SqlDriver {
            events += "create"
            creations++
            if (failCreations-- > 0) throw failure
            val driver = JdbcSqliteDriver(url, schema = AppDatabase.Schema)
            return object : SqlDriver by driver {
                override fun execute(
                    identifier: Int?,
                    sql: String,
                    parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<Long> {
                    // Android execute uses executeUpdateDelete, not a row-returning query API.
                    check(!sql.startsWith("PRAGMA") && !sql.startsWith("SELECT"))
                    return driver.execute(identifier, sql, parameters, binders)
                }

                override fun <R> executeQuery(
                    identifier: Int?,
                    sql: String,
                    mapper: (SqlCursor) -> QueryResult<R>,
                    parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<R> {
                    events += "force"
                    if (failForcedOpens-- > 0) throw failure
                    return driver.executeQuery(identifier, sql, mapper, parameters, binders)
                }

                override fun close() {
                    events += "close"
                    driver.close()
                }
            }
        }
    }

    private class FakePreferences(private val events: MutableList<String>) : Preferences {
        private val values = mutableMapOf<PreferencesKey, Long>()
        override fun getLong(key: PreferencesKey, defaultValue: Long): Long =
            values[key] ?: defaultValue

        override fun putLong(key: PreferencesKey, value: Long) {
            events += "record"
            values[key] = value
        }

        override fun getStringOrNull(key: PreferencesKey): String? = null
        override fun putString(key: PreferencesKey, value: String) = Unit
        override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = flowOf(null)
        override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean = defaultValue
        override fun putBoolean(key: PreferencesKey, value: Boolean) = Unit
        override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
            flowOf(defaultValue)

        override fun remove(key: PreferencesKey) {
            values.remove(key)
        }
    }

    private class RecordingAnalytics : Analytics {
        val exceptions = mutableListOf<Pair<Throwable, String?>>()
        override fun logException(throwable: Throwable, message: String?) {
            exceptions += throwable to message
        }

        override fun logEvent(event: AnalyticsEvent) = Unit
        override fun setUserId(userId: String?) = Unit
    }

    private companion object {
        const val USER_ID = "local-profile"
        val versionKey = PreferencesKey.UserScoped(USER_ID, "DatabaseSchemaVersion")
    }
}
