package com.retro99.database.implementation.dao.links

import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/** Called inside the library merge transaction, before the duplicate book is deleted. */
internal fun AppDatabase.mergeLibraryCopyLinks(fromId: String, intoId: String) {
    val fromKey = "library:$fromId"
    val intoKey = "library:$intoId"
    val now = Clock.System.now().toString()
    val liveIds = bookLinkQueries.selectLiveBookLinkMembers().executeAsList()
        .map { row -> row.link_id }.distinct()
    val links = liveIds.mapNotNull { linkId -> readBookLink(linkId) }
    val fromLink = links.firstOrNull { link -> fromKey in link.members }
    val intoLink = links.firstOrNull { link -> intoKey in link.members }
    val changed = if (fromLink == null) {
        emptyList()
    } else if (intoLink == null) {
        listOf(fromLink.copy(
            members = fromLink.members.map { member ->
                if (member == fromKey) intoKey else member
            }.sorted(),
            updatedAt = now,
        ))
    } else {
        val members = (fromLink.members + intoLink.members)
            .map { member -> if (member == fromKey) intoKey else member }.distinct().sorted()
        val repeatsSource = members.map { member -> member.substringBefore(':') }
            .distinct().size != members.size
        // If two server copies disagree, retain the surviving book's existing link.
        val survivor = if (repeatsSource) intoLink else listOf(fromLink, intoLink)
            .minWith(compareBy<BookLinkEntity, Instant?>(nullsLast()) { link ->
                Instant.parseOrNull(link.createdAt)
            }.thenBy { link -> link.linkId })
        val dropped = if (survivor.linkId == fromLink.linkId) intoLink else fromLink
        listOf(
            dropped.copy(members = emptyList(), updatedAt = now, deletedAt = now),
            survivor.copy(
                members = if (repeatsSource) survivor.members else members,
                updatedAt = now,
            ),
        )
    }

    // Carry unlink intent into replacement snapshots so a stale revision cannot restore it.
    val changedIds = changed.mapTo(mutableSetOf()) { link -> link.linkId }
    val removedMembers = syncOutboxQueries.getAllMutations().executeAsList()
        .filter { mutation ->
            mutation.entity_type == SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK &&
                mutation.entity_id in changedIds && mutation.state == SyncOutboxEntry.STATE_PENDING
        }.flatMap { mutation ->
            Json.parseToJsonElement(mutation.payload).jsonObject["removed_members"]
                ?.jsonArray.orEmpty().map { member -> member.jsonPrimitive.content }
        }.plus(fromKey).distinct()

    // Replace unsent snapshots, not dispatched mutation IDs (which may already be accepted).
    changed.forEach { link ->
        syncOutboxQueries.deletePendingMutationsForEntityAnyUser(
            SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
            link.linkId,
        )
    }
    val decisions = readBookLinkDecisions()
    val movedDecisions = decisions.filter { decision ->
        fromKey in decision.pairKey.split('|')
    }.map { decision ->
        val pair = decision.pairKey.split('|').map { member ->
            if (member == fromKey) intoKey else member
        }.sorted().joinToString("|")
        val existing = decisions.firstOrNull { candidate -> candidate.pairKey == pair }
        bookLinkQueries.deleteBookLinkDecision(decision.pairKey)
        syncOutboxQueries.deletePendingMutationsForEntityAnyUser(
            SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
            decision.pairKey,
        )
        if (existing != null && Instant.parse(existing.decidedAt) >=
            Instant.parse(decision.decidedAt)
        ) {
            existing
        } else {
            decision.copy(pairKey = pair, remoteRevision = existing?.remoteRevision)
        }
    }
    writeBookLinks(BookLinkWrite(
        links = changed,
        decisions = movedDecisions,
        outboxEntries = changed.map { link ->
            link.mergeOutbox(removedMembers.filterNot { member -> member in link.members })
        } +
            movedDecisions.map { decision ->
                SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
                    entityId = decision.pairKey,
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    baseRevision = decision.remoteRevision,
                    payload = buildJsonObject {
                        put("pair_key", decision.pairKey)
                        put("decision", decision.decision)
                        put("decided_at", decision.decidedAt)
                    }.toString(),
                )
            },
    ))
}

private fun BookLinkEntity.mergeOutbox(removedMembers: List<String>): SyncOutboxEntry =
    SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
        entityId = linkId,
        operation = if (deletedAt != null) {
            SyncOutboxEntry.OPERATION_DELETE
        } else {
            SyncOutboxEntry.OPERATION_UPSERT
        },
        baseRevision = remoteRevision,
        payload = buildJsonObject {
            put("link_id", linkId)
            put("members", JsonArray(members.map { member -> JsonPrimitive(member) }))
            put("deleted", deletedAt != null)
            put("removed_members", JsonArray(removedMembers.map { member -> JsonPrimitive(member) }))
        }.toString(),
    )
