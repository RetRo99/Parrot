package com.retro99.database.implementation.dao.links

import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.links.BookLinksDatabase
import kotlinx.coroutines.flow.Flow

internal class BookLinksDatabaseImpl(
    private val dao: BookLinksSqlDelightDao,
) : BookLinksDatabase {
    override fun observeLinks(): Flow<List<BookLinkEntity>> = dao.observeLinks()

    override fun observeDecisions(): Flow<List<BookLinkDecisionEntity>> = dao.observeDecisions()

    override suspend fun getLinks(): List<BookLinkEntity> = dao.getLinks()

    override suspend fun getLink(linkId: String): BookLinkEntity? = dao.getLink(linkId)

    override suspend fun getDecision(pairKey: String): BookLinkDecisionEntity? =
        dao.getDecision(pairKey)

    override suspend fun write(write: BookLinkWrite) = dao.write(write)

    override suspend fun setLinkRemoteRevision(linkId: String, remoteRevision: Long) =
        dao.setLinkRemoteRevision(linkId, remoteRevision)

    override suspend fun setDecisionRemoteRevision(pairKey: String, remoteRevision: Long) =
        dao.setDecisionRemoteRevision(pairKey, remoteRevision)
}
