package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.LegacySyncTransport
import com.retro99.sync.domain.SyncMutationResponse
import com.retro99.sync.data.ProgressIdentity
import com.retro99.sync.data.ProgressIdentityResolver
import com.retro99.sync.data.ProgressOutboxCodec
import com.retro99.sync.data.ProgressSyncEngine
import com.retro99.sync.data.LegacyMutationApplier
import com.retro99.sync.data.LegacySyncEngine
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.data.SyncLibraryBookSnapshot
import com.retro99.sync.data.SyncPullEngine
import com.retro99.sync.data.SyncPullPage
import com.retro99.sync.data.SyncPass
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [SyncPass::class])
class ParrotCloudSyncAdapter(
    @Provided private val clientProvider: SupabaseClientProvider,
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val profileDatabaseSession: ProfileDatabaseSession,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val progressTransport: ProgressSyncTransport,
    @Provided private val legacyTransport: LegacySyncTransport,
    @Provided private val progressSyncEngine: ProgressSyncEngine,
    @Provided private val legacySyncEngine: LegacySyncEngine,
    @Provided private val syncPullEngine: SyncPullEngine,
    @Provided private val libraryBookSyncApplier: LibraryBookSyncApplier,
) : SyncPass {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun execute(request: SyncRequest): SyncResult {
        if (!clientProvider.isConfigured) return SyncResult.NotConfigured
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val link = profileLinkRepository.getForLocalProfile(localProfileId)
            ?: return SyncResult.ProfileNotLinked
        if (!link.syncEnabled) return SyncResult.SyncDisabled

        return try {
            clientProvider.withProfileSession(localProfileId) {
                if (clientProvider.currentSessionState().accountId != link.cloudUserId) {
                    return@withProfileSession SyncResult.NotAuthenticated
                }
                profileDatabaseSession.withProfile(localProfileId) {
                    synchronizeProfile(link.cloudUserId)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            SyncResult.Failed(exception.message ?: "Cloud synchronization failed")
        }
    }

    private suspend fun synchronizeProfile(
        cloudUserId: String,
    ): SyncResult {
        syncOutboxDatabase.bindUnassignedMutations(cloudUserId)
        syncOutboxDatabase.deleteByEntityType(SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS)
        repairDuplicatePositions()
        val initialPull = pullUntilCaughtUp(cloudUserId)
        var cursor = initialPull.cursor?.toLongOrNull() ?: 0L
        var pulledCount = initialPull.pulledChangeCount

        val entries = syncOutboxDatabase.getEligible(cloudUserId, Clock.System.now().toString())
            .filter { entry -> entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS }
            .take(SYNC_BATCH_SIZE)
        val pushedCount = pushMutations(entries, cloudUserId, cursor)
        if (entries.isNotEmpty()) {
            val afterPushPull = pullUntilCaughtUp(cloudUserId)
            pulledCount += afterPushPull.pulledChangeCount
            cursor = afterPushPull.cursor?.toLongOrNull() ?: cursor
        }
        return SyncResult.Completed(
            pushedMutationCount = pushedCount,
            pulledChangeCount = pulledCount,
            pendingMutationCount = syncOutboxDatabase.getPending(cloudUserId).size,
        )
    }

    private suspend fun pullUntilCaughtUp(cloudUserId: String) =
        syncPullEngine.pullUntilCaughtUp(
            destinationId = PARROT_CLOUD_SERVER_ID,
            remoteAccountId = cloudUserId,
            limit = SYNC_BATCH_SIZE,
        ) { cursor, limit ->
            val page = pullAndApply(
                cloudUserId = cloudUserId,
                cursor = cursor?.toLongOrNull() ?: 0L,
                limit = limit,
            )
            SyncPullPage(
                changeCount = page.first,
                nextCursor = page.second.toString(),
                hasMore = page.third,
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
            pushLegacyMutations(legacyEntries, cursor)
    }

    private suspend fun pushProgressMutations(
        entries: List<SyncOutboxEntry>,
    ): Int {
        if (entries.isEmpty()) return 0
        val summary = progressSyncEngine.push(
            entries = entries,
            transport = progressTransport,
            codec = ProgressOutboxCodec { entry ->
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
            },
            identityResolver = parrotProgressIdentityResolver(),
        )
        return summary.acknowledgedCount + summary.conflictCount
    }

    private fun parrotProgressIdentityResolver() = ProgressIdentityResolver { remote ->
        val libraryBookId = remote.libraryBookId ?: remote.entityId ?: remote.remoteBookId
        val localBookUuid = resolveLocalBookUuid(
            libraryBookId = libraryBookId,
            cloudBookId = remote.remoteBookId,
            fallback = remote.entityId ?: libraryBookId,
        )
        ProgressIdentity(
            localBookUuid = localBookUuid,
            libraryBookId = libraryBookId,
        )
    }

    private suspend fun pushLegacyMutations(
        entries: List<SyncOutboxEntry>,
        cursor: Long,
    ): Int {
        if (entries.isEmpty()) return 0
        val summary = legacySyncEngine.push(
            entries = entries,
            transport = legacyTransport,
            cursor = cursor.toString(),
            applier = object : LegacyMutationApplier {
                override suspend fun onAccepted(
                    entry: SyncOutboxEntry,
                    response: com.retro99.sync.domain.SyncMutationResponse,
                ) {
                    applyAcceptedMetadata(entry, response)
                }

                override suspend fun onConflict(
                    entry: SyncOutboxEntry,
                    response: com.retro99.sync.domain.SyncMutationResponse,
                ) {
                    response.payload?.let { payload ->
                        applyRemoteConflict(
                            entry = entry,
                            payload = json.decodeFromString<JsonElement>(payload),
                            revision = response.revision,
                        )
                    }
                }
            },
        )
        return summary.acknowledgedCount + summary.conflictCount
    }

    private suspend fun pullAndApply(
        cloudUserId: String,
        cursor: Long,
        limit: Int,
    ): Triple<Int, Long, Boolean> {
        val legacyResponse = legacyTransport.pull(
            cursor = cursor.toString(),
            limit = limit,
        )
        legacyResponse.changes
            .filter { change -> change.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION }
            .forEach { change ->
                applyRemoteChange(
                    entityType = change.entityType,
                    payload = json.decodeFromString<JsonElement>(change.payload),
                    revision = change.revision,
                )
            }

        val progressPage = progressTransport.fetchChanges(
            cursor = cursor.toString(),
            limit = limit,
        )
        progressPage.changes.forEach { remote ->
            applyRemoteProgress(remote, cloudUserId)
        }

        val nextCursor = maxOf(
            legacyResponse.nextCursor?.toLongOrNull() ?: cursor,
            progressPage.nextCursor?.toLongOrNull() ?: cursor,
        )
        return Triple(
            legacyResponse.changes.count { change ->
                change.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
            } + progressPage.changes.size,
            nextCursor,
            legacyResponse.hasMore || progressPage.hasMore,
        )
    }

    private suspend fun applyRemoteProgress(
        remote: com.retro99.sync.domain.RemoteProgressSnapshot,
        cloudUserId: String,
    ) {
        progressSyncEngine.applyRemote(
            remote = remote,
            accountId = cloudUserId,
            identityResolver = parrotProgressIdentityResolver(),
        )
    }

    private suspend fun applyRemoteChange(
        entityType: String,
        payload: JsonElement,
        revision: Long?,
    ) {
        when (entityType) {
            SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK -> {
                val book = json.decodeFromJsonElement<ParrotCloudBookPayload>(payload)
                libraryBookSyncApplier.applyRemote(
                    book.toSyncLibraryBookSnapshot(revision),
                )
            }
        }
    }

    private suspend fun applyRemoteConflict(
        entry: SyncOutboxEntry,
        payload: JsonElement,
        revision: Long?,
    ) {
        if (entry.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK) {
            val book = json.decodeFromJsonElement<ParrotCloudBookPayload>(payload)
            libraryBookSyncApplier.applyRemote(
                book.toSyncLibraryBookSnapshot(revision),
            )
        }
    }

    private suspend fun applyAcceptedMetadata(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        when (entry.entityType) {
            SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK -> {
                val payload = json.decodeFromString<ParrotCloudBookPayload>(entry.payload)
                libraryBookSyncApplier.applyAccepted(
                    entry = entry,
                    response = response,
                    snapshot = payload.toSyncLibraryBookSnapshot(response.revision),
                )
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
        const val SYNC_BATCH_SIZE = 50
    }
}

private fun ParrotCloudBookPayload.toSyncLibraryBookSnapshot(
    revision: Long?,
): SyncLibraryBookSnapshot {
    return SyncLibraryBookSnapshot(
        libraryBookId = libraryBookId,
        cloudBookId = cloudBookId,
        contentHash = contentHash,
        contentHashAlgorithm = contentHashAlgorithm,
        title = title,
        author = author,
        format = format,
        remoteRevision = revision ?: remoteRevision,
        metadataJson = metadataJson,
    )
}
