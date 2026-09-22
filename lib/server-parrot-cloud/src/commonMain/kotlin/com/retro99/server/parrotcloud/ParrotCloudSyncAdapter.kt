package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
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
import com.retro99.sync.data.SyncPass
import com.retro99.sync.data.SyncExecutionContext
import com.retro99.sync.data.SyncOutboxPreflight
import com.retro99.sync.data.SyncOutboxCapability
import com.retro99.sync.data.DuplicatePositionRepair
import com.retro99.sync.data.LocalBookUuidResolver
import com.retro99.sync.data.SyncBoundedPass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [SyncPass::class])
class ParrotCloudSyncAdapter(
    @Provided private val syncOutboxPreflight: SyncOutboxPreflight,
    @Provided private val localBookUuidResolver: LocalBookUuidResolver,
    @Provided private val duplicatePositionRepair: DuplicatePositionRepair,
    @Provided private val legacyTransport: LegacySyncTransport,
    @Provided private val progressTransport: ProgressSyncTransport,
    @Provided private val progressSyncEngine: ProgressSyncEngine,
    @Provided private val legacySyncEngine: LegacySyncEngine,
    @Provided private val syncBoundedPass: SyncBoundedPass,
    @Provided private val syncPageAdapter: ParrotCloudSyncPageAdapter,
    @Provided private val libraryBookSyncApplier: LibraryBookSyncApplier,
) : SyncPass {
    private val outboxCapability = SyncOutboxCapability(
        unsupportedEntityTypes = setOf(SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS),
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun execute(
        request: SyncRequest,
        context: SyncExecutionContext,
    ): SyncResult {
        return synchronizeProfile(context.remoteAccountId)
    }

    private suspend fun synchronizeProfile(
        cloudUserId: String,
    ): SyncResult {
        duplicatePositionRepair.repair()
        return syncBoundedPass.execute(
            destinationId = PARROT_CLOUD_SERVER_ID,
            remoteAccountId = cloudUserId,
            batchSize = SYNC_BATCH_SIZE,
            selectEntries = {
                syncOutboxPreflight.selectEligible(
                    remoteAccountId = cloudUserId,
                    maxEntries = SYNC_BATCH_SIZE,
                    capability = outboxCapability,
                )
            },
            pushProgressEntries = ::pushProgressMutations,
            pushLegacyEntries = { entries, cursor ->
                pushLegacyMutations(entries, cursor ?: "0")
            },
            fetchAndApply = { cursor, limit ->
                syncPageAdapter.fetchPage(
                    cursor = cursor ?: "0",
                    limit = limit,
                    onLegacyChange = { change ->
                        applyRemoteChange(
                            entityType = change.entityType,
                            payload = json.decodeFromString<JsonElement>(change.payload),
                            revision = change.revision,
                        )
                    },
                    onProgressChange = { remote ->
                        applyRemoteProgress(remote, cloudUserId)
                    },
                )
            },
            pendingMutationCount = {
                syncOutboxPreflight.pendingCount(cloudUserId, outboxCapability)
            },
        )
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
        val localBookUuid = localBookUuidResolver.resolve(
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
        cursor: String,
    ): Int {
        if (entries.isEmpty()) return 0
        val summary = legacySyncEngine.push(
            entries = entries,
            transport = legacyTransport,
            cursor = cursor,
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
        val payload = json.decodeFromString<ParrotCloudBookPayload>(entry.payload)
        libraryBookSyncApplier.applyAccepted(
            entry = entry,
            response = response,
            snapshot = payload.toSyncLibraryBookSnapshot(response.revision),
        )
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
