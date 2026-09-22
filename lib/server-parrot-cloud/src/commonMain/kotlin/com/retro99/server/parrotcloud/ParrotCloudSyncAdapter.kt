package com.retro99.server.parrotcloud

import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.user.api.UserRegistry
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [SyncRepository::class])
class ParrotCloudSyncAdapter(
    @Provided private val clientProvider: SupabaseClientProvider,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val profileDatabaseSession: ProfileDatabaseSession,
    @Provided private val preferences: Preferences,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val progressTransport: ProgressSyncTransport,
) : SyncRepository {
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun sync(): SyncResult = mutex.withLock {
        if (!clientProvider.isConfigured) return SyncResult.NotConfigured
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val link = profileLinkRepository.getForLocalProfile(localProfileId)
            ?: return SyncResult.ProfileNotLinked
        if (!link.syncEnabled) return SyncResult.SyncDisabled

        try {
            clientProvider.withProfileSession(localProfileId) {
                if (clientProvider.currentSessionState().accountId != link.cloudUserId) {
                    return@withProfileSession SyncResult.NotAuthenticated
                }
                profileDatabaseSession.withProfile(localProfileId) {
                    synchronizeProfile(localProfileId, link.cloudUserId)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            SyncResult.Failed(exception.message ?: "Cloud synchronization failed")
        }
    }

    private suspend fun synchronizeProfile(
        localProfileId: String,
        cloudUserId: String,
    ): SyncResult {
        syncOutboxDatabase.bindUnassignedMutations(cloudUserId)
        syncOutboxDatabase.deleteByEntityType(SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS)
        repairDuplicatePositions()
        var cursor = preferences.getLong(PreferencesKey.SyncCursor(localProfileId, cloudUserId))
        var pulledCount = 0
        do {
            val pull = pullAndApply(localProfileId, cloudUserId, cursor)
            pulledCount += pull.first
            cursor = pull.second
            preferences.putLong(PreferencesKey.SyncCursor(localProfileId, cloudUserId), cursor)
        } while (pull.third)

        val entries = syncOutboxDatabase.getEligible(cloudUserId, Clock.System.now().toString())
            .filter { entry -> entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS }
            .take(SYNC_BATCH_SIZE)
        val pushedCount = pushMutations(entries, cloudUserId, cursor)
        if (entries.isNotEmpty()) {
            var hasMore: Boolean
            do {
                val pull = pullAndApply(localProfileId, cloudUserId, cursor)
                pulledCount += pull.first
                cursor = pull.second
                hasMore = pull.third
                preferences.putLong(
                    PreferencesKey.SyncCursor(localProfileId, cloudUserId),
                    cursor,
                )
            } while (hasMore)
        }
        preferences.putLong(PreferencesKey.SyncCursor(localProfileId, cloudUserId), cursor)
        return SyncResult.Completed(
            pushedMutationCount = pushedCount,
            pulledChangeCount = pulledCount,
            pendingMutationCount = syncOutboxDatabase.getPending(cloudUserId).size,
        )
    }

    private suspend fun pushMutations(
        entries: List<SyncOutboxEntry>,
        cloudUserId: String,
        cursor: Long,
    ): Int {
        val progressEntries = entries.filter { entry ->
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
        }
        val legacyEntries = entries.filter { entry ->
            entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
        }
        return pushProgressMutations(progressEntries) +
            pushLegacyMutations(legacyEntries, cloudUserId, cursor)
    }

    private suspend fun pushProgressMutations(
        entries: List<SyncOutboxEntry>,
    ): Int {
        if (entries.isEmpty()) return 0

        entries.forEach { entry ->
            syncOutboxDatabase.markDispatched(entry.mutationId)
        }
        val mutations = entries.map { entry ->
            val payload = json.decodeFromString<ParrotCloudReadingPositionPayload>(entry.payload)
            ProgressMutation(
                mutationId = entry.mutationId,
                entityId = entry.entityId,
                remoteBookId = payload.cloudBookId,
                libraryBookId = payload.libraryBookId,
                kind = ProgressKind.EBOOK,
                snapshot = payload.position.toProgressSyncSnapshot(),
                baseVersion = entry.baseRevision?.toString(),
                observedAt = entry.createdAt,
            )
        }
        val results = progressTransport.pushProgress(mutations)
        val resultsByMutationId = results.associateBy { result -> result.mutationId }
        var handled = 0
        entries.forEach { entry ->
            when (val result = resultsByMutationId[entry.mutationId]) {
                is ProgressPushResult.Accepted -> {
                    applyAcceptedProgressMetadata(entry, result.version?.toLongOrNull())
                    syncOutboxDatabase.delete(entry.mutationId)
                    handled++
                }

                is ProgressPushResult.Conflict -> {
                    applyRemoteConflict(result.remote, entry)
                    syncOutboxDatabase.markConflict(
                        mutationId = entry.mutationId,
                        error = "Remote progress conflict",
                    )
                    handled++
                }

                is ProgressPushResult.Rejected -> {
                    val attempt = entry.attemptCount.coerceAtLeast(0)
                    val delaySeconds = result.retryAfterMillis
                        ?.div(1000L)
                        ?.coerceAtLeast(1L)
                        ?: (1L shl min(attempt, MAX_BACKOFF_POWER))
                    syncOutboxDatabase.recordFailure(
                        mutationId = entry.mutationId,
                        nextAttemptAt = Clock.System.now().plus(delaySeconds.seconds).toString(),
                        error = result.reason,
                    )
                }

                null -> Unit
            }
        }
        return handled
    }

    private suspend fun pushLegacyMutations(
        entries: List<SyncOutboxEntry>,
        cloudUserId: String,
        cursor: Long,
    ): Int {
        if (entries.isEmpty()) return 0
        val requests = entries.map { entry ->
            CloudMutationRequest(
                mutationId = entry.mutationId,
                entityType = entry.entityType,
                entityId = entry.entityId,
                operation = entry.operation,
                payload = json.decodeFromString<JsonElement>(entry.payload),
                baseRevision = entry.baseRevision,
                createdAt = entry.createdAt,
            )
        }
        entries.forEach { entry ->
            syncOutboxDatabase.markDispatched(entry.mutationId)
        }
        val response = clientProvider.client.postgrest
            .rpc(
                "push_sync_changes",
                buildJsonObject {
                    put("mutations", json.encodeToJsonElement(requests))
                    put("client_cursor", cursor)
                },
            )
            .decodeAs<List<CloudMutationResponse>>()
        val responsesByMutationId = response.associateBy { mutation -> mutation.mutationId }
        var handled = 0
        entries.forEach { entry ->
            val mutationResponse = responsesByMutationId[entry.mutationId]
                ?: error("Sync response omitted mutation ${entry.mutationId}")
            when (mutationResponse.status) {
                STATUS_ACCEPTED -> {
                    applyAcceptedMetadata(entry, mutationResponse)
                    syncOutboxDatabase.delete(entry.mutationId)
                    handled++
                }

                STATUS_CONFLICT -> {
                    mutationResponse.payload?.let { payload ->
                        applyRemoteConflict(entry, payload, mutationResponse.revision)
                    }
                    syncOutboxDatabase.markConflict(
                        mutationId = entry.mutationId,
                        error = mutationResponse.reason ?: "Remote progress conflict",
                    )
                    handled++
                }

                else -> {
                    val attempt = entry.attemptCount.coerceAtLeast(0)
                    val delaySeconds = 1L shl min(attempt, MAX_BACKOFF_POWER)
                    syncOutboxDatabase.recordFailure(
                        mutationId = entry.mutationId,
                        nextAttemptAt = Clock.System.now().plus(delaySeconds.seconds).toString(),
                        error = mutationResponse.reason ?: "Cloud mutation rejected",
                    )
                }
            }
        }
        return handled
    }

    private suspend fun pullAndApply(
        localProfileId: String,
        cloudUserId: String,
        cursor: Long,
    ): Triple<Int, Long, Boolean> {
        val response = clientProvider.client.postgrest
            .rpc(
                "pull_sync_changes",
                buildJsonObject {
                    put("cursor", cursor)
                    put("limit", SYNC_BATCH_SIZE)
                },
            )
            .decodeAs<CloudPullResponse>()
        for (change in response.changes) {
            applyRemoteChange(
                entityType = change.entityType,
                payload = change.payload,
                revision = change.revision,
                pendingMutations = syncOutboxDatabase.getPending(cloudUserId),
            )
        }
        return Triple(response.changes.size, response.nextCursor, response.hasMore)
    }

    private suspend fun applyRemoteChange(
        entityType: String,
        payload: JsonElement,
        revision: Long?,
        pendingMutations: List<SyncOutboxEntry> = emptyList(),
    ) {
        when (entityType) {
            SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK -> {
                val book = json.decodeFromJsonElement<ParrotCloudBookPayload>(payload)
                val existing = libraryBooksDatabase.getLibraryBookByCloudBookId(book.cloudBookId ?: "")
                    ?: libraryBooksDatabase.getLibraryBookByContentHash(
                        book.contentHashAlgorithm,
                        book.contentHash,
                    )
                val libraryBookId = existing?.libraryBookId ?: book.libraryBookId
                libraryBooksDatabase.upsertLibraryBook(
                    ParrotCloudLibraryBookEntity(
                        libraryBookId = libraryBookId,
                        cloudBookId = book.cloudBookId,
                        contentHash = book.contentHash,
                        contentHashAlgorithm = book.contentHashAlgorithm,
                        title = book.title,
                        author = book.author,
                        format = book.format,
                        remoteRevision = revision ?: book.remoteRevision,
                        metadataJson = book.metadataJson,
                    ),
                )
            }

            SyncOutboxEntry.ENTITY_TYPE_READING_POSITION -> {
                val position = json.decodeFromJsonElement<ParrotCloudReadingPositionPayload>(payload)
                val existingBook = libraryBooksDatabase.getLibraryBookByCloudBookId(position.cloudBookId)
                val libraryBookId = existingBook?.libraryBookId ?: position.libraryBookId
                val localBookUuid = resolveLocalBookUuid(
                    libraryBookId = libraryBookId,
                    cloudBookId = position.cloudBookId,
                    fallback = position.position.bookUuid,
                )
                val localPosition = position.position.toParrotCloudPositionEntity(
                    remoteRevision = revision,
                    bookUuid = localBookUuid,
                    libraryBookId = libraryBookId,
                )
                if (pendingMutations.hasPendingProgressFor(
                        localBookUuid = localBookUuid,
                        libraryBookId = libraryBookId,
                        cloudBookId = position.cloudBookId,
                    )
                ) {
                    positionDatabase.upsertRemotePosition(localPosition)
                } else {
                    positionDatabase.upsertPosition(localPosition)
                    positionDatabase.deleteRemotePosition(localBookUuid)
                }
            }
        }
    }

    private suspend fun applyRemoteChange(
        entry: SyncOutboxEntry,
        payload: JsonElement,
        revision: Long?,
    ) {
        applyRemoteChange(entry.entityType, payload, revision)
    }

    private suspend fun applyRemoteConflict(
        entry: SyncOutboxEntry,
        payload: JsonElement,
        revision: Long?,
    ) {
        if (entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION) {
            applyRemoteChange(entry, payload, revision)
            return
        }

        val position = json.decodeFromJsonElement<ParrotCloudReadingPositionPayload>(payload)
        val existingBook = libraryBooksDatabase.getLibraryBookByCloudBookId(position.cloudBookId)
        val libraryBookId = existingBook?.libraryBookId ?: position.libraryBookId
        val localBookUuid = resolveLocalBookUuid(
            libraryBookId = libraryBookId,
            cloudBookId = position.cloudBookId,
            fallback = position.position.bookUuid,
        )
        positionDatabase.upsertRemotePosition(
            position.position.toParrotCloudPositionEntity(
                remoteRevision = revision,
                bookUuid = localBookUuid,
                libraryBookId = libraryBookId,
            ),
        )
    }

    private suspend fun applyRemoteConflict(
        remote: com.retro99.sync.domain.RemoteProgressSnapshot,
        entry: SyncOutboxEntry,
    ) {
        val libraryBookId = remote.libraryBookId ?: entry.entityId
        val localBookUuid = resolveLocalBookUuid(
            libraryBookId = libraryBookId,
            cloudBookId = remote.remoteBookId,
            fallback = remote.entityId ?: entry.entityId,
        )
        positionDatabase.upsertRemotePosition(
            remote.snapshot.toServerPosition(
                bookUuid = localBookUuid,
                libraryBookId = libraryBookId,
            ).toParrotCloudPositionEntity(
                remoteRevision = remote.version?.toLongOrNull(),
                bookUuid = localBookUuid,
                libraryBookId = libraryBookId,
            ),
        )
    }

    private suspend fun applyAcceptedProgressMetadata(
        entry: SyncOutboxEntry,
        revision: Long?,
    ) {
        if (revision == null) return
        val payload = json.decodeFromString<ParrotCloudReadingPositionPayload>(entry.payload)
        val localBookUuid = resolveLocalBookUuid(
            libraryBookId = payload.libraryBookId,
            cloudBookId = payload.cloudBookId,
            fallback = entry.entityId,
        )
        positionDatabase.updateRemoteRevision(
            bookUuid = localBookUuid,
            remoteRevision = revision,
            expectedLocalGeneration = entry.localGeneration,
        )
    }

    private suspend fun applyAcceptedMetadata(
        entry: SyncOutboxEntry,
        response: CloudMutationResponse,
    ) {
        when (entry.entityType) {
            SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK -> {
                val cloudBookId = response.cloudBookId ?: return
                val existing = libraryBooksDatabase.getLibraryBookById(entry.entityId)
                if (existing == null) {
                    val payload = json.decodeFromString<ParrotCloudBookPayload>(entry.payload)
                    libraryBooksDatabase.upsertLibraryBook(
                        ParrotCloudLibraryBookEntity(
                            libraryBookId = payload.libraryBookId,
                            cloudBookId = cloudBookId,
                            contentHash = payload.contentHash,
                            contentHashAlgorithm = payload.contentHashAlgorithm,
                            title = payload.title,
                            author = payload.author,
                            format = payload.format,
                            remoteRevision = response.revision ?: payload.remoteRevision,
                            metadataJson = payload.metadataJson,
                        ),
                    )
                } else {
                    libraryBooksDatabase.upsertLibraryBook(
                        ParrotCloudLibraryBookEntity(
                            libraryBookId = existing.libraryBookId,
                            contentHash = existing.contentHash,
                            contentHashAlgorithm = existing.contentHashAlgorithm,
                            title = existing.title,
                            author = existing.author,
                            format = existing.format,
                            remoteRevision = response.revision ?: existing.remoteRevision,
                            deletedAt = existing.deletedAt,
                            cloudBookId = cloudBookId,
                            metadataJson = existing.metadataJson,
                        ),
                    )
                }
            }

            SyncOutboxEntry.ENTITY_TYPE_READING_POSITION -> {
                response.revision?.let { revision ->
                    val payload = json.decodeFromString<ParrotCloudReadingPositionPayload>(entry.payload)
                    val localBookUuid = resolveLocalBookUuid(
                        libraryBookId = payload.libraryBookId,
                        cloudBookId = payload.cloudBookId,
                        fallback = entry.entityId,
                    )
                    positionDatabase.updateRemoteRevision(
                        bookUuid = localBookUuid,
                        remoteRevision = revision,
                        expectedLocalGeneration = entry.localGeneration,
                    )
                }
            }
        }
    }

    private suspend fun resolveLocalBookUuid(
        libraryBookId: String,
        cloudBookId: String,
        fallback: String,
    ): String {
        val libraryBook = libraryBooksDatabase.getLibraryBookById(libraryBookId)
            ?: libraryBooksDatabase.getLibraryBookByCloudBookId(cloudBookId)
        val contentHash = libraryBook?.contentHash
        return importedBooksDatabase.getAllImportedBooks()
            .first()
            .firstOrNull { book ->
                book.contentHash == contentHash &&
                    book.contentHash != null
            }
            ?.uuid
            ?: fallback
    }

    private suspend fun repairDuplicatePositions() {
        val positionsByLibraryBookId = positionDatabase.getAllPositions()
            .filter { position -> position.libraryBookId.isNotBlank() }
            .groupBy { position -> position.libraryBookId }
        positionsByLibraryBookId.forEach { (libraryBookId, positions) ->
            if (positions.size > 1) {
                val winner = positions.maxWithOrNull(
                    compareBy<PositionEntity>({ position -> position.remoteRevision }, { position -> position.updatedAt }),
                ) ?: return@forEach
                val localBookUuid = resolveLocalBookUuid(
                    libraryBookId = libraryBookId,
                    cloudBookId = libraryBooksDatabase.getLibraryBookById(libraryBookId)?.cloudBookId.orEmpty(),
                    fallback = winner.bookUuid,
                )
                val repaired = winner.toParrotCloudPositionEntity(
                    remoteRevision = winner.remoteRevision,
                    bookUuid = localBookUuid,
                    libraryBookId = libraryBookId,
                )
                positions.filter { position -> position.bookUuid != localBookUuid }
                    .forEach { position -> positionDatabase.deletePosition(position.bookUuid) }
                positionDatabase.upsertPosition(repaired)
            }
        }
    }

    private companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_CONFLICT = "conflict"
        const val SYNC_BATCH_SIZE = 50
        const val MAX_BACKOFF_POWER = 6
    }
}

private fun List<SyncOutboxEntry>.hasPendingProgressFor(
    localBookUuid: String,
    libraryBookId: String,
    cloudBookId: String,
): Boolean {
    return any { mutation ->
        mutation.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION &&
            mutation.entityId in setOf(localBookUuid, libraryBookId, cloudBookId)
    }
}

@Serializable
private data class CloudMutationRequest(
    @SerialName("mutation_id")
    val mutationId: String,
    @SerialName("entity_type")
    val entityType: String,
    @SerialName("entity_id")
    val entityId: String,
    val operation: String,
    val payload: JsonElement,
    @SerialName("base_revision")
    val baseRevision: Long?,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
private data class CloudMutationResponse(
    @SerialName("mutation_id")
    val mutationId: String,
    val status: String,
    @SerialName("cloud_book_id")
    val cloudBookId: String? = null,
    val revision: Long? = null,
    val payload: JsonElement? = null,
    val reason: String? = null,
)

@Serializable
private data class CloudPullResponse(
    val changes: List<CloudRemoteChange>,
    @SerialName("next_cursor")
    val nextCursor: Long,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

@Serializable
private data class CloudRemoteChange(
    @SerialName("entity_type")
    val entityType: String,
    val payload: JsonElement,
    val revision: Long,
)
