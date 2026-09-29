package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.server.api.ServerPosition
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncPhaseReporter
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.LibraryMutationSyncTransport
import com.retro99.sync.data.ProgressIdentity
import com.retro99.sync.data.ProgressIdentityResolver
import com.retro99.sync.data.ProgressOutboxCodec
import com.retro99.sync.data.ProgressSyncEngine
import com.retro99.sync.data.LibraryMutationSyncEngine
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.data.SyncPass
import com.retro99.sync.data.SyncExecutionContext
import com.retro99.sync.data.SyncOutboxPreflight
import com.retro99.sync.data.SyncOutboxCapability
import com.retro99.sync.data.DuplicatePositionRepair
import com.retro99.sync.data.LocalBookUuidResolver
import com.retro99.sync.data.SyncBoundedPass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.put
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [SyncPass::class])
class ParrotCloudSyncAdapter(
    @Provided private val syncOutboxPreflight: SyncOutboxPreflight,
    @Provided private val localBookUuidResolver: LocalBookUuidResolver,
    @Provided private val duplicatePositionRepair: DuplicatePositionRepair,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val libraryMutationTransport: LibraryMutationSyncTransport,
    @Provided private val progressTransport: ProgressSyncTransport,
    @Provided private val progressSyncEngine: ProgressSyncEngine,
    @Provided private val libraryMutationSyncEngine: LibraryMutationSyncEngine,
    @Provided private val syncBoundedPass: SyncBoundedPass,
    @Provided private val syncPageAdapter: ParrotCloudSyncPageAdapter,
    @Provided private val libraryMutationApplier: ParrotCloudLibraryMutationApplier,
    @Provided private val libraryBookSyncApplier: LibraryBookSyncApplier,
    @Provided private val bookFileChangeApplier: ParrotCloudBookFileChangeApplier,
    @Provided private val readingSessionSyncService: ParrotCloudReadingSessionSyncService,
    @Provided private val readingSessionChangeApplier: ParrotCloudReadingSessionChangeApplier,
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
        reportPhase: SyncPhaseReporter,
    ): SyncResult {
        return synchronizeProfile(context.remoteAccountId, reportPhase)
    }

    private suspend fun synchronizeProfile(
        cloudUserId: String,
        reportPhase: SyncPhaseReporter,
    ): SyncResult {
        duplicatePositionRepair.repair()
        // Backfill-style sweep: enqueue every local reading session recorded
        // since the last sweep before the pass drains the outbox.
        readingSessionSyncService.enqueueNewSessions(cloudUserId)
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
            pushLibraryMutationEntries = { entries, cursor ->
                pushLibraryMutations(cloudUserId, entries, cursor ?: "0")
            },
            fetchAndApply = { cursor, limit, reportApplying ->
                syncPageAdapter.fetchPage(
                    cursor = cursor ?: "0",
                    limit = limit,
                    reportApplying = reportApplying,
                    onLibraryMutationChange = { change ->
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
            reportPhase = reportPhase,
        )
    }

    private suspend fun pushProgressMutations(
        entries: List<SyncOutboxEntry>,
    ): Int {
        if (entries.isEmpty()) return 0
        // Per-entry isolation: one unresolvable/unlinked book must never abort
        // the batch for every other book. Unresolvable entries simply stay in
        // the outbox until their book is linked.
        val resolvedEntries = mutableMapOf<String, SyncOutboxEntry>()
        val unresolvedMutationIds = mutableSetOf<String>()
        val latestEntryByCloudBookId = linkedMapOf<String, Pair<Long, String>>()
        entries.forEachIndexed { index, entry ->
            val cloudEntry = entry.toCloudReadingPositionEntry()
            if (cloudEntry == null) {
                unresolvedMutationIds += entry.mutationId
                return@forEachIndexed
            }
            resolvedEntries[entry.mutationId] = cloudEntry
            val payload = json.decodeFromString<ParrotCloudReadingPositionPayload>(cloudEntry.payload)
            val generation = entry.localGeneration.takeIf { it > 0L } ?: index.toLong()
            val current = latestEntryByCloudBookId[payload.cloudBookId]
            if (current == null || generation >= current.first) {
                latestEntryByCloudBookId[payload.cloudBookId] = generation to entry.mutationId
            }
        }
        val latestEntries = latestEntryByCloudBookId.values.map { (_, mutationId) -> resolvedEntries.getValue(mutationId) }
        val latestMutationIds = latestEntries.mapTo(mutableSetOf()) { entry -> entry.mutationId }
        entries.filter { entry ->
            entry.state == SyncOutboxEntry.STATE_PENDING &&
                entry.mutationId !in latestMutationIds &&
                entry.mutationId !in unresolvedMutationIds
        }
            .forEach { entry -> syncOutboxDatabase.delete(entry.mutationId) }

        // A dispatched mutation may have reached the server before the client
        // lost its response. Retry those IDs for idempotent reconciliation;
        // only collapse pending snapshots that have never been sent.
        val entriesToPush = entries.filter { entry ->
            entry.mutationId in resolvedEntries &&
                (entry.mutationId in latestMutationIds ||
                    entry.state == SyncOutboxEntry.STATE_DISPATCHED)
        }
        val cloudEntries = entriesToPush.map { entry -> resolvedEntries.getValue(entry.mutationId) }
        val summary = progressSyncEngine.push(
            entries = cloudEntries,
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

    private suspend fun SyncOutboxEntry.toCloudReadingPositionEntry(): SyncOutboxEntry? {
        try {
            json.decodeFromString<ParrotCloudReadingPositionPayload>(payload)
            return this
        } catch (_: SerializationException) {
            // Local-source reader positions use the shared local mutation shape.
        }

        val localPosition = try {
            json.decodeFromString<LocalReadingPositionMutation>(payload)
        } catch (_: SerializationException) {
            // Undecodable payloads stay in the outbox; they must not abort the batch.
            return null
        }
        val libraryBook = localPosition.position.libraryBookId
            ?.let { libraryBookId -> libraryBooksDatabase.getLibraryBookById(libraryBookId) }
            ?: localPosition.contentHash?.let { contentHash ->
                libraryBooksDatabase.getLibraryBookByContentHash(
                    contentHashAlgorithm = localPosition.contentHashAlgorithm
                        ?: DEFAULT_CONTENT_HASH_ALGORITHM,
                    contentHash = contentHash,
                )
            }
            ?: return null

        val cloudBookId = libraryBook.cloudBookId ?: return null
        val cloudPayload = localPosition.toParrotCloudReadingPositionPayload(
            libraryBook = libraryBook,
            cloudBookId = cloudBookId,
        )
        return copy(payload = json.encodeToString(cloudPayload))
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

    private suspend fun pushLibraryMutations(
        cloudUserId: String,
        entries: List<SyncOutboxEntry>,
        cursor: String,
    ): Int {
        if (entries.isEmpty()) return 0
        // Drain more than one batch per pass so a large reading-session
        // backfill does not trickle out at one batch per sync request.
        var pushedCount = 0
        var chunk = entries.filterLibraryMutationChannelEntries()
        val attemptedMutationIds = mutableSetOf<String>()
        var chunkCount = 0
        while (chunk.isNotEmpty() && chunkCount < MAX_LIBRARY_PUSH_CHUNKS) {
            val freshChunk = chunk.filter { entry -> attemptedMutationIds.add(entry.mutationId) }
            if (freshChunk.isEmpty()) break
            val summary = libraryMutationSyncEngine.push(
                entries = freshChunk,
                transport = libraryMutationTransport,
                cursor = cursor,
                applier = libraryMutationApplier,
            )
            pushedCount += summary.acknowledgedCount + summary.conflictCount
            chunkCount++
            if (chunkCount >= MAX_LIBRARY_PUSH_CHUNKS) break
            chunk = syncOutboxPreflight.selectEligible(
                remoteAccountId = cloudUserId,
                maxEntries = SYNC_BATCH_SIZE,
                capability = outboxCapability,
            ).filterLibraryMutationChannelEntries()
        }
        return pushedCount
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

            ENTITY_TYPE_BOOK_FILE -> bookFileChangeApplier.apply(payload)
            SyncOutboxEntry.ENTITY_TYPE_READING_SESSION -> readingSessionChangeApplier.apply(payload)
        }
    }

    private companion object {
        const val SYNC_BATCH_SIZE = 50
        const val MAX_LIBRARY_PUSH_CHUNKS = 5
        const val ENTITY_TYPE_BOOK_FILE = "book_file"
        const val DEFAULT_CONTENT_HASH_ALGORITHM = "sha-256-v1"
    }
}

@Serializable
internal data class LocalReadingPositionMutation(
    val bookUuid: String,
    val contentHash: String?,
    val contentHashAlgorithm: String?,
    val position: ServerPosition,
)

/**
 * Library-mutation channel membership mirrors the split in SyncBoundedPass:
 * everything except reading_position, which must stay on the progress channel
 * because it needs the progress engine's payload conversion, book linking and
 * latest-snapshot collapsing. Feeding progress entries to the library-mutation
 * engine would also poison their mutation ids on the server with responses
 * that the progress channel can never replay past.
 */
internal fun List<SyncOutboxEntry>.filterLibraryMutationChannelEntries(): List<SyncOutboxEntry> {
    return filter { entry -> entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION }
}

internal fun LocalReadingPositionMutation.toParrotCloudReadingPositionPayload(
    libraryBook: LibraryBookEntity,
    cloudBookId: String,
): ParrotCloudReadingPositionPayload {
    return ParrotCloudReadingPositionPayload(
        cloudBookId = cloudBookId,
        libraryBookId = libraryBook.libraryBookId,
        position = position.copy(
            bookUuid = bookUuid,
            serverId = PARROT_CLOUD_SERVER_ID,
            libraryBookId = libraryBook.libraryBookId,
        ),
    )
}
