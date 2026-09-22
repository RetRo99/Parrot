package com.retro99.sync.data

import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicatePositionRepairTest {
    @Test
    fun newestPositionWinsAndCanonicalUuidReplacesDuplicateRows() = runTest {
        val winner = testPosition(
            bookUuid = "canonical-uuid",
            remoteRevision = 8L,
            updatedAt = "2026-09-22T08:00:00Z",
            localGeneration = 12L,
        )
        val olderDuplicate = testPosition(
            bookUuid = "duplicate-uuid",
            remoteRevision = 7L,
            updatedAt = "2026-09-22T09:00:00Z",
            localGeneration = 11L,
        )
        val database = DuplicateRecordingPositionDatabase(listOf(winner, olderDuplicate))
        val resolver = LocalBookUuidResolver { _, _, fallback ->
            assertEquals("canonical-uuid", fallback)
            "canonical-uuid"
        }

        DuplicatePositionRepair(database, resolver).repair()

        assertEquals(listOf("duplicate-uuid"), database.deletedBookUuids)
        assertEquals(1, database.upsertedPositions.size)
        assertEquals("canonical-uuid", database.upsertedPositions.single().bookUuid)
        assertEquals(8L, database.upsertedPositions.single().remoteRevision)
        assertEquals(12L, database.upsertedPositions.single().localGeneration)
        assertTrue(database.upsertedPositions.single().libraryBookId.isNotBlank())
    }

    @Test
    fun uniqueAndUnscopedPositionsRemainUntouched() = runTest {
        val unique = testPosition(bookUuid = "unique", libraryBookId = "library-unique")
        val unscoped = testPosition(bookUuid = "unscoped", libraryBookId = "")
        val database = DuplicateRecordingPositionDatabase(listOf(unique, unscoped))

        DuplicatePositionRepair(
            positionDatabase = database,
            localBookUuidResolver = LocalBookUuidResolver { _, _, fallback -> fallback },
        ).repair()

        assertTrue(database.deletedBookUuids.isEmpty())
        assertTrue(database.upsertedPositions.isEmpty())
    }
}

private class DuplicateRecordingPositionDatabase(
    private val positions: List<PositionEntity>,
) : PositionDatabase {
    val deletedBookUuids = mutableListOf<String>()
    val upsertedPositions = mutableListOf<PositionEntity>()

    override suspend fun upsertPosition(position: PositionEntity) {
        upsertedPositions += position
    }

    override suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    ) = Unit

    override suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) = Unit

    override suspend fun upsertRemotePosition(position: PositionEntity) = Unit

    override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? = null

    override suspend fun deleteRemotePosition(bookUuid: String) = Unit

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? = null

    override suspend fun getAllPositions(): List<PositionEntity> = positions

    override suspend fun deletePosition(bookUuid: String) {
        deletedBookUuids += bookUuid
    }

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = emptyFlow()

    override fun observeAllPositions(): Flow<List<PositionEntity>> = emptyFlow()

    override suspend fun clearAllData() = Unit
}

private data class TestPosition(
    override val bookUuid: String,
    override val libraryBookId: String,
    override val localGeneration: Long,
    override val remoteRevision: Long?,
    override val updatedAt: String?,
) : PositionEntity {
    override val timestamp: Long? = null
    override val createdAt: String? = null
    override val locatorHref: String? = null
    override val locatorType: String? = null
    override val locatorTitle: String? = null
    override val locatorTarget: Int? = null
    override val cssSelector: String? = null
    override val audioTimestampMs: Long? = null
    override val chapterIndex: Int? = null
    override val progression: Double? = null
    override val totalChapters: Int? = null
    override val totalDurationMs: Long? = null
    override val totalProgression: Double? = null
    override val position: Int? = null
}

private fun testPosition(
    bookUuid: String,
    libraryBookId: String = "library-book",
    remoteRevision: Long? = null,
    updatedAt: String? = null,
    localGeneration: Long = 0L,
): PositionEntity {
    return TestPosition(
        bookUuid = bookUuid,
        libraryBookId = libraryBookId,
        localGeneration = localGeneration,
        remoteRevision = remoteRevision,
        updatedAt = updatedAt,
    )
}
