package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.implementation.dao.sync.toCheckpoint
import com.retro99.database.implementation.dao.sync.upsert
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncCheckpointDatabaseTest {

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
    fun checkpointPreservesOpaqueCursorPerDestinationAndAccount() {
        val checkpoint = SyncCheckpoint(
            destinationId = "parrot-cloud",
            remoteAccountId = "account-a",
            cursor = "opaque:0007/commit-token",
            updatedAt = "2026-09-22T12:00:00Z",
            status = "up_to_date",
            pendingMutationCount = 0,
            lastSuccessfulAt = "2026-09-22T12:00:00Z",
            lastError = null,
        )

        database.syncCheckpointQueries.upsert(checkpoint)

        assertEquals(
            checkpoint,
            database.syncCheckpointQueries
                .getCheckpoint("parrot-cloud", "account-a")
                .executeAsOne()
                .toCheckpoint(),
        )
        assertEquals(
            null,
            database.syncCheckpointQueries
                .getCheckpoint("parrot-cloud", "account-b")
                .executeAsOneOrNull(),
        )
    }
}
