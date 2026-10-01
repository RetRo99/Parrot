package com.retro99.server.parrotcloud

import co.touchlab.kermit.Logger
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.mergeLinks
import com.retro99.books.domain.model.links.olderLinkId
import com.retro99.books.domain.model.links.repeatsSource
import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.links.BookLinksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Applies Parrot Cloud's book links and link decisions to this device: pulled changes, and
 * the results of this device's own pushes.
 *
 * Parrot Cloud allows a copy in only one link. When it holds a link that shares a copy
 * with a link on this device, the two are merged here into the older link and both changes
 * are queued. If they can't be merged (a source would repeat), Parrot Cloud's link wins.
 */
@Single
class ParrotCloudBookLinkSync(
    @Provided private val bookLinksDatabase: BookLinksDatabase,
) {
    private val logger = Logger.withTag("ParrotCloudBookLinkSync")

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun applyRemoteLink(payload: JsonElement) {
        val remote = json.decodeFromJsonElement<ParrotCloudBookLinkPayload>(payload)
        val local = bookLinksDatabase.getLink(remote.linkId)
        // A change this device has already seen, usually the echo of its own push. Applying
        // it again would undo anything changed here since.
        val localRevision = local?.remoteRevision
        val remoteRevision = remote.remoteRevision
        if (localRevision != null && remoteRevision != null && remoteRevision <= localRevision) {
            return
        }
        if (remote.deleted) {
            if (local == null) return
            val now = now()
            bookLinksDatabase.write(
                BookLinkWrite(
                    links = listOf(
                        local.copy(
                            members = emptyList(),
                            updatedAt = now,
                            remoteRevision = remote.remoteRevision,
                            deletedAt = local.deletedAt ?: now,
                        ),
                    ),
                ),
            )
            return
        }
        reconcile(remote.toEntity(local, remote.members), pushSurvivor = false)
    }

    suspend fun applyRemoteDecision(payload: JsonElement) {
        val remote = json.decodeFromJsonElement<ParrotCloudBookLinkDecisionPayload>(payload)
        val local = bookLinksDatabase.getDecision(remote.pairKey)
        if (local != null && !isNewer(remote.decidedAt, local.decidedAt)) {
            // Last write wins; this device's decision is at least as recent.
            return
        }
        bookLinksDatabase.write(
            BookLinkWrite(
                decisions = listOf(
                    BookLinkDecisionEntity(
                        pairKey = remote.pairKey,
                        decision = remote.decision,
                        decidedAt = remote.decidedAt,
                        remoteRevision = remote.remoteRevision,
                    ),
                ),
            ),
        )
    }

    suspend fun onAccepted(entry: SyncOutboxEntry, response: SyncMutationResponse) {
        val revision = response.revision ?: return
        when (entry.entityType) {
            SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK ->
                bookLinksDatabase.setLinkRemoteRevision(entry.entityId, revision)
            SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION ->
                bookLinksDatabase.setDecisionRemoteRevision(entry.entityId, revision)
        }
    }

    suspend fun onConflict(entry: SyncOutboxEntry, response: SyncMutationResponse) {
        if (entry.entityType != SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK) return
        val payload = response.payload ?: return
        val remote = json.decodeFromString<ParrotCloudBookLinkPayload>(payload)
        if (remote.deleted) return
        val local = bookLinksDatabase.getLink(remote.linkId)
        if (remote.linkId != entry.entityId) {
            // Another live link in Parrot Cloud holds one of the pushed link's copies.
            reconcile(remote.toEntity(local, remote.members), pushSurvivor = false)
            return
        }
        // A stale revision of the same link: another device changed it first. Keep what
        // both sides added when that is still a valid link, otherwise keep this device's.
        if (local == null || local.deletedAt != null) return
        val mutation = json.decodeFromString<ParrotCloudBookLinkPayload>(entry.payload)
        val removed = mutation.removedMembers.toSet()
        val joined = (local.members + remote.members).distinct().filterNot { member ->
            member in removed
        }.sorted()
        val members = if (joined.toCopyKeys().repeatsSource()) {
            local.members.filterNot { member -> member in removed }
        } else {
            joined
        }
        reconcile(
            remote.toEntity(local, members),
            pushSurvivor = members.sorted() != remote.members.sorted(),
            removedMembers = mutation.removedMembers,
        )
    }

    /**
     * Stores [incoming] (Parrot Cloud's link, already at its revision), first merging any
     * other local link that shares a copy with it.
     */
    private suspend fun reconcile(
        incoming: BookLinkEntity,
        pushSurvivor: Boolean,
        removedMembers: List<String> = emptyList(),
    ) {
        val now = now()
        var survivor = incoming
        var push = pushSurvivor
        val dropped = mutableListOf<BookLinkEntity>()
        val overlapping = bookLinksDatabase.getLinks().filter { link ->
            link.linkId != incoming.linkId &&
                link.members.any { member -> member in incoming.members }
        }
        overlapping.forEach { other ->
            val merged = mergeLinks(survivor.toDomain(), other.toDomain())
            if (merged == null) {
                logger.w {
                    "Link ${other.linkId} can't merge with Parrot Cloud's ${survivor.linkId} " +
                        "(a source would repeat); keeping Parrot Cloud's link"
                }
                dropped += other
                return@forEach
            }
            val keptId = olderLinkId(
                survivor.linkId,
                survivor.createdAt,
                other.linkId,
                other.createdAt,
            )
            val (kept, lost) = if (keptId == survivor.linkId) {
                survivor to other
            } else {
                other to survivor
            }
            survivor = kept.copy(
                members = (survivor.members + other.members).distinct().sorted(),
                updatedAt = now,
            )
            dropped += lost
            push = true
        }
        val tombstones = dropped.map { link ->
            link.copy(members = emptyList(), updatedAt = now, deletedAt = now)
        }
        bookLinksDatabase.write(
            BookLinkWrite(
                links = tombstones + survivor,
                // Deletions first, so Parrot Cloud frees the copies before the survivor
                // claims them.
                outboxEntries = tombstones.map { link -> link.toOutbox() } +
                    listOfNotNull(survivor.takeIf { push }?.toOutbox(removedMembers)),
            ),
        )
    }

    private fun ParrotCloudBookLinkPayload.toEntity(
        local: BookLinkEntity?,
        members: List<String>,
    ): BookLinkEntity {
        val now = now()
        return BookLinkEntity(
            linkId = linkId,
            members = members.sorted(),
            createdAt = createdAt ?: local?.createdAt ?: now,
            updatedAt = now,
            remoteRevision = remoteRevision,
        )
    }

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
            payload = json.encodeToString(
                ParrotCloudBookLinkPayload(
                    linkId = linkId,
                    members = members,
                    deleted = deleted,
                    removedMembers = removedMembers,
                ),
            ),
            baseRevision = remoteRevision,
        )
    }

    private fun BookLinkEntity.toDomain() = BookLink(linkId, members.toCopyKeys())

    private fun List<String>.toCopyKeys(): Set<CopyKey> =
        mapNotNull { member -> CopyKey.parse(member) }.toSet()

    private fun isNewer(candidate: String, current: String): Boolean {
        val candidateInstant = Instant.parseOrNull(candidate)
        val currentInstant = Instant.parseOrNull(current)
        return if (candidateInstant != null && currentInstant != null) {
            candidateInstant > currentInstant
        } else {
            candidate > current
        }
    }

    private fun now(): String = Clock.System.now().toString()
}
