package com.retro99.server.storyteller

import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.sync.data.ProgressIdentityResolver
import com.retro99.sync.data.ProgressOutboxCodec
import com.retro99.sync.data.ProgressSyncEngine
import com.retro99.sync.data.SyncBoundedPass
import com.retro99.sync.data.SyncDestination
import com.retro99.sync.data.SyncOutboxCapability
import com.retro99.sync.data.SyncOutboxPreflight
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncPhase
import com.retro99.sync.domain.SyncPhaseReporter
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Runs Storyteller progress through the shared outbox and progress engine.
 * The network client is supplied per configured Storyteller server; this class
 * therefore does not bind a second application-wide SyncPass.
 */
@Single(binds = [SyncDestination::class])
class StorytellerProgressSyncAdapter(
    @Provided private val syncOutboxPreflight: SyncOutboxPreflight,
    @Provided private val progressSyncEngine: ProgressSyncEngine,
    @Provided private val syncBoundedPass: SyncBoundedPass,
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val networkClientProvider: ServerNetworkClientProvider,
    @Provided private val profileDatabaseSession: ProfileDatabaseSession,
    @Provided private val userRegistry: UserRegistry,
) : SyncDestination {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun execute(request: SyncRequest): SyncResult? = execute(request) { _, _, _ -> }

    override suspend fun execute(
        request: SyncRequest,
        reportPhase: SyncPhaseReporter,
    ): SyncResult? {
        val profileId = userRegistry.getActiveProfileIdOrDefault()
        return profileDatabaseSession.withProfile(profileId) {
            val servers = serverRegistry.getAuthenticatedServers()
                .filter { server -> server.type == ServerType.Storyteller }
            if (servers.isEmpty()) {
                null
            } else {
                val results = servers.map { server ->
                    try {
                        execute(request, networkClientProvider.create(server), reportPhase)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        SyncResult.Failed(
                            exception.message
                                ?: "Storyteller synchronization failed for ${server.id}",
                        )
                    }
                }
                results.fold(SyncResult.Completed(0, 0, 0), ::combineResults)
            }
        }
    }

    suspend fun execute(
        networkClient: ServerNetworkClient,
    ): SyncResult.Completed {
        return execute(SyncRequest(), networkClient)
    }

    suspend fun execute(
        request: SyncRequest,
        networkClient: ServerNetworkClient,
        reportPhase: SyncPhaseReporter = { _, _, _ -> },
    ): SyncResult.Completed {
        val transport = StorytellerProgressTransport(networkClient)
        val capability = SyncOutboxCapability(
            unsupportedEntityTypes = setOf(
                // Links sync through Parrot Cloud only; nothing is written to this server.
                SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
                SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
                SyncOutboxEntry.ENTITY_TYPE_BOOKMARK,
                SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
                SyncOutboxEntry.ENTITY_TYPE_READING_SESSION,
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

        if (request.reason == SyncTriggerReason.BOOK_OPEN) {
            reportPhase(SyncPhase.PULLING, 0, null)
            val bookIds = (request.scope as? SyncScope.Books)?.bookIds.orEmpty()
            progressSyncEngine.refreshRemoteBookIds(
                remoteBookIds = bookIds,
                accountId = networkClient.serverId,
                transport = transport,
                identityResolver = ProgressIdentityResolver.Default,
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
            pushLibraryMutationEntries = { _, _ -> 0 },
            fetchAndApply = { cursor, _, _ ->
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
            reportPhase = reportPhase,
        )
    }

    private companion object {
        const val BATCH_SIZE = 1

        fun combineResults(
            first: SyncResult,
            second: SyncResult,
        ): SyncResult {
            if (first is SyncResult.Failed) return first
            if (second is SyncResult.Failed) return second
            if (first is SyncResult.Offline || second is SyncResult.Offline) {
                val pendingCount = listOf(first, second)
                    .sumOf { result ->
                        when (result) {
                            is SyncResult.Offline -> result.pendingMutationCount
                            is SyncResult.Completed -> result.pendingMutationCount
                            else -> 0
                        }
                    }
                return SyncResult.Offline(pendingCount)
            }
            if (first is SyncResult.Completed && second is SyncResult.Completed) {
                return SyncResult.Completed(
                    pushedMutationCount = first.pushedMutationCount + second.pushedMutationCount,
                    pulledChangeCount = first.pulledChangeCount + second.pulledChangeCount,
                    pendingMutationCount = first.pendingMutationCount + second.pendingMutationCount,
                )
            }
            return if (first is SyncResult.Completed) first else second
        }
    }
}

@Serializable
private data class StorytellerPositionOutboxPayload(
    val position: com.retro99.server.api.ServerPosition,
)
