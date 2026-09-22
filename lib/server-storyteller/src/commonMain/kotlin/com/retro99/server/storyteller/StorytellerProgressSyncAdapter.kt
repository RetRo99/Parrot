package com.retro99.server.storyteller

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerNetworkClient
import com.retro99.sync.data.ProgressIdentityResolver
import com.retro99.sync.data.ProgressOutboxCodec
import com.retro99.sync.data.ProgressSyncEngine
import com.retro99.sync.data.SyncBoundedPass
import com.retro99.sync.data.SyncOutboxCapability
import com.retro99.sync.data.SyncOutboxPreflight
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.SyncResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Runs Storyteller progress through the shared outbox and progress engine.
 * The network client is supplied per configured Storyteller server; this class
 * therefore does not bind a second application-wide SyncPass.
 */
@Single
class StorytellerProgressSyncAdapter(
    @Provided private val syncOutboxPreflight: SyncOutboxPreflight,
    @Provided private val progressSyncEngine: ProgressSyncEngine,
    @Provided private val syncBoundedPass: SyncBoundedPass,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun execute(
        networkClient: ServerNetworkClient,
    ): SyncResult.Completed {
        val transport = StorytellerProgressTransport(networkClient)
        val capability = SyncOutboxCapability(
            unsupportedEntityTypes = setOf(
                SyncOutboxEntry.ENTITY_TYPE_BOOKMARK,
                SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
            ),
        )
        val codec = ProgressOutboxCodec { entry ->
            val payload = json.decodeFromString<StorytellerPositionOutboxPayload>(entry.payload)
            val position = payload.position
            ProgressMutation(
                mutationId = entry.mutationId,
                entityId = entry.entityId,
                remoteBookId = position.bookUuid,
                libraryBookId = position.libraryBookId,
                kind = ProgressKind.EBOOK,
                snapshot = position.toRemoteProgressSnapshot(position.bookUuid).snapshot,
                baseVersion = entry.baseRevision?.toString(),
                observedAt = entry.createdAt,
            )
        }

        return syncBoundedPass.execute(
            destinationId = networkClient.serverId,
            remoteAccountId = networkClient.serverId,
            batchSize = BATCH_SIZE,
            selectEntries = {
                syncOutboxPreflight.selectEligible(
                    remoteAccountId = networkClient.serverId,
                    maxEntries = BATCH_SIZE,
                    capability = capability,
                )
            },
            refreshProgressEntries = { entries ->
                progressSyncEngine.refreshRemote(
                    entries = entries,
                    accountId = networkClient.serverId,
                    transport = transport,
                    codec = codec,
                    identityResolver = ProgressIdentityResolver.Default,
                )
            },
            pushProgressEntries = { entries ->
                val summary = progressSyncEngine.push(
                    entries = entries,
                    transport = transport,
                    codec = codec,
                    identityResolver = ProgressIdentityResolver.Default,
                )
                summary.acknowledgedCount + summary.conflictCount
            },
            pushLegacyEntries = { _, _ -> 0 },
            fetchAndApply = { cursor, _ ->
                com.retro99.sync.data.SyncPullPage(
                    changeCount = 0,
                    nextCursor = cursor,
                    hasMore = false,
                )
            },
            pendingMutationCount = {
                syncOutboxPreflight.pendingCount(
                    remoteAccountId = networkClient.serverId,
                    capability = capability,
                )
            },
            pullEnabled = false,
        )
    }

    private companion object {
        const val BATCH_SIZE = 1
    }
}

@Serializable
private data class StorytellerPositionOutboxPayload(
    val position: com.retro99.server.api.ServerPosition,
)
