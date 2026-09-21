package com.retro99.sync.data

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncPendingChange
import com.retro99.database.api.sync.SyncPendingChangesDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.server.api.ServerPosition
import com.retro99.sync.data.model.SyncMutationRequest
import com.retro99.sync.data.model.SyncMutationResponse
import com.retro99.sync.data.model.SyncPullResponse
import com.retro99.sync.data.model.SyncReadingPositionPayload
import com.retro99.sync.data.model.SyncRemoteChange
import com.retro99.sync.data.model.syncJson
import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncResult
import com.retro99.user.api.UserRegistry
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [SyncRepository::class])
internal class SupabaseSyncDataRepository(
    @Provided private val clientProvider: SupabaseClientProvider,
    @Provided private val cloudProfileLinkRepository: CloudProfileLinkRepository,
    @Provided private val preferences: Preferences,
    @Provided private val profileDatabaseSession: ProfileDatabaseSession,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val syncPendingChangesDatabase: SyncPendingChangesDatabase,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val userRegistry: UserRegistry,
) : SyncRepository {
    private val mutex = Mutex()

    override suspend fun sync(): SyncResult = mutex.withLock {
        syncLocked()
    }

    private suspend fun syncLocked(): SyncResult {
        if (!clientProvider.isConfigured) return SyncResult.NotConfigured

        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val link = cloudProfileLinkRepository.getForLocalProfile(localProfileId)
            ?: return SyncResult.ProfileNotLinked
        if (!link.syncEnabled) return SyncResult.SyncDisabled

        return clientProvider.withProfileSession(localProfileId) {
            if (clientProvider.currentSessionState().accountId != link.cloudUserId) {
                return@withProfileSession SyncResult.NotAuthenticated
            }
            profileDatabaseSession.withProfile(localProfileId) {
                syncWithinProfile(
                    localProfileId = localProfileId,
                    cloudUserId = link.cloudUserId,
                    initialMergeCompleted = link.initialMergeCompleted,
                )
            }
        }
    }

    private suspend fun syncWithinProfile(
        localProfileId: String,
        cloudUserId: String,
        initialMergeCompleted: Boolean,
    ): SyncResult {
        return try {
            syncOutboxDatabase.bindUnassignedMutations(cloudUserId)
            val snapshotKey = PreferencesKey.SyncInitialSnapshot(localProfileId, cloudUserId)
            if (!initialMergeCompleted && !preferences.getBoolean(snapshotKey)) {
                snapshotExistingPositions(cloudUserId)
                preferences.putBoolean(snapshotKey, true)
            }

            var cursor = preferences.getLong(
                PreferencesKey.SyncCursor(localProfileId, cloudUserId),
            )
            var pulledChangeCount = 0
            applyPendingChanges(cloudUserId)
            var hasMore = true

            while (hasMore) {
                val response = pullChanges(cursor)
                applyChanges(cloudUserId, response.changes)
                cursor = response.nextCursor
                preferences.putLong(
                    PreferencesKey.SyncCursor(localProfileId, cloudUserId),
                    cursor,
                )
                pulledChangeCount += response.changes.size
                hasMore = response.hasMore
            }

            val pushedMutationCount = pushPendingMutations(cloudUserId)
            hasMore = true

            while (hasMore) {
                val response = pullChanges(cursor)
                applyChanges(cloudUserId, response.changes)
                cursor = response.nextCursor
                preferences.putLong(
                    PreferencesKey.SyncCursor(localProfileId, cloudUserId),
                    cursor,
                )
                pulledChangeCount += response.changes.size
                hasMore = response.hasMore
            }

            val pendingMutationCount = syncOutboxDatabase.getPending(cloudUserId)
                .count { entry -> entry.isPositionMutationWithContentHash() }
            val pendingRemoteChangeCount = syncPendingChangesDatabase
                .getPending(cloudUserId)
                .size
            val result = SyncResult.Completed(
                pushedMutationCount = pushedMutationCount,
                pulledChangeCount = pulledChangeCount,
                pendingMutationCount = pendingMutationCount,
            )
            if (!initialMergeCompleted &&
                pendingMutationCount == 0 &&
                pendingRemoteChangeCount == 0
            ) {
                cloudProfileLinkRepository.markInitialMergeCompleted(localProfileId)
            }
            result
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            SyncResult.Failed(exception.message ?: "Cloud synchronization failed")
        }
    }

    private suspend fun pushPendingMutations(cloudUserId: String): Int {
        val entries = syncOutboxDatabase.getPending(cloudUserId)
            .filter { entry -> entry.isPositionMutationWithContentHash() }
            .take(SYNC_BATCH_SIZE)
        if (entries.isEmpty()) return 0

        val requests = entries.map { entry -> entry.toRequest() }
        val parameters = buildJsonObject {
            put(
                "p_mutations",
                syncJson.encodeToJsonElement(requests),
            )
        }
        val responses = clientProvider.client.postgrest
            .rpc("push_sync_changes", parameters)
            .decodeAs<List<SyncMutationResponse>>()

        val responseByMutationId = responses.associateBy { response -> response.mutationId }
        var handledMutationCount = 0
        entries.forEach { entry ->
            val response = responseByMutationId[entry.mutationId]
                ?: error("Sync response omitted mutation ${entry.mutationId}")
            when (response.status) {
                STATUS_ACCEPTED -> {
                    syncOutboxDatabase.delete(entry.mutationId)
                    handledMutationCount++
                }

                STATUS_CONFLICT -> {
                    val remoteChange = response.toRemoteChange(entry)
                    if (!applyReadingPosition(remoteChange)) {
                        syncPendingChangesDatabase.upsert(
                            remoteChange.toPendingChange(cloudUserId),
                        )
                    }
                    syncOutboxDatabase.delete(entry.mutationId)
                    handledMutationCount++
                }

                else -> error(response.reason ?: "Sync rejected mutation ${entry.mutationId}")
            }
        }
        return handledMutationCount
    }

    private suspend fun pullChanges(cursor: Long): SyncPullResponse {
        val parameters = buildJsonObject {
            put("p_cursor", cursor)
            put("p_limit", SYNC_BATCH_SIZE)
        }
        return clientProvider.client.postgrest
            .rpc("pull_sync_changes", parameters)
            .decodeAs()
    }

    private suspend fun applyPendingChanges(cloudUserId: String) {
        syncPendingChangesDatabase.getPending(cloudUserId).forEach { pendingChange ->
            val change = pendingChange.toRemoteChange()
            if (mergeRemoteChange(cloudUserId, change)) {
                syncPendingChangesDatabase.delete(cloudUserId, pendingChange.changeId)
            }
        }
    }

    private suspend fun applyChanges(
        cloudUserId: String,
        changes: List<SyncRemoteChange>,
    ) {
        changes.forEach { change ->
            if (change.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION ||
                change.operation != SyncOutboxEntry.OPERATION_UPSERT
            ) {
                return@forEach
            }
            if (!mergeRemoteChange(cloudUserId, change)) {
                syncPendingChangesDatabase.upsert(change.toPendingChange(cloudUserId))
            }
        }
    }

    private suspend fun mergeRemoteChange(
        cloudUserId: String,
        change: SyncRemoteChange,
    ): Boolean {
        val remotePayload = syncJson.decodeFromJsonElement<SyncReadingPositionPayload>(
            change.payload,
        )
        val localMutation = syncOutboxDatabase.getPending(cloudUserId)
            .firstOrNull { entry ->
                entry.isPositionMutationWithContentHash() &&
                    entry.contentHash() == remotePayload.contentHash
        }
        if (localMutation != null) {
            val localPayload = syncJson.decodeFromString<SyncReadingPositionPayload>(
                localMutation.payload,
            )
            val localTimestamp = localPayload.position.timestamp
            val remoteTimestamp = remotePayload.position.timestamp
            if (localTimestamp != null &&
                (remoteTimestamp == null || localTimestamp >= remoteTimestamp)
            ) {
                syncOutboxDatabase.updateBaseRevision(localMutation.mutationId, change.revision)
                return true
            }
            syncOutboxDatabase.delete(localMutation.mutationId)
        }
        return applyReadingPosition(change)
    }

    private suspend fun applyReadingPosition(change: SyncRemoteChange): Boolean {
        val payload = syncJson.decodeFromJsonElement<SyncReadingPositionPayload>(change.payload)
        val contentHash = payload.contentHash
            ?: error("Remote reading position has no content hash")
        val libraryBook = libraryBooksDatabase.getLibraryBookByContentHash(contentHash)
            ?: return false
        val localFiles = libraryBooksDatabase.getLocalBookFiles(libraryBook.libraryBookId)
        if (localFiles.isEmpty()) {
            return false
        }

        localFiles.forEach { localFile ->
            positionDatabase.upsertPosition(
                payload.position
                    .copy(
                        bookUuid = localFile.importedBookUuid,
                        serverId = LOCAL_SERVER_ID,
                    )
                    .toPositionEntity(change.revision),
            )
        }
        return true
    }

    private suspend fun snapshotExistingPositions(cloudUserId: String) {
        val pendingBookUuids = syncOutboxDatabase.getPending(cloudUserId)
            .filter { entry -> entry.isPositionMutationWithContentHash() }
            .map { entry -> entry.entityId }
            .toSet()
        positionDatabase.getAllPositions().forEach { position ->
            if (position.bookUuid in pendingBookUuids) return@forEach
            val importedBook = importedBooksDatabase.getImportedBookByUuid(position.bookUuid)
            val contentHash = importedBook?.contentHash ?: return@forEach
            syncOutboxDatabase.enqueue(
                SyncOutboxEntry.new(
                    cloudUserId = cloudUserId,
                    entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                    entityId = position.bookUuid,
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    payload = syncJson.encodeToString(
                        SyncReadingPositionPayload(
                            bookUuid = position.bookUuid,
                            contentHash = contentHash,
                            contentHashAlgorithm = importedBook.contentHashAlgorithm ?: "sha256",
                            position = position.toServerPosition(),
                        ),
                    ),
                    baseRevision = position.remoteRevision,
                ),
            )
        }
    }

    private fun SyncOutboxEntry.isPositionMutationWithContentHash(): Boolean {
        return entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION && contentHash() != null
    }

    private fun SyncOutboxEntry.contentHash(): String? {
        val payload = runCatching {
            syncJson.parseToJsonElement(payload).jsonObject
        }.getOrNull() ?: return null
        return payload.contentHashValue()
    }

    private fun SyncOutboxEntry.toRequest(): SyncMutationRequest {
        return SyncMutationRequest(
            mutationId = mutationId,
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payload = syncJson.parseToJsonElement(payload),
            baseRevision = baseRevision,
            createdAt = createdAt,
        )
    }

    private fun JsonObject.contentHashValue(): String? {
        return get("contentHash")?.jsonPrimitive?.contentOrNull
            ?: get("content_hash")?.jsonPrimitive?.contentOrNull
    }

    private fun PositionEntity.toServerPosition(): ServerPosition {
        return ServerPosition(
            bookUuid = bookUuid,
            serverId = LOCAL_SERVER_ID,
            timestamp = timestamp,
            createdAt = createdAt,
            updatedAt = updatedAt,
            locatorHref = locatorHref,
            locatorType = locatorType,
            locatorTitle = locatorTitle,
            locatorTarget = locatorTarget,
            audioTimestampMs = audioTimestampMs,
            chapterIndex = chapterIndex,
            progression = progression,
            totalChapters = totalChapters,
            totalDurationMs = totalDurationMs,
            totalProgression = totalProgression,
            position = position,
        )
    }

    private fun ServerPosition.toPositionEntity(revision: Long): PositionEntity {
        return RemotePositionEntity(
            bookUuid = bookUuid,
            remoteRevision = revision,
            timestamp = timestamp,
            createdAt = createdAt,
            updatedAt = updatedAt,
            locatorHref = locatorHref,
            locatorType = locatorType,
            locatorTitle = locatorTitle,
            locatorTarget = locatorTarget,
            audioTimestampMs = audioTimestampMs,
            chapterIndex = chapterIndex,
            progression = progression,
            totalChapters = totalChapters,
            totalDurationMs = totalDurationMs,
            totalProgression = totalProgression,
            position = position,
        )
    }

    private companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_CONFLICT = "conflict"
        const val SYNC_BATCH_SIZE = 50
    }
}

private fun SyncMutationResponse.toRemoteChange(entry: SyncOutboxEntry): SyncRemoteChange {
    return SyncRemoteChange(
        changeId = changeId ?: error("Conflict response omitted change ID"),
        entityType = entityType ?: entry.entityType,
        entityId = entityId ?: entry.entityId,
        operation = entry.operation,
        payload = payload ?: error("Conflict response omitted payload"),
        revision = revision ?: error("Conflict response omitted revision"),
        createdAt = entry.createdAt,
    )
}

private fun SyncRemoteChange.toPendingChange(cloudUserId: String): SyncPendingChange {
    return SyncPendingChange(
        cloudUserId = cloudUserId,
        changeId = changeId,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = syncJson.encodeToString(payload),
        revision = revision,
        createdAt = createdAt,
    )
}

private fun SyncPendingChange.toRemoteChange(): SyncRemoteChange {
    return SyncRemoteChange(
        changeId = changeId,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = syncJson.parseToJsonElement(payload),
        revision = revision,
        createdAt = createdAt,
    )
}

private data class RemotePositionEntity(
    override val bookUuid: String,
    override val remoteRevision: Long?,
    override val timestamp: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val locatorHref: String?,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val locatorTarget: Int?,
    override val audioTimestampMs: Long?,
    override val chapterIndex: Int?,
    override val progression: Double?,
    override val totalChapters: Int?,
    override val totalDurationMs: Long?,
    override val totalProgression: Double?,
    override val position: Int?,
) : PositionEntity
