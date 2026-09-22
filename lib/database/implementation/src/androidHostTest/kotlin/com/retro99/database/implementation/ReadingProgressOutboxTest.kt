package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.sync.enqueue
import com.retro99.database.implementation.dao.sync.toEntry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingProgressOutboxTest {

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
    fun newerSameBaseRevisionUpdatesPreserveTheDispatchedMutation() {
        val first = progressMutation("first", localGeneration = 1L)
        val second = progressMutation("second", localGeneration = 2L)
        val third = progressMutation("third", localGeneration = 3L)

        database.syncOutboxQueries.enqueue(first)
        database.syncOutboxQueries.markMutationDispatched(first.mutationId)
        database.syncOutboxQueries.enqueue(second)
        database.syncOutboxQueries.enqueue(third)

        val pending = database.syncOutboxQueries
            .getPendingMutations("cloud-user")
            .executeAsList()
            .map { it.toEntry() }

        assertEquals(
            listOf(first.mutationId, third.mutationId),
            pending.map { it.mutationId },
        )
        assertEquals(
            listOf(SyncOutboxEntry.STATE_DISPATCHED, SyncOutboxEntry.STATE_PENDING),
            pending.map { it.state },
        )
        assertEquals(listOf(1L, 3L), pending.map { it.localGeneration })
        assertEquals(listOf(7L, 7L), pending.map { it.baseRevision })
    }

    @Test
    fun conflictPreservedMutationSurvivesAStillNewerUpdate() {
        val first = progressMutation("first", localGeneration = 1L)
        val conflicted = progressMutation("conflicted", localGeneration = 2L)
        val newer = progressMutation("newer", localGeneration = 3L)

        database.syncOutboxQueries.enqueue(first)
        database.syncOutboxQueries.markMutationDispatched(first.mutationId)
        database.syncOutboxQueries.enqueue(conflicted)
        database.syncOutboxQueries.markMutationConflict(
            last_error = "remote revision changed",
            mutation_id = conflicted.mutationId,
        )
        database.syncOutboxQueries.enqueue(newer)

        val pending = database.syncOutboxQueries
            .getPendingMutations("cloud-user")
            .executeAsList()
            .map { it.toEntry() }

        assertEquals(
            listOf(first.mutationId, conflicted.mutationId, newer.mutationId),
            pending.map { it.mutationId },
        )
        assertEquals(
            listOf(
                SyncOutboxEntry.STATE_DISPATCHED,
                SyncOutboxEntry.STATE_CONFLICT_PRESERVED,
                SyncOutboxEntry.STATE_PENDING,
            ),
            pending.map { it.state },
        )
        assertEquals(
            listOf(first.mutationId, newer.mutationId),
            database.syncOutboxQueries
                .getEligibleMutations("cloud-user", "9999-01-01T00:00:00Z")
                .executeAsList()
                .map { it.mutation_id },
        )
    }

    private fun progressMutation(
        value: String,
        localGeneration: Long,
    ): SyncOutboxEntry {
        return SyncOutboxEntry.new(
            cloudUserId = "cloud-user",
            entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
            entityId = "book-1",
            operation = SyncOutboxEntry.OPERATION_UPSERT,
            payload = "{\"position\":\"$value\"}",
            baseRevision = 7L,
        ).copy(localGeneration = localGeneration)
    }
}
