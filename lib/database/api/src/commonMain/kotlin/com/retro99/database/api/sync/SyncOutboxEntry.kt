package com.retro99.database.api.sync

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class SyncOutboxEntry(
    val mutationId: String,
    val cloudUserId: String?,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val baseRevision: Long?,
    val createdAt: String,
    val attemptCount: Int,
    val nextAttemptAt: String?,
    val lastError: String?,
) {
    companion object {
        const val ENTITY_TYPE_BOOKMARK = "bookmark"
        const val ENTITY_TYPE_READER_SETTINGS = "reader_settings"
        const val ENTITY_TYPE_READING_POSITION = "reading_position"

        const val OPERATION_UPSERT = "upsert"
        const val OPERATION_DELETE = "delete"

        @OptIn(ExperimentalUuidApi::class)
        fun new(
            entityType: String,
            entityId: String,
            operation: String,
            payload: String,
            baseRevision: Long? = null,
            cloudUserId: String? = null,
        ): SyncOutboxEntry {
            return SyncOutboxEntry(
                mutationId = Uuid.random().toString(),
                cloudUserId = cloudUserId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payload = payload,
                baseRevision = baseRevision,
                createdAt = Clock.System.now().toString(),
                attemptCount = 0,
                nextAttemptAt = null,
                lastError = null,
            )
        }
    }
}
