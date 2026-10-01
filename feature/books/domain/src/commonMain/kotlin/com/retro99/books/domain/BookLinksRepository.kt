package com.retro99.books.domain

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import kotlinx.coroutines.flow.Flow

/**
 * Links between copies of the same book on different sources. Links are stored on this
 * device and queued for Parrot Cloud; they never change anything on another server.
 */
interface BookLinksRepository {
    fun observeLinks(): Flow<List<BookLink>>

    /** Decisions by pair key (see `pairKey`). */
    fun observeDecisions(): Flow<Map<String, LinkDecision>>

    /** Links the copies, merging existing links. Fails when a source would repeat. */
    suspend fun link(first: CopyKey, second: CopyKey): AppResult<BookLink>

    /** Removes [copy] from its link and records never-decisions for each pair it had. */
    suspend fun unlink(copy: CopyKey): CompletableResult

    suspend fun decide(
        first: CopyKey,
        second: CopyKey,
        decision: LinkDecisionType,
    ): CompletableResult
}
