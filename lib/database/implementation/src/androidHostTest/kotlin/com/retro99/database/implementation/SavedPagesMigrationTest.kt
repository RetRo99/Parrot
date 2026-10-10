package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.catalogue.CatalogueDocumentEntity
import com.retro99.database.implementation.dao.catalogue.CatalogueDocumentsSqlDelightDao
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 41.sqm (schema version 41 to 42): a saved page remembers the address it was served from. */
class SavedPagesMigrationTest {

    @Test
    fun `a new database is at version 42 or later and a saved page round-trips the address it was served from`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            AppDatabase.Schema.create(driver)
            val documents = CatalogueDocumentsSqlDelightDao { AppDatabase(driver) }
            val page = CatalogueDocumentEntity(
                sourceId = "source-1",
                accessGeneration = 3,
                requestUrl = "http://books.example/opds",
                contentType = "application/opds+json",
                eTag = "\"v1\"",
                lastModified = null,
                storedAt = 7,
                payload = byteArrayOf(1, 2, 3),
                effectiveUrl = "https://books.example/opds/",
            )

            // When
            documents.upsert(page)

            // Then
            assertTrue(AppDatabase.Schema.version >= 42L)
            val stored = assertNotNull(documents.get("source-1", 3, "http://books.example/opds"))
            assertEquals("https://books.example/opds/", stored.effectiveUrl)
            assertContentEquals(byteArrayOf(1, 2, 3), stored.payload)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `upgrading from version 41 keeps saved pages and gives them no served-from address`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(driver, readResource("v28_schema.sql"))
            AppDatabase.Schema.migrate(driver, oldVersion = 28, newVersion = 41)
            executeScript(
                driver,
                """
                INSERT INTO catalogue_documents(
                    source_id, access_generation, request_url, content_type, stored_at, payload, size_bytes
                ) VALUES ('source-1', 0, 'https://books.example/opds/', 'application/opds+json', 5, x'0102', 2)
                """.trimIndent(),
            )
            val before = schemaOf(driver)

            // When
            AppDatabase.Schema.migrate(driver, oldVersion = 41, newVersion = 42)

            // Then
            val page = assertNotNull(
                CatalogueDocumentsSqlDelightDao { AppDatabase(driver) }.get("source-1", 0, "https://books.example/opds/"),
            )
            assertContentEquals(byteArrayOf(1, 2), page.payload)
            assertEquals(5L, page.storedAt)
            assertNull(page.effectiveUrl)
            val after = schemaOf(driver)
            before.filterKeys { table -> table != "catalogue_documents" }.forEach { (table, definition) ->
                assertEquals(definition, after[table], "$table is unchanged by 41.sqm")
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `the upgraded saved-pages table matches the one a new database gets`() {
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Given
            executeScript(migrated, readResource("v28_schema.sql"))

            // When
            AppDatabase.Schema.migrate(migrated, oldVersion = 28, newVersion = AppDatabase.Schema.version)
            AppDatabase.Schema.create(fresh)

            // Then
            val table = schemaOf(fresh).getValue("catalogue_documents")
            assertTrue(table.any { definition -> definition.startsWith("column:effective_url:TEXT") })
            assertEquals(table, schemaOf(migrated)["catalogue_documents"])
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    private fun readResource(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
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
