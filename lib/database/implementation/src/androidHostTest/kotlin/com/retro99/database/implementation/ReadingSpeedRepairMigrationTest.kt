package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 35.sqm: reading speeds pinned at the old clamp bounds are artifacts and get repaired. */
class ReadingSpeedRepairMigrationTest {

    @Test
    fun `a setting pinned at the upper clamp bound is reset to the default`() = migrateOnce { driver ->
        // Given
        driver.execute(
            null,
            "INSERT INTO reader_settings(setting_key, setting_value) VALUES ('reading_speed_wpm', '1000')",
            0,
        )

        // When
        upgrade(driver)

        // Then
        val settings = AppDatabase(driver).readerSettingsQueries.getAllReaderSettings().executeAsOne()
        assertEquals("200", settings.setting_value)
    }

    @Test
    fun `a setting pinned at the lower clamp bound is reset to the default`() = migrateOnce { driver ->
        // Given
        driver.execute(
            null,
            "INSERT INTO reader_settings(setting_key, setting_value) VALUES ('reading_speed_wpm', '50')",
            0,
        )

        // When
        upgrade(driver)

        // Then
        val settings = AppDatabase(driver).readerSettingsQueries.getAllReaderSettings().executeAsOne()
        assertEquals("200", settings.setting_value)
    }

    @Test
    fun `plausible speeds are kept and only pinned sessions are cleared`() = migrateOnce { driver ->
        // Given
        driver.execute(
            null,
            "INSERT INTO reader_settings(setting_key, setting_value) VALUES ('reading_speed_wpm', '350')",
            0,
        )
        insertSession(driver, bookUuid = "pinned-high", readingSpeedWpm = 1000)
        insertSession(driver, bookUuid = "pinned-low", readingSpeedWpm = 50)
        insertSession(driver, bookUuid = "plausible", readingSpeedWpm = 350)

        // When
        upgrade(driver)

        // Then
        val settings = AppDatabase(driver).readerSettingsQueries.getAllReaderSettings().executeAsOne()
        assertEquals("350", settings.setting_value)
        val sessions = AppDatabase(driver).readingSessionQueries.getSessionsByBookUuid("pinned-high").executeAsOne()
        assertNull(sessions.reading_speed_wpm)
        assertNull(
            AppDatabase(driver).readingSessionQueries.getSessionsByBookUuid("pinned-low").executeAsOne().reading_speed_wpm,
        )
        assertEquals(
            350L,
            AppDatabase(driver).readingSessionQueries.getSessionsByBookUuid("plausible").executeAsOne().reading_speed_wpm,
        )
    }

    private fun migrateOnce(block: (JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            executeScript(driver, readResource("v28_schema.sql"))
            driver.execute(null, "PRAGMA user_version = 28", 0)
            block(driver)
        } finally {
            driver.close()
        }
    }

    private fun upgrade(driver: JdbcSqliteDriver) {
        AppDatabase.Schema.migrate(
            driver,
            oldVersion = MIN_MIGRATABLE_SCHEMA_VERSION,
            newVersion = AppDatabase.Schema.version,
        )
    }

    private fun insertSession(driver: JdbcSqliteDriver, bookUuid: String, readingSpeedWpm: Int) {
        driver.execute(
            null,
            "INSERT INTO reading_session(book_uuid, book_title, book_type, start_time, " +
                "end_time, duration_ms, reading_speed_wpm) VALUES (?, 'Book', 'ebook', 1, 2, 1, ?)",
            2,
        ) {
            bindString(0, bookUuid)
            bindLong(1, readingSpeedWpm.toLong())
        }
    }

    private fun readResource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().use { reader -> reader.readText() }

    private fun executeScript(driver: JdbcSqliteDriver, script: String) {
        script.split(';')
            .map { statement -> statement.trim() }
            .filter { statement ->
                statement.isNotEmpty() && !statement.startsWith("CREATE TABLE sqlite_sequence")
            }
            .forEach { statement -> driver.execute(null, statement, 0) }
    }
}
