package com.retro99.books.data.links

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.model.BookLinkJsonCodec
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.model.links.olderLinkId
import com.retro99.books.domain.model.links.pairKey
import com.retro99.books.domain.model.links.repeatedSource
import com.retro99.books.domain.model.links.sameSourceLinkError
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.links.BookLinksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Stores links on this device. Every change is one database transaction that also queues
 * the sync outbox entries; they stay queued until a Parrot Cloud account is active.
 */
@OptIn(ExperimentalUuidApi::class)
@Single(binds = [BookLinksRepository::class])
internal class BookLinksDataRepository(
    @Provided private val bookLinksDatabase: BookLinksDatabase,
    @Provided private val databaseExecutor: DatabaseExecutor,
) : BookLinksRepository {
    // Each change reads the links and then writes; two at once could both miss the other.
    private val writeMutex = Mutex()

    override fun observeLinks(): Flow<List<BookLink>> =
        bookLinksDatabase.observeLinks().map { links ->
            links.map { link -> link.toDomain() }
        }

    override fun observeDecisions(): Flow<Map<String, LinkDecision>> =
        bookLinksDatabase.observeDecisions().map { decisions ->
            decisions.mapNotNull { decision -> decision.toDomain() }
                .associateBy { decision -> decision.pairKey }
        }

    override suspend fun link(first: CopyKey, second: CopyKey): AppResult<BookLink> {
        if (first.source == second.source) return Err(sameSourceLinkError(first.source))
        return databaseExecutor.executeDatabaseOperation {
            writeMutex.withLock { linkLocked(first, second) }
        }.andThen { result -> result }
    }

    override suspend fun unlink(copy: CopyKey): CompletableResult =
        databaseExecutor.executeDatabaseOperation {
            writeMutex.withLock { unlinkLocked(copy) }
        }

    override suspend fun decide(
        first: CopyKey,
        second: CopyKey,
        decision: LinkDecisionType,
    ): CompletableResult = databaseExecutor.executeDatabaseOperation {
        val entity = decisionEntity(first, second, decision, now())
        bookLinksDatabase.write(
            BookLinkWrite(decisions = listOf(entity), outboxEntries = listOf(entity.toOutbox())),
        )
    }

    private suspend fun linkLocked(first: CopyKey, second: CopyKey): AppResult<BookLink> {
        val links = bookLinksDatabase.getLinks()
        val firstLink = links.firstOrNull { link -> first.value in link.members }
        val secondLink = links.firstOrNull { link -> second.value in link.members }
        if (firstLink != null && firstLink.linkId == secondLink?.linkId) {
            return Ok(firstLink.toDomain())
        }

        val now = now()
        val members = (firstLink?.toDomain()?.members.orEmpty() + first) +
            (secondLink?.toDomain()?.members.orEmpty() + second)
        members.repeatedSource()?.let { source -> return Err(sameSourceLinkError(source)) }

        val survivorId = when {
            firstLink != null && secondLink != null -> olderLinkId(
                firstLink.linkId,
                firstLink.createdAt,
                secondLink.linkId,
                secondLink.createdAt,
            )
            else -> firstLink?.linkId ?: secondLink?.linkId
        }
        val existing = listOfNotNull(firstLink, secondLink)
        val base = existing.firstOrNull { link -> link.linkId == survivorId }
        val survivor = BookLinkEntity(
            linkId = base?.linkId ?: Uuid.random().toString(),
            members = members.map { key -> key.value }.sorted(),
            createdAt = base?.createdAt ?: now,
            updatedAt = now,
            remoteRevision = base?.remoteRevision,
        )
        val dropped = existing
            .filter { link -> link.linkId != survivor.linkId }
            .map { link -> link.copy(members = emptyList(), updatedAt = now, deletedAt = now) }
        bookLinksDatabase.write(
            BookLinkWrite(
                links = dropped + survivor,
                expectedMemberships = links.associate { link -> link.linkId to link.members.toSet() },
                // Deletions first, so Parrot Cloud frees the members before the survivor
                // claims them.
                outboxEntries = dropped.map { link -> link.toOutbox() } + survivor.toOutbox(),
            ),
        )
        return Ok(survivor.toDomain())
    }

    private suspend fun unlinkLocked(copy: CopyKey) {
        val links = bookLinksDatabase.getLinks()
        val link = links
            .firstOrNull { link -> copy.value in link.members }
            ?: return
        val now = now()
        val remaining = link.members - copy.value
        val decisions = remaining.mapNotNull { member -> CopyKey.parse(member) }
            .map { other -> decisionEntity(copy, other, LinkDecisionType.Never, now) }
        val updated = if (remaining.size < MIN_LINK_MEMBERS) {
            link.copy(members = emptyList(), updatedAt = now, deletedAt = now)
        } else {
            link.copy(members = remaining, updatedAt = now)
        }
        bookLinksDatabase.write(
            BookLinkWrite(
                links = listOf(updated),
                expectedMemberships = links.associate { stored -> stored.linkId to stored.members.toSet() },
                decisions = decisions,
                outboxEntries = listOf(updated.toOutbox(removedMembers = listOf(copy.value))) +
                    decisions.map { decision -> decision.toOutbox() },
            ),
        )
    }

    private fun decisionEntity(
        first: CopyKey,
        second: CopyKey,
        decision: LinkDecisionType,
        decidedAt: String,
    ) = BookLinkDecisionEntity(
        pairKey = pairKey(first, second),
        decision = decision.value,
        decidedAt = decidedAt,
    )

    private fun BookLinkEntity.toOutbox(
        removedMembers: List<String> = emptyList(),
    ): SyncOutboxEntry {
        val deleted = deletedAt != null
        return SyncOutboxEntry.new(
            entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
            entityId = linkId,
            operation = if (deleted) {
                SyncOutboxEntry.OPERATION_DELETE
            } else {
                SyncOutboxEntry.OPERATION_UPSERT
            },
            payload = BookLinkJsonCodec.encodeLink(linkId, members, deleted, removedMembers),
            baseRevision = remoteRevision,
        )
    }

    private fun BookLinkDecisionEntity.toOutbox() = SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
        entityId = pairKey,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = BookLinkJsonCodec.encodeDecision(pairKey, decision, decidedAt),
        baseRevision = remoteRevision,
    )

    private fun BookLinkEntity.toDomain() = BookLink(
        linkId = linkId,
        // A member this app version can't read (a source added later) is ignored.
        members = members.mapNotNull { member -> CopyKey.parse(member) }.toSet(),
    )

    private fun BookLinkDecisionEntity.toDomain(): LinkDecision? {
        val type = LinkDecisionType.fromValue(decision) ?: return null
        val decidedAt = Instant.parseOrNull(decidedAt) ?: return null
        return LinkDecision(pairKey = pairKey, type = type, decidedAt = decidedAt)
    }

    private fun now(): String = Clock.System.now().toString()

    private companion object {
        const val MIN_LINK_MEMBERS = 2
    }
}
