package com.retro99.server.parrotcloud

import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.links.BookLinksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [BookLinksDatabase] that keeps the real one's rule: one live link per member. */
internal class ParrotTestBookLinksDatabase(
    vararg initialLinks: BookLinkEntity,
) : BookLinksDatabase {
    val links = MutableStateFlow(initialLinks.associateBy(BookLinkEntity::linkId))
    val decisions = MutableStateFlow(emptyMap<String, BookLinkDecisionEntity>())
    val outbox = mutableListOf<SyncOutboxEntry>()

    val liveLinks: List<BookLinkEntity>
        get() = links.value.values.filter { link -> link.deletedAt == null }

    override fun observeLinks(): Flow<List<BookLinkEntity>> = links.map { liveLinks }

    override fun observeDecisions(): Flow<List<BookLinkDecisionEntity>> =
        decisions.map { byKey -> byKey.values.toList() }

    override suspend fun getLinks(): List<BookLinkEntity> = liveLinks

    override suspend fun getLink(linkId: String): BookLinkEntity? = links.value[linkId]

    override suspend fun getDecision(pairKey: String): BookLinkDecisionEntity? =
        decisions.value[pairKey]

    override suspend fun write(write: BookLinkWrite) {
        val updated = links.value.toMutableMap()
        write.links.forEach { link ->
            updated[link.linkId] = if (link.deletedAt != null) {
                link.copy(members = emptyList())
            } else {
                link
            }
        }
        val liveMembers = updated.values
            .filter { link -> link.deletedAt == null }
            .flatMap { link -> link.members }
        check(liveMembers.size == liveMembers.distinct().size) {
            "UNIQUE constraint failed: book_link_members.member_key"
        }
        links.value = updated
        decisions.value = decisions.value +
            write.decisions.associateBy(BookLinkDecisionEntity::pairKey)
        outbox += write.outboxEntries
    }

    override suspend fun setLinkRemoteRevision(linkId: String, remoteRevision: Long) {
        val link = links.value[linkId] ?: return
        links.value = links.value + (linkId to link.copy(remoteRevision = remoteRevision))
    }

    override suspend fun setDecisionRemoteRevision(pairKey: String, remoteRevision: Long) {
        val decision = decisions.value[pairKey] ?: return
        decisions.value = decisions.value +
            (pairKey to decision.copy(remoteRevision = remoteRevision))
    }
}
