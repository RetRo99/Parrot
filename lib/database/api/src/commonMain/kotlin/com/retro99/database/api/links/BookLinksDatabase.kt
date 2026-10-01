package com.retro99.database.api.links

import kotlinx.coroutines.flow.Flow

interface BookLinksDatabase {
    /** Live links with their members. */
    fun observeLinks(): Flow<List<BookLinkEntity>>

    fun observeDecisions(): Flow<List<BookLinkDecisionEntity>>

    /** Live links with their members. */
    suspend fun getLinks(): List<BookLinkEntity>

    /** The link with this id, including a tombstone. */
    suspend fun getLink(linkId: String): BookLinkEntity?

    suspend fun getDecision(pairKey: String): BookLinkDecisionEntity?

    /**
     * Applies [write] in one transaction. Fails, changing nothing, when a member would
     * belong to two live links.
     */
    suspend fun write(write: BookLinkWrite)

    suspend fun setLinkRemoteRevision(linkId: String, remoteRevision: Long)

    suspend fun setDecisionRemoteRevision(pairKey: String, remoteRevision: Long)
}
