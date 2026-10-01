package com.retro99.reader.domain.fakes

import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Positions by book uuid, local and remote, in memory. */
class FakePositionDatabase : PositionDatabase {
    val local = MutableStateFlow<Map<String, PositionEntity>>(emptyMap())
    val remote = mutableMapOf<String, PositionEntity>()
    val mutations = mutableListOf<SyncOutboxEntry>()

    override suspend fun upsertPosition(position: PositionEntity) {
        local.value = local.value + (position.bookUuid to position)
    }

    override suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    ) {
        upsertPosition(position)
        mutations += mutation
    }

    override suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) = Unit

    override suspend fun upsertRemotePosition(position: PositionEntity) {
        remote[position.bookUuid] = position
    }

    override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? =
        remote[bookUuid]

    override suspend fun deleteRemotePosition(bookUuid: String) {
        remote.remove(bookUuid)
    }

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? =
        local.value[bookUuid]

    override suspend fun getAllPositions(): List<PositionEntity> = local.value.values.toList()

    override suspend fun deletePosition(bookUuid: String) {
        local.value = local.value - bookUuid
    }

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> =
        local.map { positions -> positions[bookUuid] }

    override fun observeAllPositions(): Flow<List<PositionEntity>> =
        local.map { positions -> positions.values.toList() }

    override suspend fun clearAllData() {
        local.value = emptyMap()
        remote.clear()
    }
}

data class StoredPosition(
    override val bookUuid: String,
    override val totalProgression: Double? = null,
    override val progression: Double? = totalProgression,
    override val locatorHref: String? = "chapter-1.xhtml",
    override val audioTimestampMs: Long? = null,
    override val totalDurationMs: Long? = null,
    override val origin: String = PositionEntity.ORIGIN_USER,
    override val observedAt: String? = null,
    override val updatedAt: String? = observedAt,
    override val timestamp: Long? = null,
    override val remoteRevision: Long? = null,
    override val textAnchor: String? = null,
    override val cssSelector: String? = null,
    override val chapterIndex: Int? = null,
    override val totalChapters: Int? = null,
) : PositionEntity {
    override val createdAt: String? = updatedAt
    override val locatorType: String? = null
    override val locatorTitle: String? = null
    override val locatorTarget: Int? = null
    override val position: Int? = null
}
