package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationChainTest {
    @Test
    fun `all migrations from immutable baseline produce the fresh schema`() {
        // Given
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val schema = javaClass.classLoader!!.getResourceAsStream("v28_schema.sql")!!
                .bufferedReader().use { reader -> reader.readText() }
            schema.split(';').map { statement -> statement.trim() }
                .filter { statement ->
                    // SQLite creates this internal table when creating AUTOINCREMENT tables.
                    statement.isNotEmpty() && !statement.startsWith("CREATE TABLE sqlite_sequence")
                }
                .forEach { statement -> migrated.execute(null, statement, 0) }
            migrated.execute(null, "PRAGMA user_version = 28", 0)

            // When
            AppDatabase.Schema.migrate(
                migrated,
                oldVersion = MIN_MIGRATABLE_SCHEMA_VERSION,
                newVersion = AppDatabase.Schema.version,
            )
            AppDatabase.Schema.create(fresh)

            // Then
            assertEquals(schemaOf(fresh), schemaOf(migrated), "migration chain from 28")
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
