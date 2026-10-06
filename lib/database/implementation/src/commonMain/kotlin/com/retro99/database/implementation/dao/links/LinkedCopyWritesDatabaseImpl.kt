package com.retro99.database.implementation.dao.links

import com.retro99.database.api.links.LinkedCopyWriteEntity
import com.retro99.database.api.links.LinkedCopyWritesDatabase
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Linked_copy_writes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal class LinkedCopyWritesDatabaseImpl(
    private val databaseManager: DatabaseManager,
) : LinkedCopyWritesDatabase {
    private val queries get() = databaseManager.getDatabase().linkedCopyWriteQueries

    override suspend fun replace(write: LinkedCopyWriteEntity, deleteWrittenBefore: String) =
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().transaction {
                queries.deleteWrittenBefore(deleteWrittenBefore)
                queries.upsertWrite(
                    target_key = write.targetKey,
                    target_book_uuid = write.targetBookUuid,
                    source_key = write.sourceKey,
                    source_observed_at = write.sourceObservedAt,
                    written_at = write.writtenAt,
                    marker = write.marker,
                    locator_href = write.locatorHref,
                    progression = write.progression,
                    total_progression = write.totalProgression,
                    audio_ms = write.audioMs,
                )
            }
        }

    override suspend fun getWrite(targetKey: String, notBefore: String): LinkedCopyWriteEntity? =
        withContext(Dispatchers.IO) {
            queries.getWrite(targetKey).executeAsOneOrNull()
                ?.takeIf { row -> row.written_at >= notBefore }
                ?.toEntity()
        }

    override suspend fun getWriteForBook(
        bookUuid: String,
        notBefore: String,
    ): LinkedCopyWriteEntity? = withContext(Dispatchers.IO) {
        queries.getWriteForBook(bookUuid).executeAsOneOrNull()
            ?.takeIf { row -> row.written_at >= notBefore }
            ?.toEntity()
    }

    override suspend fun setMarker(targetKey: String, marker: String) {
        withContext(Dispatchers.IO) {
            queries.setMarker(marker, targetKey)
        }
    }
}

internal fun Linked_copy_writes.toEntity() = LinkedCopyWriteEntity(
    targetKey = target_key,
    targetBookUuid = target_book_uuid,
    sourceKey = source_key,
    sourceObservedAt = source_observed_at,
    writtenAt = written_at,
    marker = marker,
    locatorHref = locator_href,
    progression = progression,
    totalProgression = total_progression,
    audioMs = audio_ms,
)
