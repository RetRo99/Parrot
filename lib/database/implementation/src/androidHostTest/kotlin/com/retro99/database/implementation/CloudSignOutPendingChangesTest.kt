package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.sync.enqueue
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudSignOutPendingChangesTest {
    @Test
    fun `sign out preview includes first sync changes but never another account`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AppDatabase.Schema.create(driver)
            val database = AppDatabase(driver)
            fun entry(id: String, account: String?) = SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                entityId = id,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = "{}",
                cloudUserId = account,
            )
            val unassigned = entry("new-book", null)
            val own = entry("my-book", "me")
            val other = entry("other-book", "someone-else")
            listOf(unassigned, own, other).forEach { database.syncOutboxQueries.enqueue(it) }
            assertEquals(
                setOf(unassigned.mutationId, own.mutationId),
                database.syncOutboxQueries.getPendingIncludingUnassigned("me").executeAsList().map { it.mutation_id }.toSet(),
            )
            // Preview must not bind or remove anything.
            assertEquals(null, database.syncOutboxQueries.getPendingIncludingUnassigned("me").executeAsList().first { it.mutation_id == unassigned.mutationId }.cloud_user_id)
        } finally {
            driver.close()
        }
    }
}
