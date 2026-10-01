package com.retro99.sync.data

import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.links.LinkedCopyWritesDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.EchoClassifier
import com.retro99.sync.domain.ObservedTime
import com.retro99.sync.domain.OwnWrite
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressPushResult
import com.retro99.sync.domain.ProgressSyncTransport
import com.retro99.sync.domain.RemoteProgressSnapshot
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Shared progress delivery and reconciliation primitive.
 *
 * This class deliberately knows nothing about HTTP, Supabase, or a concrete
 * server payload. A destination supplies the codec that turns its durable
 * outbox payload into the canonical [ProgressMutation] model; the engine then
 * owns state transitions and local reconciliation consistently for every
 * backend.
 */
@Single
class ProgressSyncEngine(
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val linkedCopyWritesDatabase: LinkedCopyWritesDatabase,
) {

    suspend fun push(
        entries: List<SyncOutboxEntry>,
        transport: ProgressSyncTransport,
        codec: ProgressOutboxCodec,
        identityResolver: ProgressIdentityResolver = ProgressIdentityResolver.Default,
    ): ProgressPushSummary {
        val progressEntries = entries.filter { entry ->
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
        }
        if (progressEntries.isEmpty()) return ProgressPushSummary()

        progressEntries.forEach { entry ->
            syncOutboxDatabase.markDispatched(entry.mutationId)
        }

        val mutations = progressEntries.map(codec::decode)
        val mutationsById = mutations.associateBy { mutation -> mutation.mutationId }
        val results = try {
            transport.pushProgress(mutations)
        } catch (exception: CancellationException) {
            throw exception
        }
        val resultsByMutationId = results.associateBy { result -> result.mutationId }
        var acknowledgedCount = 0
        var conflictCount = 0
        var retryCount = 0
        var unresolvedCount = 0

        progressEntries.forEach { entry ->
            when (val result = resultsByMutationId[entry.mutationId]) {
                is ProgressPushResult.Accepted -> {
                    acknowledge(entry, result.version)
                    recordWriteMarker(mutationsById[entry.mutationId], result.version)
                    acknowledgedCount++
                }

                is ProgressPushResult.Conflict -> {
                    preserveConflict(entry, result.remote, identityResolver)
                    conflictCount++
                }

                is ProgressPushResult.Rejected -> {
                    scheduleRetry(entry, result)
                    retryCount++
                }

                null -> {
                    // An omitted result is an unresolved delivery outcome, not
                    // an acknowledgement. It remains dispatched for recovery.
                    unresolvedCount++
                }
            }
        }

        return ProgressPushSummary(
            acknowledgedCount = acknowledgedCount,
            conflictCount = conflictCount,
            retryCount = retryCount,
            unresolvedCount = unresolvedCount,
        )
    }

    suspend fun applyRemote(
        remote: RemoteProgressSnapshot,
        accountId: String,
        identityResolver: ProgressIdentityResolver = ProgressIdentityResolver.Default,
    ): ProgressPullOutcome {
        val identity = identityResolver.resolve(remote)
        val pending = syncOutboxDatabase.getPending(accountId)
        val hasPendingLocalProgress = pending.any { entry ->
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION &&
                entry.entityId in setOfNotNull(
                    identity.localBookUuid,
                    identity.libraryBookId,
                    remote.remoteBookId,
                )
        }

        val stored = positionDatabase.getPositionByBookUuid(identity.localBookUuid)
        // Guard 3: re-pulling the same server snapshot leaves the stored row alone, including
        // its origin and observation time.
        if (!hasPendingLocalProgress && stored != null && stored.isSameSnapshotAs(remote)) {
            return ProgressPullOutcome.AppliedToLocal
        }
        val remotePosition = remote.toPositionEntity(
            identity = identity,
            origin = pulledOrigin(remote, identity),
        )

        return if (hasPendingLocalProgress) {
            positionDatabase.upsertRemotePosition(remotePosition)
            ProgressPullOutcome.PreservedLocalProgress
        } else {
            positionDatabase.upsertPosition(remotePosition)
            positionDatabase.deleteRemotePosition(identity.localBookUuid)
            ProgressPullOutcome.AppliedToLocal
        }
    }

    suspend fun refreshRemote(
        entries: List<SyncOutboxEntry>,
        accountId: String,
        transport: ProgressSyncTransport,
        codec: ProgressOutboxCodec,
        identityResolver: ProgressIdentityResolver = ProgressIdentityResolver.Default,
    ): Int {
        val progressEntries = entries.filter { entry ->
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_POSITION
        }
        if (progressEntries.isEmpty()) return 0

        val remoteBookIds = progressEntries
            .map(codec::decode)
            .map { mutation -> mutation.remoteBookId }
            .toSet()
        return refreshRemoteBookIds(
            remoteBookIds = remoteBookIds,
            accountId = accountId,
            transport = transport,
            identityResolver = identityResolver,
        )
    }

    suspend fun refreshRemoteBookIds(
        remoteBookIds: Set<String>,
        accountId: String,
        transport: ProgressSyncTransport,
        identityResolver: ProgressIdentityResolver = ProgressIdentityResolver.Default,
    ): Int {
        if (remoteBookIds.isEmpty()) return 0

        val remoteSnapshots = transport.fetchProgress(remoteBookIds)
        remoteSnapshots.values.forEach { remote ->
            applyRemote(
                remote = remote,
                accountId = accountId,
                identityResolver = identityResolver,
            )
        }
        return remoteSnapshots.size
    }

    private suspend fun acknowledge(
        entry: SyncOutboxEntry,
        version: String?,
    ) {
        version?.toLongOrNull()?.let { revision ->
            positionDatabase.updateRemoteRevision(
                bookUuid = entry.entityId,
                remoteRevision = revision,
                expectedLocalGeneration = entry.localGeneration,
            )
        }
        syncOutboxDatabase.delete(entry.mutationId)
    }

    /** Guard 2: an echo of this device's own write into a linked copy isn't real reading. */
    private suspend fun pulledOrigin(
        remote: RemoteProgressSnapshot,
        identity: ProgressIdentity,
    ): String {
        val ownWrite = linkedCopyWritesDatabase
            .getWriteForBook(identity.localBookUuid, notBefore = writeLogCutoff())
            ?.let { write -> OwnWrite(write.marker, write.totalProgression) }
        val isEcho = EchoClassifier.isEcho(
            pulledMarker = remote.marker,
            pulledTotalProgression = remote.snapshot.totalProgression,
            ownWrite = ownWrite,
        )
        return if (isEcho) PositionEntity.ORIGIN_LINKED_COPY else PositionEntity.ORIGIN_REMOTE
    }

    /**
     * Parrot Cloud's marker is the revision acknowledged for our write into a linked copy.
     * It's recorded only when the acknowledged mutation carries the values we wrote.
     */
    private suspend fun recordWriteMarker(mutation: ProgressMutation?, version: String?) {
        if (mutation == null || version == null) return
        val write = linkedCopyWritesDatabase
            .getWriteForBook(mutation.entityId, notBefore = writeLogCutoff())
            ?: return
        if (write.marker != null) return
        val snapshot = mutation.snapshot
        val sameValues = write.locatorHref == snapshot.locator?.href &&
            write.totalProgression == snapshot.totalProgression &&
            write.audioMs == snapshot.audioTimestampMs
        if (sameValues) linkedCopyWritesDatabase.setMarker(write.targetKey, version)
    }

    private fun writeLogCutoff(): String =
        Clock.System.now().minus(WRITE_LOG_DAYS.days).toString()

    private suspend fun preserveConflict(
        entry: SyncOutboxEntry,
        remote: RemoteProgressSnapshot,
        identityResolver: ProgressIdentityResolver,
    ) {
        val identity = identityResolver.resolve(remote)
        val stored = positionDatabase.getPositionByBookUuid(identity.localBookUuid)
        if (stored?.origin == PositionEntity.ORIGIN_LINKED_COPY) {
            // An automatic write from another linked copy lost to newer reading on that
            // server (Storyteller's 409, guard 13). That's the right outcome, not a choice
            // for the person: the server's position replaces ours, and nothing is retried.
            val origin = pulledOrigin(remote, identity)
            positionDatabase.upsertPosition(remote.toPositionEntity(identity, origin))
            positionDatabase.deleteRemotePosition(identity.localBookUuid)
            syncOutboxDatabase.delete(entry.mutationId)
            return
        }
        applyRemote(
            remote = remote,
            accountId = entry.cloudUserId ?: "",
            identityResolver = identityResolver,
        )
        syncOutboxDatabase.markConflict(
            mutationId = entry.mutationId,
            error = "Remote progress conflict",
        )
    }

    private suspend fun scheduleRetry(
        entry: SyncOutboxEntry,
        result: ProgressPushResult.Rejected,
    ) {
        val delaySeconds = result.retryAfterMillis
            ?.div(1000L)
            ?.coerceAtLeast(1L)
            ?: (1L shl min(entry.attemptCount.coerceAtLeast(0), MAX_BACKOFF_POWER))
        syncOutboxDatabase.recordFailure(
            mutationId = entry.mutationId,
            nextAttemptAt = Clock.System.now().plus(delaySeconds.seconds).toString(),
            error = result.reason,
        )
    }

    private companion object {
        const val MAX_BACKOFF_POWER = 6
        const val WRITE_LOG_DAYS = 7
    }
}

fun interface ProgressOutboxCodec {
    fun decode(entry: SyncOutboxEntry): ProgressMutation
}

fun interface ProgressIdentityResolver {
    suspend fun resolve(remote: RemoteProgressSnapshot): ProgressIdentity

    companion object {
        val Default = ProgressIdentityResolver { remote ->
            val localBookUuid = remote.entityId
                ?: remote.libraryBookId
                ?: remote.remoteBookId
            ProgressIdentity(
                localBookUuid = localBookUuid,
                libraryBookId = remote.libraryBookId,
            )
        }
    }
}

data class ProgressIdentity(
    val localBookUuid: String,
    /** Only meaningful for books in your library; the database decides what it stores. */
    val libraryBookId: String?,
)

data class ProgressPushSummary(
    val acknowledgedCount: Int = 0,
    val conflictCount: Int = 0,
    val retryCount: Int = 0,
    val unresolvedCount: Int = 0,
)

enum class ProgressPullOutcome {
    AppliedToLocal,
    PreservedLocalProgress,
}

/** The same server write (timestamp or revision) at the same place. */
private fun PositionEntity.isSameSnapshotAs(remote: RemoteProgressSnapshot): Boolean {
    val snapshot = remote.snapshot
    val revision = remote.version?.toLongOrNull()
    val sameWrite = (revision != null && revision == remoteRevision) ||
        (snapshot.timestamp != null && snapshot.timestamp == timestamp)
    return sameWrite &&
        locatorHref == snapshot.locator?.href &&
        cssSelector == snapshot.locator?.cssSelector &&
        progression == snapshot.progression &&
        totalProgression == snapshot.totalProgression &&
        audioTimestampMs == snapshot.audioTimestampMs &&
        bookTimeMs == snapshot.bookTimeMs
}

private fun RemoteProgressSnapshot.toPositionEntity(
    identity: ProgressIdentity,
    origin: String,
): PositionEntity {
    return EnginePositionEntity(
        bookUuid = identity.localBookUuid,
        libraryBookId = identity.libraryBookId,
        remoteRevision = version?.toLongOrNull(),
        timestamp = snapshot.timestamp,
        createdAt = snapshot.createdAt,
        updatedAt = snapshot.updatedAt,
        locatorHref = snapshot.locator?.href,
        locatorType = snapshot.locator?.type,
        locatorTitle = snapshot.locator?.title,
        locatorTarget = snapshot.locator?.target,
        cssSelector = snapshot.locator?.cssSelector,
        audioTimestampMs = snapshot.audioTimestampMs,
        chapterIndex = snapshot.chapterIndex,
        progression = snapshot.progression,
        totalChapters = snapshot.totalChapters,
        totalDurationMs = snapshot.totalDurationMs,
        totalProgression = snapshot.totalProgression,
        bookTimeMs = snapshot.bookTimeMs,
        ebookLocationRaw = snapshot.ebookLocationRaw,
        position = snapshot.position,
        origin = origin,
        observedAt = ObservedTime.normalize(observedAt ?: snapshot.updatedAt),
    )
}

private data class EnginePositionEntity(
    override val bookUuid: String,
    override val libraryBookId: String?,
    override val remoteRevision: Long?,
    override val timestamp: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val locatorHref: String?,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val locatorTarget: Int?,
    override val cssSelector: String?,
    override val audioTimestampMs: Long?,
    override val chapterIndex: Int?,
    override val progression: Double?,
    override val totalChapters: Int?,
    override val totalDurationMs: Long?,
    override val totalProgression: Double?,
    override val position: Int?,
    override val origin: String,
    override val observedAt: String?,
    override val bookTimeMs: Long?,
    override val ebookLocationRaw: String?,
) : PositionEntity
