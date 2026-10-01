package com.retro99.database.implementation.dao.links

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Book_link_decisions
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.SelectLiveBookLinkMembers
import com.retro99.database.implementation.dao.sync.enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class BookLinksSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val database get() = databaseManager.getDatabase()

    fun observeLinks(): Flow<List<BookLinkEntity>> =
        database.observeBookLinkEntities(Dispatchers.IO)

    fun observeDecisions(): Flow<List<BookLinkDecisionEntity>> =
        database.bookLinkQueries.selectBookLinkDecisions()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { row -> row.toEntity() } }

    suspend fun getLinks(): List<BookLinkEntity> = withContext(Dispatchers.IO) {
        database.bookLinkQueries.selectLiveBookLinkMembers().executeAsList().toEntities()
    }

    suspend fun getLink(linkId: String): BookLinkEntity? = withContext(Dispatchers.IO) {
        database.readBookLink(linkId)
    }

    suspend fun getDecision(pairKey: String): BookLinkDecisionEntity? =
        withContext(Dispatchers.IO) {
            database.bookLinkQueries.getBookLinkDecision(pairKey).executeAsOneOrNull()?.toEntity()
        }

    suspend fun write(write: BookLinkWrite) = withContext(Dispatchers.IO) {
        database.writeBookLinks(write)
    }

    suspend fun setLinkRemoteRevision(linkId: String, remoteRevision: Long) =
        withContext(Dispatchers.IO) {
            database.bookLinkQueries.setBookLinkRemoteRevision(remoteRevision, linkId)
        }

    suspend fun setDecisionRemoteRevision(pairKey: String, remoteRevision: Long) =
        withContext(Dispatchers.IO) {
            database.bookLinkQueries.setBookLinkDecisionRemoteRevision(remoteRevision, pairKey)
        }
}

internal fun AppDatabase.observeBookLinkEntities(
    context: CoroutineContext,
): Flow<List<BookLinkEntity>> =
    bookLinkQueries.selectLiveBookLinkMembers()
        .asFlow()
        .mapToList(context)
        .map { rows -> rows.toEntities() }

internal fun AppDatabase.readBookLink(linkId: String): BookLinkEntity? {
    val link = bookLinkQueries.getBookLink(linkId).executeAsOneOrNull() ?: return null
    return BookLinkEntity(
        linkId = link.link_id,
        members = bookLinkQueries.getBookLinkMembers(linkId).executeAsList(),
        createdAt = link.created_at,
        updatedAt = link.updated_at,
        remoteRevision = link.remote_revision,
        deletedAt = link.deleted_at,
    )
}

internal fun AppDatabase.readBookLinkDecisions(): List<BookLinkDecisionEntity> =
    bookLinkQueries.selectBookLinkDecisions().executeAsList().map { row -> row.toEntity() }

/**
 * Applies [write] in one transaction. Tombstones go first so their members are free to
 * join another link in the same write. A member that would end up in two links violates
 * the UNIQUE constraint and rolls the whole write back.
 */
internal fun AppDatabase.writeBookLinks(write: BookLinkWrite) {
    transaction {
        val (tombstones, liveLinks) = write.links.partition { link -> link.deletedAt != null }
        tombstones.forEach { link ->
            upsertBookLinkRow(link)
            bookLinkQueries.deleteBookLinkMembers(link.linkId)
        }
        liveLinks.forEach { link ->
            upsertBookLinkRow(link)
            val existing = bookLinkQueries.getBookLinkMembers(link.linkId).executeAsList().toSet()
            val wanted = link.members.toSet()
            (existing - wanted).forEach { member ->
                bookLinkQueries.deleteBookLinkMember(link.linkId, member)
            }
            (wanted - existing).forEach { member ->
                bookLinkQueries.insertBookLinkMember(
                    link_id = link.linkId,
                    member_key = member,
                    added_at = link.updatedAt,
                )
            }
        }
        write.decisions.forEach { decision ->
            bookLinkQueries.upsertBookLinkDecision(
                pair_key = decision.pairKey,
                decision = decision.decision,
                decided_at = decision.decidedAt,
                remote_revision = decision.remoteRevision,
            )
        }
        write.outboxEntries.forEach { entry -> syncOutboxQueries.enqueue(entry) }
    }
}

private fun AppDatabase.upsertBookLinkRow(link: BookLinkEntity) {
    bookLinkQueries.upsertBookLink(
        link_id = link.linkId,
        created_at = link.createdAt,
        updated_at = link.updatedAt,
        remote_revision = link.remoteRevision,
        deleted_at = link.deletedAt,
    )
}

private fun List<SelectLiveBookLinkMembers>.toEntities(): List<BookLinkEntity> =
    groupBy { row -> row.link_id }.map { (linkId, rows) ->
        val first = rows.first()
        BookLinkEntity(
            linkId = linkId,
            members = rows.map { row -> row.member_key },
            createdAt = first.created_at,
            updatedAt = first.updated_at,
            remoteRevision = first.remote_revision,
        )
    }

private fun Book_link_decisions.toEntity() = BookLinkDecisionEntity(
    pairKey = pair_key,
    decision = decision,
    decidedAt = decided_at,
    remoteRevision = remote_revision,
)
